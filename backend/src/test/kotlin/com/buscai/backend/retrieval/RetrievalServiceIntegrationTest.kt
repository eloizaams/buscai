package com.buscai.backend.retrieval

import com.buscai.backend.catalog.Book
import com.buscai.backend.catalog.BookRepository
import com.buscai.backend.catalog.BookVersion
import com.buscai.backend.catalog.BookVersionRepository
import com.buscai.backend.catalog.BookVersionStatus
import com.buscai.backend.catalog.Chunk
import com.buscai.backend.catalog.ChunkRepository
import com.buscai.backend.catalog.EMBEDDING_DIMENSIONS
import com.buscai.backend.embedding.EmbeddingClient
import com.buscai.backend.embedding.EmbeddingInputType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Testes de ponta a ponta de [RetrievalService] (`specs/retrieval/tasks.md`, T4) contra um
 * Postgres real via Testcontainers — mesmo padrão de `HybridSearchDaoIntegrationTest`/
 * `IngestionServiceTest`, já que a resolução de escopo depende de `BookVersionStatus`/
 * `activeVersionId` reais e a busca híbrida usa pgvector/tsvector (não suportado pelo H2 do
 * `contextLoads`).
 *
 * [FakeQueryEmbeddingClient] substitui a Voyage real (sem chamada de rede) por um vetor
 * determinístico "one-hot", mesma convenção de `HybridSearchDaoIntegrationTest`: todo chunk de
 * fixture usa `oneHotEmbedding(0)`, então o ramo vetorial nunca é o fator decisivo destes testes —
 * o que importa aqui é a resolução de escopo (CA2/CA6) e a resolução de `bookId`/`bookTitle`, não a
 * qualidade do ranking (já coberta por `HybridSearchDaoIntegrationTest`, T3).
 */
@Testcontainers
@ActiveProfiles("testcontainers")
@SpringBootTest
class RetrievalServiceIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16"))

        @DynamicPropertySource
        @JvmStatic
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }

        private fun oneHotEmbedding(hotIndex: Int): FloatArray =
            FloatArray(EMBEDDING_DIMENSIONS) { index -> if (index == hotIndex) 1f else 0f }
    }

    /**
     * Substitui o `EmbeddingClient` real (`VoyageEmbeddingClient`) por um fake determinístico sem
     * rede, mesma convenção de `IngestionServiceTest.FakeEmbeddingClientConfig` — aqui simulando a
     * embeddagem da *query* (`EmbeddingInputType.QUERY`), não do conteúdo indexado.
     */
    @TestConfiguration
    class FakeQueryEmbeddingClientConfig {
        @Bean
        @Primary
        fun fakeQueryEmbeddingClient(): FakeQueryEmbeddingClient = FakeQueryEmbeddingClient()
    }

    /** Ver KDoc de [FakeQueryEmbeddingClientConfig]. */
    class FakeQueryEmbeddingClient : EmbeddingClient {
        val receivedInputTypes = mutableListOf<EmbeddingInputType>()

        override fun embed(
            texts: List<String>,
            inputType: EmbeddingInputType,
        ): List<FloatArray> {
            receivedInputTypes += inputType
            return texts.map { oneHotEmbedding(0) }
        }
    }

    @Autowired
    lateinit var retrievalService: RetrievalService

    @Autowired
    lateinit var bookRepository: BookRepository

    @Autowired
    lateinit var bookVersionRepository: BookVersionRepository

    @Autowired
    lateinit var chunkRepository: ChunkRepository

    @Autowired
    lateinit var fakeQueryEmbeddingClient: FakeQueryEmbeddingClient

    private fun persistBook(
        bookId: String,
        title: String,
    ): Book = bookRepository.save(Book(id = bookId, title = title))

    private fun persistVersion(
        bookId: String,
        status: BookVersionStatus,
        embeddingModel: String = "voyage-3",
        embeddingModelVersion: String = "v1",
    ): BookVersion =
        bookVersionRepository.save(
            BookVersion(
                id = UUID.randomUUID(),
                bookId = bookId,
                fileHash =
                    UUID
                        .randomUUID()
                        .toString()
                        .replace("-", "")
                        .repeat(2)
                        .take(64),
                embeddingModel = embeddingModel,
                embeddingModelVersion = embeddingModelVersion,
                status = status,
            ),
        )

    private fun activate(
        book: Book,
        versionId: UUID,
    ): Book {
        book.activeVersionId = versionId
        return bookRepository.save(book)
    }

    private fun persistChunk(
        bookVersionId: UUID,
        text: String,
        page: Int = 1,
    ): Chunk =
        chunkRepository.save(
            Chunk(
                id = UUID.randomUUID(),
                bookVersionId = bookVersionId,
                page = page,
                charOffset = 0,
                tokenCount = 10,
                text = text,
                embedding = oneHotEmbedding(0),
            ),
        )

    @Test
    fun `Books com um unico bookId nunca traz chunk de outro livro (CA2)`() {
        val suffix = UUID.randomUUID()
        val livroA = persistBook("livro-a-$suffix", "Livro A")
        val versaoA = persistVersion(livroA.id, BookVersionStatus.READY)
        activate(livroA, versaoA.id)
        persistChunk(versaoA.id, "Texto exclusivo do livro A sobre protagonistas.")

        val livroB = persistBook("livro-b-$suffix", "Livro B")
        val versaoB = persistVersion(livroB.id, BookVersionStatus.READY)
        activate(livroB, versaoB.id)
        persistChunk(versaoB.id, "Texto exclusivo do livro B sobre protagonistas.")

        val result = retrievalService.search("protagonistas", RetrievalScope.Books(setOf(livroA.id)))

        require(result is RetrievalResult.Found)
        assertTrue(result.chunks.isNotEmpty(), "esperava encontrar ao menos um chunk do livro A")
        assertTrue(result.chunks.all { it.bookId == livroA.id }, "não deveria conter chunk de outro livro: ${result.chunks}")
    }

    @Test
    fun `Books com subconjunto de 2 de 3 livros so traz chunks dos livros selecionados`() {
        val suffix = UUID.randomUUID()
        val livro1 = persistBook("livro-1-$suffix", "Livro 1")
        val versao1 = persistVersion(livro1.id, BookVersionStatus.READY)
        activate(livro1, versao1.id)
        persistChunk(versao1.id, "Trecho do livro 1 sobre viagens.")

        val livro2 = persistBook("livro-2-$suffix", "Livro 2")
        val versao2 = persistVersion(livro2.id, BookVersionStatus.READY)
        activate(livro2, versao2.id)
        persistChunk(versao2.id, "Trecho do livro 2 sobre viagens.")

        val livro3 = persistBook("livro-3-$suffix", "Livro 3")
        val versao3 = persistVersion(livro3.id, BookVersionStatus.READY)
        activate(livro3, versao3.id)
        persistChunk(versao3.id, "Trecho do livro 3 sobre viagens.")

        val result = retrievalService.search("viagens", RetrievalScope.Books(setOf(livro1.id, livro2.id)))

        require(result is RetrievalResult.Found)
        val bookIdsEncontrados = result.chunks.map { it.bookId }.toSet()
        assertTrue(bookIdsEncontrados.contains(livro1.id), "livro 1 deveria aparecer: $bookIdsEncontrados")
        assertTrue(bookIdsEncontrados.contains(livro2.id), "livro 2 deveria aparecer: $bookIdsEncontrados")
        assertTrue(!bookIdsEncontrados.contains(livro3.id), "livro 3 não deveria aparecer (fora do subconjunto): $bookIdsEncontrados")
    }

    @Test
    fun `livro com versao mais recente INGESTING continua encontrado pela versao READY anterior (CA6)`() {
        val suffix = UUID.randomUUID()
        val livro = persistBook("livro-reindexando-$suffix", "Livro Reindexando")
        val versaoAntigaReady = persistVersion(livro.id, BookVersionStatus.READY)
        activate(livro, versaoAntigaReady.id)
        persistChunk(versaoAntigaReady.id, "Conteúdo estável da versão pronta anterior.")

        // Versão nova em ingestão, nunca ativada (ADR-0008 — swap atômico só troca o ponteiro
        // quando a nova versão chega a READY).
        val versaoNovaIngesting = persistVersion(livro.id, BookVersionStatus.INGESTING)
        persistChunk(versaoNovaIngesting.id, "Conteúdo da versão nova ainda incompleta.")

        val resultBooks = retrievalService.search("conteúdo", RetrievalScope.Books(setOf(livro.id)))
        require(resultBooks is RetrievalResult.Found)
        assertTrue(resultBooks.chunks.isNotEmpty())
        assertTrue(resultBooks.chunks.none { it.text.contains("incompleta") })

        val resultAllBooks = retrievalService.search("conteúdo", RetrievalScope.AllBooks)
        require(resultAllBooks is RetrievalResult.Found)
        assertTrue(resultAllBooks.chunks.any { it.text.contains("estável") })
        assertTrue(resultAllBooks.chunks.none { it.text.contains("incompleta") })
    }

    @Test
    fun `livro cuja unica versao e FAILED nunca aparece em AllBooks nem Books`() {
        val suffix = UUID.randomUUID()
        val livro = persistBook("livro-falho-$suffix", "Livro Falho")
        val versaoFailed = persistVersion(livro.id, BookVersionStatus.FAILED)
        persistChunk(versaoFailed.id, "Conteúdo de uma ingestão que falhou.")
        // Nunca teve activeVersionId apontado (nenhuma ingestão chegou a READY, ver ADR-0008).

        // Livro READY à parte, só para garantir que o conjunto elegível de AllBooks não fique
        // vazio nesta asserção por acaso (o que devolveria NoRelevantContext direto, sem exercitar
        // o filtro que este teste quer checar) — independe de outros testes já terem persistido
        // dados na mesma instância de Postgres do Testcontainers.
        val livroReady = persistBook("livro-controle-ready-$suffix", "Livro Controle Ready")
        val versaoReady = persistVersion(livroReady.id, BookVersionStatus.READY)
        activate(livroReady, versaoReady.id)
        persistChunk(versaoReady.id, "Conteúdo de controle de uma versão pronta.")

        val resultBooks = retrievalService.search("conteúdo", RetrievalScope.Books(setOf(livro.id)))
        assertEquals(RetrievalResult.NoRelevantContext, resultBooks)

        val resultAllBooks = retrievalService.search("conteúdo", RetrievalScope.AllBooks)
        require(resultAllBooks is RetrievalResult.Found)
        assertTrue(resultAllBooks.chunks.none { it.bookId == livro.id })
    }

    @Test
    fun `AllBooks com todos os livros na mesma versao de embedding nao filtra nada indevidamente`() {
        val suffix = UUID.randomUUID()
        val livroA = persistBook("livro-mesmo-modelo-a-$suffix", "Livro Mesmo Modelo A")
        val versaoA = persistVersion(livroA.id, BookVersionStatus.READY, embeddingModel = "voyage-3", embeddingModelVersion = "v1")
        activate(livroA, versaoA.id)
        persistChunk(versaoA.id, "Trecho do livro A, mesmo modelo de embedding.")

        val livroB = persistBook("livro-mesmo-modelo-b-$suffix", "Livro Mesmo Modelo B")
        val versaoB = persistVersion(livroB.id, BookVersionStatus.READY, embeddingModel = "voyage-3", embeddingModelVersion = "v1")
        activate(livroB, versaoB.id)
        persistChunk(versaoB.id, "Trecho do livro B, mesmo modelo de embedding.")

        val result = retrievalService.search("trecho", RetrievalScope.AllBooks)

        require(result is RetrievalResult.Found)
        val bookIdsEncontrados = result.chunks.map { it.bookId }.toSet()
        assertTrue(bookIdsEncontrados.contains(livroA.id), "livro A deveria aparecer: $bookIdsEncontrados")
        assertTrue(bookIdsEncontrados.contains(livroB.id), "livro B deveria aparecer: $bookIdsEncontrados")
    }

    @Test
    fun `Books de bookId inexistente devolve NoRelevantContext sem lancar excecao`() {
        val result = retrievalService.search("qualquer pergunta", RetrievalScope.Books(setOf("livro-que-nao-existe-${UUID.randomUUID()}")))

        assertEquals(RetrievalResult.NoRelevantContext, result)
    }

    @Test
    fun `bookTitle e resolvido corretamente em RetrievedChunk`() {
        val suffix = UUID.randomUUID()
        val livro = persistBook("dom-casmurro-$suffix", "Dom Casmurro")
        val versao = persistVersion(livro.id, BookVersionStatus.READY)
        activate(livro, versao.id)
        persistChunk(versao.id, "Bentinho e Capitu se conhecem quando crianças.")

        val result = retrievalService.search("Bentinho", RetrievalScope.Books(setOf(livro.id)))

        require(result is RetrievalResult.Found)
        assertTrue(result.chunks.isNotEmpty())
        assertTrue(result.chunks.all { it.bookTitle == "Dom Casmurro" }, "título esperado em todos os chunks: ${result.chunks}")
    }

    @Test
    fun `conjunto vazio de versoes elegiveis nao chama o EmbeddingClient`() {
        fakeQueryEmbeddingClient.receivedInputTypes.clear()

        val result = retrievalService.search("qualquer pergunta", RetrievalScope.Books(setOf("livro-inexistente-${UUID.randomUUID()}")))

        assertEquals(RetrievalResult.NoRelevantContext, result)
        assertTrue(fakeQueryEmbeddingClient.receivedInputTypes.isEmpty(), "não deveria chamar o EmbeddingClient sem versão elegível")
    }
}
