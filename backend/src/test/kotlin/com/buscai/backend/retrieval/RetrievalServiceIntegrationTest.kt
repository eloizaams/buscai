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
import com.buscai.backend.ingestion.chunking.ReferenceType
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
        tokenCount: Int = 10,
        embedding: FloatArray = oneHotEmbedding(0),
        reference: String? = null,
        referenceType: ReferenceType? = null,
    ): Chunk =
        chunkRepository.save(
            Chunk(
                id = UUID.randomUUID(),
                bookVersionId = bookVersionId,
                page = page,
                charOffset = 0,
                tokenCount = tokenCount,
                text = text,
                embedding = embedding,
                reference = reference,
                referenceType = referenceType,
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
    fun `AllBooks exclui livro com embeddingModelVersion divergente, mas Books daquele bookId ainda encontra`() {
        val suffix = UUID.randomUUID()
        val livroModeloAtual = persistBook("livro-modelo-atual-$suffix", "Livro Modelo Atual")
        val versaoModeloAtual =
            persistVersion(livroModeloAtual.id, BookVersionStatus.READY, embeddingModel = "voyage-3", embeddingModelVersion = "v1")
        activate(livroModeloAtual, versaoModeloAtual.id)
        persistChunk(versaoModeloAtual.id, "Trecho do livro com o modelo de embedding atual.")

        // embeddingModelVersion diferente do VoyageProperties atual ("v1", ver application.yml) —
        // simula um livro reindexado com modelo novo enquanto outro ainda não foi (plan.md).
        val livroModeloDivergente = persistBook("livro-modelo-divergente-$suffix", "Livro Modelo Divergente")
        val versaoModeloDivergente =
            persistVersion(
                livroModeloDivergente.id,
                BookVersionStatus.READY,
                embeddingModel = "voyage-3",
                embeddingModelVersion = "v2",
            )
        activate(livroModeloDivergente, versaoModeloDivergente.id)
        persistChunk(versaoModeloDivergente.id, "Trecho do livro com modelo de embedding divergente.")

        val resultAllBooks = retrievalService.search("trecho", RetrievalScope.AllBooks)
        require(resultAllBooks is RetrievalResult.Found)
        val bookIdsEncontrados = resultAllBooks.chunks.map { it.bookId }.toSet()
        assertTrue(bookIdsEncontrados.contains(livroModeloAtual.id), "livro com modelo atual deveria aparecer: $bookIdsEncontrados")
        assertTrue(
            !bookIdsEncontrados.contains(livroModeloDivergente.id),
            "livro com embeddingModelVersion divergente não deveria aparecer em AllBooks: $bookIdsEncontrados",
        )

        // O filtro de versão de embedding só se aplica a AllBooks (evita misturar espaços
        // vetoriais incompatíveis na busca sobre o acervo inteiro) — Books(bookId) continua
        // encontrando o mesmo livro normalmente, já que o chamador pediu por ele explicitamente.
        val resultBooks = retrievalService.search("trecho", RetrievalScope.Books(setOf(livroModeloDivergente.id)))
        require(resultBooks is RetrievalResult.Found)
        assertTrue(resultBooks.chunks.isNotEmpty(), "Books(bookId) deveria encontrar o livro mesmo com modelo divergente")
        assertTrue(resultBooks.chunks.all { it.bookId == livroModeloDivergente.id })
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

    @Test
    fun `candidato sem match lexico e com cosineSimilarity abaixo do limiar produz NoRelevantContext (CA7)`() {
        val suffix = UUID.randomUUID()
        val livro = persistBook("livro-baixa-similaridade-$suffix", "Livro Baixa Similaridade")
        val versao = persistVersion(livro.id, BookVersionStatus.READY)
        activate(livro, versao.id)
        // Nenhum match léxico do termo da query ("excêntrico" não aparece no texto do chunk) e
        // embedding ortogonal ao vetor "one-hot" que FakeQueryEmbeddingClient devolve para a query
        // (oneHotEmbedding(0)) — cosine_similarity real entre os dois vetores one-hot ortogonais é
        // 0.0 (bem abaixo do limiar default de 0.5, plan.md "Config nova") e matchedLexicalBranch
        // é false (chunk não entra na CTE lexical_rank): nenhum dos dois sinais de relevância do
        // gate de CA7 é satisfeito.
        //
        // Nota (2026-07-17): antes deste teste usava um chunk com match léxico exato do termo da
        // query — esse era exatamente o cenário do bug corrigido em RetrievalService.search (ver
        // KDoc da classe e HybridSearchRow.matchedLexicalBranch): um match léxico puro (CA3) era
        // incorretamente tratado como "sem contexto relevante" por só olhar cosineSimilarity. O
        // cenário mudou para continuar testando genuinamente "nenhum sinal de relevância", não mais
        // o comportamento (agora incorreto) de descartar um match léxico exato.
        persistChunk(versao.id, "Um personagem qualquer aparece no capítulo final.", embedding = oneHotEmbedding(7))

        val result = retrievalService.search("excêntrico", RetrievalScope.Books(setOf(livro.id)))

        assertEquals(RetrievalResult.NoRelevantContext, result)
    }

    @Test
    fun `chunk com match lexico exato mas embedding distante produz Found, nao NoRelevantContext (CA3 + CA7, bug fix 2026-07-17)`() {
        val suffix = UUID.randomUUID()
        val livro = persistBook("livro-lexico-puro-$suffix", "Livro Léxico Puro")
        val versao = persistVersion(livro.id, BookVersionStatus.READY)
        activate(livro, versao.id)
        // Único chunk do livro: contém o termo exato buscado ("excêntrico"), mas embedding
        // ortogonal ao vetor one-hot(0) que FakeQueryEmbeddingClient devolve para a query —
        // cosineSimilarity real é 0.0, abaixo do limiar default de 0.5. Reproduz o cenário exato do
        // bug de CA7 corrigido em 2026-07-17: um match léxico puro (CA3, "termos exatos e nomes
        // próprios que a busca puramente semântica não colocaria no topo", spec.md) não pode ser
        // descartado como "sem contexto relevante" só por não ter aparecido no ramo vetorial.
        val chunkComTermo =
            persistChunk(versao.id, "Um personagem excêntrico aparece no capítulo final.", embedding = oneHotEmbedding(7))

        val result = retrievalService.search("excêntrico", RetrievalScope.Books(setOf(livro.id)))

        require(result is RetrievalResult.Found)
        assertTrue(
            result.chunks.any { it.chunkId == chunkComTermo.id },
            "esperava encontrar o chunk com match léxico exato: ${result.chunks}",
        )
    }

    @Test
    fun `ao menos um candidato acima do limiar produz Found com os candidatos esperados (CA7)`() {
        val suffix = UUID.randomUUID()
        val livro = persistBook("livro-alta-similaridade-$suffix", "Livro Alta Similaridade")
        val versao = persistVersion(livro.id, BookVersionStatus.READY)
        activate(livro, versao.id)
        // Mesmo vetor one-hot da query (FakeQueryEmbeddingClient) -> cosine_similarity = 1.0,
        // acima do limiar; o segundo chunk fica abaixo do limiar e não deveria impedir o Found.
        val chunkRelevante = persistChunk(versao.id, "Trecho relevante sobre excêntrico.", embedding = oneHotEmbedding(0))
        persistChunk(versao.id, "Outro trecho sobre excêntrico, porém com embedding distante.", page = 2, embedding = oneHotEmbedding(9))

        val result = retrievalService.search("excêntrico", RetrievalScope.Books(setOf(livro.id)))

        require(result is RetrievalResult.Found)
        assertTrue(result.chunks.isNotEmpty(), "esperava ao menos um chunk no resultado")
        assertTrue(
            result.chunks.any { it.chunkId == chunkRelevante.id },
            "esperava encontrar o chunk de alta similaridade: ${result.chunks}",
        )
    }

    @Test
    fun `lista de candidatos esvaziada pelo orcamento de tokens do ContextAssembler produz NoRelevantContext (CA7)`() {
        val suffix = UUID.randomUUID()
        val livro = persistBook("livro-estoura-orcamento-$suffix", "Livro Estoura Orçamento")
        val versao = persistVersion(livro.id, BookVersionStatus.READY)
        activate(livro, versao.id)
        // tokenCount sozinho já ultrapassa DEFAULT_TOKEN_BUDGET (3000, RetrievalService) — o
        // ContextAssembler descarta esse único candidato no corte de orçamento (CA5), reduzindo
        // assembledRows a uma lista vazia mesmo com o DAO tendo encontrado o chunk (cosine = 1.0,
        // acima do limiar — o ramo exercitado aqui é o de lista vazia pós-orçamento, não o de
        // baixa similaridade).
        persistChunk(versao.id, "Trecho sobre orçamento que jamais caberá no budget de tokens.", tokenCount = 3001)

        val result = retrievalService.search("orçamento", RetrievalScope.Books(setOf(livro.id)))

        assertEquals(RetrievalResult.NoRelevantContext, result)
    }

    @Test
    fun `RetrievedChunk final carrega reference e referenceType persistidos no chunk (ADR-0013)`() {
        val suffix = UUID.randomUUID()
        val livro = persistBook("livro-dos-espiritos-$suffix", "O Livro dos Espíritos")
        val versao = persistVersion(livro.id, BookVersionStatus.READY)
        activate(livro, versao.id)
        val chunkItemNumerado =
            persistChunk(
                versao.id,
                "Que se atribui à alma humana desígnio depois da morte.",
                reference = "157",
                referenceType = ReferenceType.NUMBERED_ITEM,
            )

        val result = retrievalService.search("morte", RetrievalScope.Books(setOf(livro.id)))

        require(result is RetrievalResult.Found)
        val chunk = result.chunks.first { it.chunkId == chunkItemNumerado.id }
        assertEquals("157", chunk.reference)
        assertEquals(ReferenceType.NUMBERED_ITEM, chunk.referenceType)
    }
}
