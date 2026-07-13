package com.buscai.backend.ingestion

import com.buscai.backend.catalog.BookRepository
import com.buscai.backend.catalog.BookVersionRepository
import com.buscai.backend.catalog.BookVersionStatus
import com.buscai.backend.catalog.Chunk
import com.buscai.backend.catalog.ChunkRepository
import com.buscai.backend.catalog.EMBEDDING_DIMENSIONS
import com.buscai.backend.ingestion.chunking.MAX_CHUNK_TOKENS
import com.buscai.backend.ingestion.chunking.MIN_CHUNK_TOKENS
import com.buscai.backend.ingestion.embedding.EmbeddingClient
import com.buscai.backend.ingestion.embedding.EmbeddingClientException
import com.buscai.backend.ingestion.pdf.PdfFixtures
import com.buscai.backend.ingestion.pdf.PdfTextExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito
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
import java.io.File
import java.nio.file.Files
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
     * Substitui o `PdfTextExtractor` real por um spy que delega para a implementação real (mesmo
     * padrão de `IngestionServiceVolumeTest.VolumeTestConfig`), mas pode ser instruído (via
     * [ExtractRangeFailureSwitch.shouldFail]) a lançar uma exceção genérica em [extractRange] — sem
     * alterar [PdfTextExtractor.pageCount], que continua real. Isso simula um bug de programação
     * genuíno dentro de `extractCleanAndChunk` (chamado DEPOIS que a `BookVersion` já existe, ao
     * contrário do catch de `pageCount`) sem precisar de um segundo contexto Spring/Testcontainers só
     * para este caso — o switch é resetado a cada teste (ver [resetExtractRangeFailureSwitch]) para
     * não vazar para os demais testes deste arquivo, que precisam do comportamento real de extração.
     */
    @TestConfiguration
    class InstrumentedPdfTextExtractorConfig {
        @Bean
        fun extractRangeFailureSwitch(): ExtractRangeFailureSwitch = ExtractRangeFailureSwitch()

        @Bean
        @Primary
        fun instrumentedPdfTextExtractor(switch: ExtractRangeFailureSwitch): PdfTextExtractor {
            val spy = Mockito.spy(PdfTextExtractor())
            Mockito
                .doAnswer { invocation ->
                    if (switch.shouldFail) {
                        throw RuntimeException("falha simulada de extração")
                    }
                    invocation.callRealMethod()
                }.`when`(spy)
                // `any(File::class.java)` devolve null (API Java do Mockito); `extractRange` tem
                // `file: File` não anulável, e o Kotlin insere uma checagem de nulidade no início do
                // método que dispara antes do interceptor do Mockito registrar a invocação — por
                // isso o `?:` com um File dummy (nunca usado de fato; só evita o NPE na stubagem),
                // mesmo cuidado documentado em `IngestionServiceVolumeTest.VolumeTestConfig`.
                .extractRange(any(File::class.java) ?: File(""), anyInt(), anyInt())
            return spy
        }
    }

    /** Ver KDoc de [InstrumentedPdfTextExtractorConfig]. */
    class ExtractRangeFailureSwitch {
        @Volatile
        var shouldFail: Boolean = false
    }

    /**
     * Substitui o `ChunkRepository` real por um decorator que delega tudo para o bean real do
     * Spring Data (injetado aqui como parâmetro, não instanciado manualmente — é uma interface sem
     * implementação própria no módulo, ao contrário de [PdfTextExtractor]), exceto `saveAll`, que
     * pode ser instruído (via [SaveAllFailureSwitch.shouldFail]) a lançar uma exceção genérica.
     * Delegação Kotlin (`by delegate`) em vez de spy do Mockito sobre o proxy dinâmico do Spring
     * Data — mais simples e sem o risco de deixar o `ThreadSafeMockingProgress` do Mockito num
     * estado de "unfinished stubbing" ao interceptar um método genérico (`saveAll`) num proxy já
     * gerenciado pelo Spring. Simula item Crítico do code review final da branch: uma falha
     * inesperada (ex.: `DataIntegrityViolationException`, queda de conexão) durante a persistência
     * de um lote de chunks dentro de `IngestionService.embedAndPersistInBatches` — distinto do
     * teste já existente de falha do `EmbeddingClient` (`falha no meio da reindexacao...`), que
     * simula erro na geração do embedding, não na persistência em si.
     */
    @TestConfiguration
    class InstrumentedChunkRepositoryConfig {
        @Bean
        fun saveAllFailureSwitch(): SaveAllFailureSwitch = SaveAllFailureSwitch()

        @Bean
        @Primary
        fun instrumentedChunkRepository(
            chunkRepository: ChunkRepository,
            switch: SaveAllFailureSwitch,
        ): ChunkRepository = InstrumentedChunkRepository(chunkRepository, switch)
    }

    /** Ver KDoc de [InstrumentedChunkRepositoryConfig]. */
    class InstrumentedChunkRepository(
        private val delegate: ChunkRepository,
        private val switch: SaveAllFailureSwitch,
    ) : ChunkRepository by delegate {
        override fun <S : Chunk> saveAll(entities: Iterable<S>): List<S> {
            if (switch.shouldFail) {
                throw RuntimeException("falha simulada de persistência de chunks")
            }
            return delegate.saveAll(entities)
        }
    }

    /** Ver KDoc de [InstrumentedChunkRepositoryConfig]. */
    class SaveAllFailureSwitch {
        @Volatile
        var shouldFail: Boolean = false
    }

    /**
     * Fake de [EmbeddingClient] que grava, para cada chamada, quantos textos vieram e se havia uma
     * transação de banco ativa no momento da chamada — é a checagem central de T7 (nenhuma
     * transação de banco pode ficar aberta durante a chamada de rede à Voyage, ver `plan.md`).
     */
    class FakeEmbeddingClient : EmbeddingClient {
        val calls = mutableListOf<List<String>>()
        val transactionActiveDuringCall = mutableListOf<Boolean>()

        /**
         * Quando não nulo, a N-ésima chamada a [embed] (1-indexado, contada a partir do último
         * [org.junit.jupiter.api.BeforeEach]/reset de [calls]) lança [EmbeddingClientException] em
         * vez de devolver vetores — usado pelo teste de falha no meio da reindexação (T9,
         * CA5/CA7) para simular um lote que falha depois que outros lotes da mesma versão nova já
         * foram persistidos. `null` (padrão) preserva o comportamento determinístico sem falhas
         * usado pelos demais testes (T7/T8).
         */
        var failOnCallNumber: Int? = null

        override fun embed(texts: List<String>): List<FloatArray> {
            calls += texts
            transactionActiveDuringCall += TransactionSynchronizationManager.isActualTransactionActive()
            if (failOnCallNumber == calls.size) {
                throw EmbeddingClientException("falha simulada de embedding na chamada ${calls.size}")
            }
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

    @Autowired
    lateinit var extractRangeFailureSwitch: ExtractRangeFailureSwitch

    @Autowired
    lateinit var saveAllFailureSwitch: SaveAllFailureSwitch

    @TempDir
    lateinit var tempDir: Path

    /**
     * [FakeEmbeddingClient] e [ExtractRangeFailureSwitch] são beans singleton reaproveitados entre
     * métodos de teste (o contexto Spring é cacheado pelo Testcontainers/`@SpringBootTest`) — sem
     * isso, o estado de uma chamada de teste vazaria para a próxima e quebraria asserções como "zero
     * chamadas novas" (T8), "N chamadas neste teste" (T7) ou o comportamento real de extração
     * esperado pelos demais testes (que não devem herdar `shouldFail = true` de um teste anterior).
     */
    @BeforeEach
    fun resetFakeEmbeddingClient() {
        fakeEmbeddingClient.calls.clear()
        fakeEmbeddingClient.transactionActiveDuringCall.clear()
        fakeEmbeddingClient.failOnCallNumber = null
        extractRangeFailureSwitch.shouldFail = false
        saveAllFailureSwitch.shouldFail = false
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
     * CA7 (`spec.md`): um arquivo que não é um PDF válido (bytes aleatórios) falha em
     * `PdfTextExtractor.pageCount` — ANTES de existir uma `BookVersion` (ver KDoc de
     * `IngestionService.ingest`). A ingestão precisa devolver `IngestionOutcome.Failed` com uma
     * mensagem específica, nunca deixar a exceção crua do PDFBox subir ao chamador.
     */
    @Test
    fun `PDF corrompido falha a ingestao com mensagem clara, sem BookVersion orfa e sem chamar o EmbeddingClient`() {
        val file = tempDir.resolve("corrompido.pdf").toFile()
        Files.write(file.toPath(), byteArrayOf(1, 2, 3))

        val outcome = ingestionService.ingest(bookId = "livro-corrompido-t7", title = "Livro Corrompido", file = file)

        val failed = outcome as? IngestionOutcome.Failed
        assertNotNull(failed, "esperava IngestionOutcome.Failed, obteve: $outcome")
        checkNotNull(failed)
        assertEquals("livro-corrompido-t7", failed.bookId)
        assertTrue(
            failed.reason.contains("PDF", ignoreCase = true),
            "mensagem deveria mencionar o problema de leitura do PDF, não um stack trace: ${failed.reason}",
        )
        assertFalse(
            failed.reason.contains("\tat "),
            "mensagem não deveria conter um stack trace cru: ${failed.reason}",
        )

        // Falha ANTES de existir uma BookVersion (pageCount nem chegou a rodar) — nenhuma versão
        // órfã em INGESTING.
        assertEquals(null, failed.versionId)
        assertTrue(
            bookVersionRepository.findAll().none { it.bookId == "livro-corrompido-t7" },
            "não deveria existir nenhuma BookVersion para um PDF que falhou antes de pageCount",
        )

        assertTrue(fakeEmbeddingClient.calls.isEmpty(), "não deveria chamar o EmbeddingClient para um PDF corrompido")
    }

    /**
     * CA7 (`spec.md`), segundo caminho de falha do catch genérico adicionado a
     * `extractCleanAndChunk`: ao contrário do teste de PDF corrompido acima (falha ANTES de existir
     * uma `BookVersion`, em `pageCount`), aqui a falha simulada acontece DEPOIS — `pageCount` roda
     * normal (via [ExtractRangeFailureSwitch], só `extractRange` é instruído a lançar) e a
     * `BookVersion` já existe em `INGESTING` quando o erro ocorre. A ingestão precisa marcar essa
     * versão como `FAILED` (nunca deixá-la presa em `INGESTING`) e nunca chegar a chamar o
     * `EmbeddingClient`.
     */
    @Test
    fun `falha inesperada dentro de extractCleanAndChunk marca a BookVersion existente como FAILED`() {
        val file = PdfFixtures.textPdf(tempDir, fixtureBookPageTexts())
        extractRangeFailureSwitch.shouldFail = true

        val outcome =
            ingestionService.ingest(bookId = "livro-falha-extracao-t7", title = "Livro Falha Extração", file = file)

        val failed = outcome as? IngestionOutcome.Failed
        assertNotNull(failed, "esperava IngestionOutcome.Failed, obteve: $outcome")
        checkNotNull(failed)
        assertNotNull(
            failed.versionId,
            "esperava um versionId não nulo — a BookVersion já existia quando a falha ocorreu",
        )

        val version = bookVersionRepository.findById(checkNotNull(failed.versionId)).orElseThrow()
        assertEquals(BookVersionStatus.FAILED, version.status)

        assertTrue(
            fakeEmbeddingClient.calls.isEmpty(),
            "a falha ocorreu antes do embedding, não deveria haver chamadas ao EmbeddingClient",
        )
    }

    /**
     * Item Crítico do code review final da branch (CA7, `spec.md`): antes deste fix, o catch em
     * volta de `embedAndPersistInBatches` só cobria `EmbeddingClientException` — qualquer outra
     * falha durante a persistência do lote (`chunkRepository.saveAll`, ex.:
     * `DataIntegrityViolationException`, queda/timeout de conexão do Postgres) escapava crua,
     * deixando a `BookVersion` presa em `INGESTING` para sempre. Aqui a falha é simulada via
     * [InstrumentedChunkRepositoryConfig] dentro da própria persistência, não no `EmbeddingClient`
     * (esse caminho já é coberto por `falha no meio da reindexacao...`).
     */
    @Test
    fun `falha inesperada dentro da persistencia de chunks marca a BookVersion como FAILED`() {
        val file = PdfFixtures.textPdf(tempDir, fixtureBookPageTexts())
        saveAllFailureSwitch.shouldFail = true

        val outcome =
            ingestionService.ingest(bookId = "livro-falha-persistencia-t7", title = "Livro Falha Persistência", file = file)

        val failed = outcome as? IngestionOutcome.Failed
        assertNotNull(failed, "esperava IngestionOutcome.Failed, obteve: $outcome")
        checkNotNull(failed)
        assertNotNull(
            failed.versionId,
            "esperava um versionId não nulo — a BookVersion já existia quando a falha ocorreu",
        )

        val version = bookVersionRepository.findById(checkNotNull(failed.versionId)).orElseThrow()
        assertEquals(BookVersionStatus.FAILED, version.status, "a BookVersion não deveria ficar presa em INGESTING")
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

    /**
     * T9, caminho feliz: `--reindex` com um PDF diferente para o mesmo `bookId` faz o swap atômico
     * — a nova versão vira ativa, e a versão antiga (+ seus chunks) deixa de existir no banco
     * (ADR-0008, "Reindexação atômica").
     */
    @Test
    fun `reindex com sucesso troca a versao ativa e remove a versao e chunks antigos`() {
        val firstFile = PdfFixtures.textPdf(tempDir, fixtureBookPageTexts())
        val first = ingestionService.ingest(bookId = "livro-swap-t9", title = "Livro Swap T9", file = firstFile)
        val completedFirst = first as? IngestionOutcome.Completed
        assertNotNull(completedFirst, "esperava IngestionOutcome.Completed na primeira ingestão, obteve: $first")
        checkNotNull(completedFirst)
        val oldVersionId = completedFirst.versionId

        // Conteúdo diferente (mesmo esquema do teste de bloqueio T8) => hash diferente => reindex necessário.
        val differentPageTexts =
            fixtureBookPageTexts().map { pageText -> "$pageText wextra1 wextra2 wextra3" }
        val secondFile = PdfFixtures.textPdf(tempDir, differentPageTexts)

        val second =
            ingestionService.ingest(
                bookId = "livro-swap-t9",
                title = "Livro Swap T9",
                file = secondFile,
                reindex = true,
            )

        val completedSecond = second as? IngestionOutcome.Completed
        assertNotNull(completedSecond, "esperava IngestionOutcome.Completed na reindexação, obteve: $second")
        checkNotNull(completedSecond)

        val book = bookRepository.findById("livro-swap-t9").orElseThrow()
        assertEquals(
            completedSecond.versionId,
            book.activeVersionId,
            "apos o swap, a versao ativa deveria ser a nova versao",
        )

        assertTrue(
            bookVersionRepository.findById(oldVersionId).isEmpty,
            "a versao antiga deveria ter sido removida pelo swap atomico",
        )
        val oldChunksRemaining = chunkRepository.findAll().count { it.bookVersionId == oldVersionId }
        assertEquals(0, oldChunksRemaining, "os chunks da versao antiga deveriam ter sido removidos")

        val newVersion = bookVersionRepository.findById(completedSecond.versionId).orElseThrow()
        assertEquals(BookVersionStatus.READY, newVersion.status)
        val newChunks = chunkRepository.findAll().filter { it.bookVersionId == completedSecond.versionId }
        assertEquals(completedSecond.chunkCount, newChunks.size)
    }

    /**
     * T9, caminho de falha (teste central da task — CA5/CA7, `spec.md`): o `EmbeddingClient` fake
     * falha no meio dos lotes da NOVA versão (na 2ª chamada, não na 1ª, para garantir que parte dos
     * lotes da versão nova já foi persistida antes da falha). A versão ativa (e todos os seus
     * chunks) precisa continuar sendo a antiga — o swap nunca deve acontecer parcialmente.
     */
    @Test
    fun `falha no meio da reindexacao mantem a versao antiga ativa e intacta`() {
        val firstFile = PdfFixtures.textPdf(tempDir, fixtureBookPageTexts())
        val first =
            ingestionService.ingest(bookId = "livro-falha-reindex-t9", title = "Livro Falha Reindex T9", file = firstFile)
        val completedFirst = first as? IngestionOutcome.Completed
        assertNotNull(completedFirst, "esperava IngestionOutcome.Completed na primeira ingestão, obteve: $first")
        checkNotNull(completedFirst)
        val oldVersionId = completedFirst.versionId
        val oldChunkCount = completedFirst.chunkCount

        // Reseta a contagem de chamadas para que "2ª chamada" se refira à reindexação a seguir,
        // não à primeira ingestão já concluída acima.
        fakeEmbeddingClient.calls.clear()
        fakeEmbeddingClient.transactionActiveDuringCall.clear()
        fakeEmbeddingClient.failOnCallNumber = 2

        val differentPageTexts =
            fixtureBookPageTexts().map { pageText -> "$pageText wextra1 wextra2 wextra3" }
        val secondFile = PdfFixtures.textPdf(tempDir, differentPageTexts)

        val second =
            ingestionService.ingest(
                bookId = "livro-falha-reindex-t9",
                title = "Livro Falha Reindex T9",
                file = secondFile,
                reindex = true,
            )

        val failed = second as? IngestionOutcome.Failed
        assertNotNull(failed, "esperava IngestionOutcome.Failed, obteve: $second")
        checkNotNull(failed)
        assertTrue(
            fakeEmbeddingClient.calls.size >= 2,
            "esperava pelo menos 2 chamadas ao EmbeddingClient antes da falha simulada, teve ${fakeEmbeddingClient.calls.size}",
        )

        val book = bookRepository.findById("livro-falha-reindex-t9").orElseThrow()
        assertEquals(
            oldVersionId,
            book.activeVersionId,
            "versao ativa nao deveria mudar quando a reindexacao falha no meio (CA5, CA7)",
        )

        val oldVersion = bookVersionRepository.findById(oldVersionId).orElseThrow()
        assertEquals(BookVersionStatus.READY, oldVersion.status, "versao antiga deveria continuar READY")
        val oldChunks = chunkRepository.findAll().filter { it.bookVersionId == oldVersionId }
        assertEquals(oldChunkCount, oldChunks.size, "chunks da versao antiga deveriam continuar intactos")

        val failedVersion = bookVersionRepository.findById(checkNotNull(failed.versionId)).orElseThrow()
        assertEquals(BookVersionStatus.FAILED, failedVersion.status, "a nova versao deveria ter ficado FAILED")
    }
}
