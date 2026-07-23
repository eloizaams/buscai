package com.buscai.backend.retrieval

import com.buscai.backend.catalog.Book
import com.buscai.backend.catalog.BookRepository
import com.buscai.backend.catalog.BookVersion
import com.buscai.backend.catalog.BookVersionRepository
import com.buscai.backend.catalog.BookVersionStatus
import com.buscai.backend.embedding.EmbeddingClient
import com.buscai.backend.embedding.EmbeddingInputType
import com.buscai.backend.embedding.VoyageProperties
import com.buscai.backend.ingestion.chunking.ReferenceType
import com.buscai.backend.retrieval.context.ContextAssembler
import com.buscai.backend.retrieval.search.HybridSearchDao
import com.buscai.backend.retrieval.search.HybridSearchRow
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.mock
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Teste unitário de orquestração de [RetrievalService] (`specs/busca-exata-item/tasks.md`, T5), com
 * todas as dependências mockadas (Mockito) — sem Spring context nem Testcontainers, mesmo padrão de
 * [com.buscai.backend.catalog.BookServiceTest]. Complementa
 * [RetrievalServiceIntegrationTest] (Postgres real, foco em resolução de escopo/CA2/CA6): aqui o
 * foco é só a fiação nova do `ItemLookupDetector` (RF1) para o [HybridSearchDao] e a terceira
 * cláusula do filtro de relevância CA7 (`matchedExactBranch`, risco crítico R1 do
 * `specs/busca-exata-item/plan.md`).
 */
class RetrievalServiceTest {
    private val bookRepository = mock(BookRepository::class.java)
    private val bookVersionRepository = mock(BookVersionRepository::class.java)
    private val embeddingClient = mock(EmbeddingClient::class.java)
    private val hybridSearchDao = mock(HybridSearchDao::class.java)
    private val contextAssembler = mock(ContextAssembler::class.java)
    private val itemLookupDetector = mock(ItemLookupDetector::class.java)
    private val voyageProperties = VoyageProperties(model = "voyage-3", modelVersion = "v1")
    private val retrievalProperties = RetrievalProperties()

    private val retrievalService =
        RetrievalService(
            bookRepository = bookRepository,
            bookVersionRepository = bookVersionRepository,
            embeddingClient = embeddingClient,
            voyageProperties = voyageProperties,
            hybridSearchDao = hybridSearchDao,
            contextAssembler = contextAssembler,
            retrievalProperties = retrievalProperties,
            itemLookupDetector = itemLookupDetector,
        )

    /** Livro com uma única versão `READY` elegível, mesmo modelo/versão de embedding de [voyageProperties]. */
    private fun setupEligibleBook(bookId: String = "livro-${UUID.randomUUID()}"): Pair<Book, BookVersion> {
        val versionId = UUID.randomUUID()
        val book = Book(id = bookId, title = "Livro de teste", activeVersionId = versionId)
        val version =
            BookVersion(
                id = versionId,
                bookId = bookId,
                fileHash = "hash".padEnd(64, '0'),
                embeddingModel = "voyage-3",
                embeddingModelVersion = "v1",
                status = BookVersionStatus.READY,
            )
        Mockito.`when`(bookRepository.findAllById(setOf(bookId))).thenReturn(listOf(book))
        Mockito.`when`(bookVersionRepository.findAllById(setOf(versionId))).thenReturn(listOf(version))
        return book to version
    }

    // Nota (mesmo cuidado documentado em IngestionServiceTest.InstrumentedPdfTextExtractorConfig):
    // matchers do Mockito (API Java) que devolvem objeto (any/anyString/anyList/eq) devolvem `null`
    // por baixo dos panos; como os parâmetros correspondentes aqui são tipos Kotlin não anuláveis, o
    // compilador insere uma checagem de nulidade na expressão do argumento que dispara antes do
    // matcher ser de fato registrado — daí o `?: <valor dummy>`, nunca usado de fato (só evita o NPE
    // na stubagem/verificação).
    private fun stubEmbedding() {
        Mockito
            .`when`(embeddingClient.embed(anyList<String>() ?: emptyList(), eq(EmbeddingInputType.QUERY) ?: EmbeddingInputType.QUERY))
            .thenReturn(listOf(FloatArray(4)))
    }

    private fun stubHybridSearch(rows: List<HybridSearchRow>) {
        Mockito
            .`when`(
                hybridSearchDao.search(
                    any(FloatArray::class.java) ?: FloatArray(0),
                    anyString() ?: "",
                    anyList<UUID>() ?: emptyList(),
                    anyInt(),
                    anyInt(),
                    anyInt(),
                    anyList<Int>() ?: emptyList(),
                    anyInt(),
                ),
            ).thenReturn(rows)
    }

    private fun stubContextAssembler(rows: List<HybridSearchRow>) {
        Mockito
            .`when`(contextAssembler.assemble(anyList<HybridSearchRow>() ?: emptyList(), anyInt(), anyInt()))
            .thenReturn(rows)
    }

    @Test
    fun `lookup detectado injeta os numeros no HybridSearchDao`() {
        val (book, _) = setupEligibleBook()
        Mockito.`when`(itemLookupDetector.detect("qual a pergunta 25?")).thenReturn(listOf(25))
        stubEmbedding()
        stubHybridSearch(emptyList())
        stubContextAssembler(emptyList())

        retrievalService.search("qual a pergunta 25?", RetrievalScope.Books(setOf(book.id)))

        @Suppress("UNCHECKED_CAST")
        val exactItemNumbersCaptor = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<Int>>
        Mockito
            .verify(hybridSearchDao)
            .search(
                any(FloatArray::class.java) ?: FloatArray(0),
                anyString() ?: "",
                anyList<UUID>() ?: emptyList(),
                anyInt(),
                anyInt(),
                anyInt(),
                exactItemNumbersCaptor.capture() ?: emptyList(),
                anyInt(),
            )
        assertEquals(listOf(25), exactItemNumbersCaptor.value, "esperava que o lookup detectado fosse repassado ao HybridSearchDao")
    }

    @Test
    fun `chunk exact-only sobrevive ao filtro CA7 e produz Found (risco critico R1)`() {
        val (book, version) = setupEligibleBook()
        Mockito.`when`(itemLookupDetector.detect(anyString() ?: "")).thenReturn(listOf(25))
        stubEmbedding()
        val chunkExactOnly =
            HybridSearchRow(
                chunkId = UUID.randomUUID(),
                bookVersionId = version.id,
                page = 1,
                charOffset = 0,
                reference = "25",
                referenceType = ReferenceType.NUMBERED_ITEM,
                text = "Texto do item 25.",
                tokenCount = 10,
                // Sem sinal vetorial nem léxico — só o ramo exato encontrou este chunk.
                cosineSimilarity = 0.0,
                rrfScore = 1.0,
                matchedLexicalBranch = false,
                matchedExactBranch = true,
            )
        stubHybridSearch(listOf(chunkExactOnly))
        stubContextAssembler(listOf(chunkExactOnly))

        val result = retrievalService.search("qual a pergunta 25?", RetrievalScope.Books(setOf(book.id)))

        require(result is RetrievalResult.Found) { "esperava Found via matchedExactBranch, obteve $result" }
        assertTrue(
            result.chunks.any { it.chunkId == chunkExactOnly.chunkId },
            "chunk exact-only (cosineSimilarity=0.0, matchedLexicalBranch=false) deveria sobreviver ao filtro CA7: ${result.chunks}",
        )
    }

    @Test
    fun `pergunta sem marcador de item nao altera a chamada ao HybridSearchDao`() {
        val (book, _) = setupEligibleBook()
        Mockito.`when`(itemLookupDetector.detect("o que aconteceu em 1857?")).thenReturn(emptyList())
        stubEmbedding()
        stubHybridSearch(emptyList())
        stubContextAssembler(emptyList())

        retrievalService.search("o que aconteceu em 1857?", RetrievalScope.Books(setOf(book.id)))

        @Suppress("UNCHECKED_CAST")
        val exactItemNumbersCaptor = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<Int>>
        Mockito
            .verify(hybridSearchDao)
            .search(
                any(FloatArray::class.java) ?: FloatArray(0),
                anyString() ?: "",
                anyList<UUID>() ?: emptyList(),
                anyInt(),
                anyInt(),
                anyInt(),
                exactItemNumbersCaptor.capture() ?: emptyList(),
                anyInt(),
            )
        assertEquals(
            emptyList(),
            exactItemNumbersCaptor.value,
            "pergunta sem marcador de item deveria chamar o DAO com exactItemNumbers vazio (comportamento pré-T5)",
        )
    }
}
