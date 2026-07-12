package com.buscai.backend.ingestion

import com.buscai.backend.book.BookRepository
import com.buscai.backend.book.BookVersionRepository
import com.buscai.backend.book.BookVersionStatus
import com.buscai.backend.book.ChunkRepository
import com.buscai.backend.book.EMBEDDING_DIMENSIONS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Path

/**
 * Ver ADR-0008: marcador não-numérico distinto por página, para que o candidato de
 * header/footer de cada página ([TextCleaner.removeRepeatedHeaderFooterLines]) tenha uma
 * assinatura diferente das demais e nenhuma linha real do corpo do texto seja removida por engano
 * (o corpo de cada página é uma única linha, então ela é candidata tanto a header quanto a
 * footer).
 */
private val PAGE_MARKERS = listOf("alfa", "beta", "gama", "delta", "epsilon", "zeta", "eta", "theta", "iota")

private const val PAGE_COUNT = 9
private const val WORDS_PER_PAGE = 200

/** Gera "w<start> w<start+1> ... w<end>". */
private fun words(range: IntRange): String = range.joinToString(" ") { "w$it" }

/**
 * Texto de cada página do PDF de fixture: um marcador único ([PAGE_MARKERS]) seguido de
 * [WORDS_PER_PAGE] tokens sequenciais — dá ao `Chunker` conteúdo suficiente para formar múltiplos
 * chunks válidos (300-800 tokens, overlap 10-20%) de ponta a ponta.
 */
private fun fixtureBookPageTexts(): List<String> =
    (1..PAGE_COUNT).map { page ->
        val start = (page - 1) * WORDS_PER_PAGE + 1
        val end = page * WORDS_PER_PAGE
        "${PAGE_MARKERS[page - 1]} ${words(start..end)}"
    }

/**
 * Testes de ponta a ponta de [IngestionService] (T7) contra um Postgres real via Testcontainers —
 * mesmo padrão de `BookRepositoriesIntegrationTest`/`FlywayMigrationIntegrationTest`, já que a
 * migration V1 usa pgvector/HNSW (H2 do `contextLoads` não serve). O
 * `buscai.ingestion.chunk-embedding-batch-size` é sobrescrito para um valor pequeno (2) para forçar
 * múltiplos lotes de embedding+persistência mesmo com poucos chunks (CA1,
 * `specs/ingestao-pdf/spec.md`).
 */
@Testcontainers
@ActiveProfiles("testcontainers")
@SpringBootTest
class IngestionServiceTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16"))

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            // Pequeno o bastante para forçar múltiplos lotes com só alguns chunks (ver classe).
            registry.add("buscai.ingestion.chunk-embedding-batch-size") { "2" }
        }
    }

    /**
     * Substitui o `EmbeddingClient` real (`VoyageEmbeddingClient`, que faria uma chamada HTTP de
     * verdade) por um fake determinístico, sem rede — convenção de test-writer do projeto.
     */
    @TestConfiguration
    class FakeEmbeddingClientConfig {
        @Bean
        @Primary
        fun fakeEmbeddingClient(): FakeEmbeddingClient = FakeEmbeddingClient()
    }

    /**
     * Fake de [EmbeddingClient] que grava, para cada chamada, quantos textos vieram e se havia uma
     * transação de banco ativa no momento da chamada — é a checagem central de T7 (nenhuma
     * transação de banco pode ficar aberta durante a chamada de rede à Voyage, ver `plan.md`).
     */
    class FakeEmbeddingClient : EmbeddingClient {
        val calls = mutableListOf<List<String>>()
        val transactionActiveDuringCall = mutableListOf<Boolean>()

        override fun embed(texts: List<String>): List<FloatArray> {
            calls += texts
            transactionActiveDuringCall += TransactionSynchronizationManager.isActualTransactionActive()
            return texts.map { text -> FloatArray(EMBEDDING_DIMENSIONS) { i -> (text.hashCode() % 997) / 997f + i * 1e-6f } }
        }
    }

    @Autowired
    lateinit var ingestionService: IngestionService

    @Autowired
    lateinit var bookRepository: BookRepository

    @Autowired
    lateinit var bookVersionRepository: BookVersionRepository

    @Autowired
    lateinit var chunkRepository: ChunkRepository

    @Autowired
    lateinit var fakeEmbeddingClient: FakeEmbeddingClient

    @TempDir
    lateinit var tempDir: Path

    /**
     * [FakeEmbeddingClient] é um bean singleton reaproveitado entre métodos de teste (o contexto
     * Spring é cacheado pelo Testcontainers/`@SpringBootTest`) — sem isso, o estado de uma chamada
     * de teste vazaria para a próxima e quebraria asserções como "zero chamadas novas" (T8) ou
     * "N chamadas neste teste" (T7).
     */
    @BeforeEach
    fun resetFakeEmbeddingClient() {
        fakeEmbeddingClient.calls.clear()
        fakeEmbeddingClient.transactionActiveDuringCall.clear()
    }

    @Test
    fun `ingere um PDF valido de ponta a ponta, persiste book, bookVersion READY e chunks, sem transacao aberta durante o embedding`() {
        val file = PdfFixtures.textPdf(tempDir, fixtureBookPageTexts())

        val outcome = ingestionService.ingest(bookId = "livro-teste-t7", title = "Livro de Teste T7", file = file)

        val completed = outcome as? IngestionOutcome.Completed
        assertNotNull(completed, "esperava IngestionOutcome.Completed, obteve: $outcome")
        checkNotNull(completed)
        assertEquals(PAGE_COUNT, completed.pageCount)
        assertTrue(completed.chunkCount > 0, "esperava pelo menos 1 chunk")

        // CA1: book/version/chunks persistidos e associados corretamente.
        val book = bookRepository.findById("livro-teste-t7").orElseThrow()
        assertEquals("Livro de Teste T7", book.title)
        assertEquals(completed.versionId, book.activeVersionId)

        val version = bookVersionRepository.findById(completed.versionId).orElseThrow()
        assertEquals(BookVersionStatus.READY, version.status)
        assertEquals(PAGE_COUNT, version.pageCount)
        assertEquals(completed.chunkCount, version.chunkCount)
        assertNotNull(version.completedAt)

        val chunks = chunkRepository.findAll().filter { it.bookVersionId == completed.versionId }
        assertEquals(completed.chunkCount, chunks.size)
        chunks.forEach { chunk ->
            assertTrue(chunk.tokenCount in MIN_CHUNK_TOKENS..MAX_CHUNK_TOKENS, "chunk fora da faixa: ${chunk.tokenCount}")
            assertEquals(EMBEDDING_DIMENSIONS, chunk.embedding.size)
            assertTrue(chunk.text.isNotBlank())
        }

        // Lote pequeno (2) força múltiplos lotes de embedding mesmo com poucos chunks.
        assertTrue(fakeEmbeddingClient.calls.size > 1, "esperava múltiplos lotes de embedding, teve ${fakeEmbeddingClient.calls.size}")
        assertEquals(completed.chunkCount, fakeEmbeddingClient.calls.sumOf { it.size })

        // Ponto central de T7: nenhuma transação de banco ficava aberta durante a chamada ao
        // EmbeddingClient (evita esgotar o pool de conexões em livros grandes).
        assertFalse(
            fakeEmbeddingClient.transactionActiveDuringCall.any { it },
            "nenhuma chamada ao EmbeddingClient deveria acontecer com uma transação de banco ativa",
        )
    }

    /**
     * CA3 (`spec.md`): um PDF sem camada de texto extraível (aqui, todas as páginas em branco —
     * mesma simulação usada por `ScannedPdfDetectorTest`) não pode virar uma `BookVersion` `READY`
     * com zero chunks. Sem essa checagem, `ChunkValidator.validate(emptyList())` aprova vacuamente
     * (nenhum chunk para violar nada) e a ingestão "funcionaria" silenciosamente sem indexar nada.
     */
    @Test
    fun `PDF sem camada de texto falha a ingestao com mensagem clara, sem chamar o EmbeddingClient`() {
        val file = PdfFixtures.pdf(tempDir, pageCount = 5, blankPages = setOf(1, 2, 3, 4, 5))

        val outcome = ingestionService.ingest(bookId = "livro-escaneado-t7", title = "Livro Escaneado", file = file)

        val failed = outcome as? IngestionOutcome.Failed
        assertNotNull(failed, "esperava IngestionOutcome.Failed, obteve: $outcome")
        checkNotNull(failed)
        assertTrue(failed.reason.contains("texto"), "mensagem deveria mencionar a ausência de texto: ${failed.reason}")

        val version = bookVersionRepository.findById(checkNotNull(failed.versionId)).orElseThrow()
        assertEquals(BookVersionStatus.FAILED, version.status)

        assertTrue(fakeEmbeddingClient.calls.isEmpty(), "não deveria chamar o EmbeddingClient para um PDF escaneado")
    }

    /**
     * CA4 (`spec.md`), sub-caso "skip" do ADR-0008: mesma `(bookId, fileHash, embeddingModel,
     * embeddingModelVersion)` já `READY` — a segunda ingestão não deve reprocessar nada.
     */
    @Test
    fun `ingerir o mesmo PDF e bookId duas vezes pula a segunda vez, sem novas chamadas ao EmbeddingClient`() {
        val file = PdfFixtures.textPdf(tempDir, fixtureBookPageTexts())

        val first = ingestionService.ingest(bookId = "livro-skip-t8", title = "Livro Skip T8", file = file)
        val completed = first as? IngestionOutcome.Completed
        assertNotNull(completed, "esperava IngestionOutcome.Completed na primeira ingestão, obteve: $first")
        checkNotNull(completed)

        val callsAfterFirst = fakeEmbeddingClient.calls.size

        val second = ingestionService.ingest(bookId = "livro-skip-t8", title = "Livro Skip T8", file = file)

        val skipped = second as? IngestionOutcome.Skipped
        assertNotNull(skipped, "esperava IngestionOutcome.Skipped na segunda ingestão, obteve: $second")
        checkNotNull(skipped)
        assertEquals("livro-skip-t8", skipped.bookId)
        assertEquals(completed.versionId, skipped.existingVersionId)

        assertEquals(
            callsAfterFirst,
            fakeEmbeddingClient.calls.size,
            "a segunda ingestão (skip) não deveria gerar nenhuma chamada nova ao EmbeddingClient",
        )
    }

    /**
     * CA4 (`spec.md`), sub-caso "bloqueio" do ADR-0008: mesmo `bookId`, PDF com conteúdo (logo
     * hash) diferente e sem `reindex=true` — a ingestão é bloqueada, sem reprocessar, e a versão
     * ativa anterior permanece intacta.
     */
    @Test
    fun `ingerir o mesmo bookId com PDF diferente sem reindex bloqueia, sem novas chamadas ao EmbeddingClient e sem alterar a versao`() {
        val firstFile = PdfFixtures.textPdf(tempDir, fixtureBookPageTexts())
        val first = ingestionService.ingest(bookId = "livro-bloqueio-t8", title = "Livro Bloqueio T8", file = firstFile)
        val completed = first as? IngestionOutcome.Completed
        assertNotNull(completed, "esperava IngestionOutcome.Completed na primeira ingestão, obteve: $first")
        checkNotNull(completed)

        val callsAfterFirst = fakeEmbeddingClient.calls.size

        // Conteúdo diferente (deslocamento das palavras de cada página) => hash diferente.
        val differentPageTexts =
            fixtureBookPageTexts().map { pageText -> "$pageText wextra1 wextra2 wextra3" }
        val secondFile = PdfFixtures.textPdf(tempDir, differentPageTexts)

        val second = ingestionService.ingest(bookId = "livro-bloqueio-t8", title = "Livro Bloqueio T8", file = secondFile)

        val reindexRequired = second as? IngestionOutcome.ReindexRequired
        assertNotNull(reindexRequired, "esperava IngestionOutcome.ReindexRequired, obteve: $second")
        checkNotNull(reindexRequired)
        assertEquals("livro-bloqueio-t8", reindexRequired.bookId)
        assertEquals(completed.versionId, reindexRequired.existingVersionId)

        assertEquals(
            callsAfterFirst,
            fakeEmbeddingClient.calls.size,
            "a ingestão bloqueada não deveria gerar nenhuma chamada nova ao EmbeddingClient",
        )

        val book = bookRepository.findById("livro-bloqueio-t8").orElseThrow()
        assertEquals(completed.versionId, book.activeVersionId, "versao ativa nao deveria mudar quando o bloqueio ocorre")

        val version = bookVersionRepository.findById(completed.versionId).orElseThrow()
        assertEquals(BookVersionStatus.READY, version.status, "versao ativa anterior deveria continuar READY")
    }
}
