package com.buscai.backend.retrieval

import com.buscai.backend.catalog.Book
import com.buscai.backend.catalog.BookRepository
import com.buscai.backend.catalog.BookVersion
import com.buscai.backend.catalog.BookVersionRepository
import com.buscai.backend.catalog.BookVersionStatus
import com.buscai.backend.embedding.EmbeddingClient
import com.buscai.backend.embedding.EmbeddingInputType
import com.buscai.backend.embedding.VoyageProperties
import com.buscai.backend.retrieval.search.HybridSearchDao
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Valores literais de T3 (`vectorCandidates`/`lexicalCandidates`/`rrfK`) usados por
 * [RetrievalService] até T7 conectar `RetrievalProperties` (`buscai.retrieval.*`) — mesmos
 * defaults sugeridos em `specs/retrieval/plan.md`, seção "Config nova".
 */
private const val DEFAULT_VECTOR_CANDIDATES = 50
private const val DEFAULT_LEXICAL_CANDIDATES = 50
private const val DEFAULT_RRF_K = 60

/**
 * Único ponto de entrada da lógica de retrieval (`specs/retrieval/plan.md`, seção "Contratos entre
 * camadas"): resolve o escopo da busca em versões ativas buscáveis (CA2/CA6), embedda a pergunta
 * (`EmbeddingClient.embed(..., EmbeddingInputType.QUERY)`, ADR-0010) e delega a busca híbrida ao
 * [HybridSearchDao] (T3).
 *
 * Ainda não implementa (tasks futuras, ver `specs/retrieval/tasks.md`): dedup de vizinhos e
 * orçamento de tokens (`ContextAssembler`, T5) nem o sinal completo de "sem contexto relevante"
 * baseado em `cosineSimilarity` (CA7, T6) — aqui `NoRelevantContext` só cobre o caminho "nenhuma
 * versão elegível".
 */
@Service
class RetrievalService(
    private val bookRepository: BookRepository,
    private val bookVersionRepository: BookVersionRepository,
    private val embeddingClient: EmbeddingClient,
    private val voyageProperties: VoyageProperties,
    private val hybridSearchDao: HybridSearchDao,
) {
    fun search(
        query: String,
        scope: RetrievalScope,
    ): RetrievalResult {
        val eligibleVersions = resolveEligibleVersions(scope)
        if (eligibleVersions.isEmpty()) return RetrievalResult.NoRelevantContext

        val queryVector = embeddingClient.embed(listOf(query), EmbeddingInputType.QUERY).first()

        val rows =
            hybridSearchDao.search(
                queryVector = queryVector,
                queryText = query,
                eligibleBookVersionIds = eligibleVersions.keys.toList(),
                vectorCandidates = DEFAULT_VECTOR_CANDIDATES,
                lexicalCandidates = DEFAULT_LEXICAL_CANDIDATES,
                rrfK = DEFAULT_RRF_K,
            )

        val chunks =
            rows.map { row ->
                val book = eligibleVersions.getValue(row.bookVersionId)
                RetrievedChunk(
                    chunkId = row.chunkId,
                    bookId = book.id,
                    bookTitle = book.title,
                    page = row.page,
                    chapter = row.chapter,
                    text = row.text,
                    score = row.rrfScore,
                )
            }
        return RetrievalResult.Found(chunks)
    }

    /**
     * Resolve o conjunto de `BookVersion.id` elegíveis para [scope], já associado ao [Book] de
     * origem (evita uma query por linha de resultado ao converter `HybridSearchRow` em
     * [RetrievedChunk] — ver `specs/retrieval/tasks.md`, T4).
     *
     * `Books(bookIds)`: para cada `bookId` do conjunto, só o `activeVersionId` daquele `Book`, se
     * existir e a `BookVersion` correspondente estiver `READY` — um `bookId` inexistente ou sem
     * versão `READY` simplesmente não contribui (não é erro, CA6/CA7); os resultados de todos os
     * `bookId`s do conjunto são agregados no mesmo mapa.
     *
     * `AllBooks`: `activeVersionId` de todo `Book` com versão ativa, filtrado às que estão `READY`
     * **e** cujo `embeddingModel`/`embeddingModelVersion` batem com os configurados atualmente em
     * [VoyageProperties] — evita misturar espaços vetoriais incompatíveis (`plan.md`).
     */
    private fun resolveEligibleVersions(scope: RetrievalScope): Map<UUID, Book> =
        when (scope) {
            is RetrievalScope.Books -> {
                scope.bookIds
                    .mapNotNull { bookId ->
                        val book = bookRepository.findById(bookId).orElse(null)
                        val activeVersionId = book?.activeVersionId
                        val activeVersion = activeVersionId?.let { bookVersionRepository.findById(it).orElse(null) }
                        if (book != null && activeVersion != null && activeVersion.status == BookVersionStatus.READY) {
                            activeVersion.id to book
                        } else {
                            null
                        }
                    }.toMap()
            }
            RetrievalScope.AllBooks -> {
                bookRepository
                    .findAll()
                    .mapNotNull { book ->
                        val activeVersionId = book.activeVersionId ?: return@mapNotNull null
                        val version = bookVersionRepository.findById(activeVersionId).orElse(null) ?: return@mapNotNull null
                        if (isEligible(version)) version.id to book else null
                    }.toMap()
            }
        }

    private fun isEligible(version: BookVersion): Boolean =
        version.status == BookVersionStatus.READY &&
            version.embeddingModel == voyageProperties.model &&
            version.embeddingModelVersion == voyageProperties.modelVersion
}
