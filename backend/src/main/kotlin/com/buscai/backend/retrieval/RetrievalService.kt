package com.buscai.backend.retrieval

import com.buscai.backend.catalog.Book
import com.buscai.backend.catalog.BookRepository
import com.buscai.backend.catalog.BookVersion
import com.buscai.backend.catalog.BookVersionRepository
import com.buscai.backend.catalog.BookVersionStatus
import com.buscai.backend.embedding.EmbeddingClient
import com.buscai.backend.embedding.EmbeddingInputType
import com.buscai.backend.embedding.VoyageProperties
import com.buscai.backend.retrieval.context.ContextAssembler
import com.buscai.backend.retrieval.search.HybridSearchDao
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Único ponto de entrada da lógica de retrieval (`specs/retrieval/plan.md`, seção "Contratos entre
 * camadas"): resolve o escopo da busca em versões ativas buscáveis (CA2/CA6), embedda a pergunta
 * (`EmbeddingClient.embed(..., EmbeddingInputType.QUERY)`, ADR-0010), delega a busca híbrida ao
 * [HybridSearchDao] (T3), corta em [RetrievalProperties.topK] candidatos pós-fusão (`plan.md`,
 * "Config nova" — "quantos candidatos pós-fusão entram no `ContextAssembler`", T7), passa o
 * restante pelo [ContextAssembler] (T5) e, por fim, compara a maior `cosineSimilarity` dos
 * candidatos restantes contra [RetrievalProperties.minCosineSimilarity] (CA7, T6) — abaixo do
 * limiar (ou lista vazia), devolve `NoRelevantContext` mesmo com candidatos produzidos pelas
 * etapas anteriores.
 */
@Service
class RetrievalService(
    private val bookRepository: BookRepository,
    private val bookVersionRepository: BookVersionRepository,
    private val embeddingClient: EmbeddingClient,
    private val voyageProperties: VoyageProperties,
    private val hybridSearchDao: HybridSearchDao,
    private val contextAssembler: ContextAssembler,
    private val retrievalProperties: RetrievalProperties,
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
                vectorCandidates = retrievalProperties.vectorCandidates,
                lexicalCandidates = retrievalProperties.lexicalCandidates,
                rrfK = retrievalProperties.rrfK,
            )

        // Corte de top-k pós-fusão (RRF já ordena `rows` por `rrfScore` desc, ver
        // HybridSearchDao.search): "quantos candidatos pós-fusão entram no ContextAssembler"
        // (plan.md, "Config nova") — aplicado aqui, antes do ContextAssembler, e não dentro dele,
        // porque o corte é sobre o *número* de candidatos que chegam ao dedup/orçamento, não sobre
        // o orçamento de tokens em si (responsabilidade já coberta por tokenBudget).
        val topRows = rows.take(retrievalProperties.topK)

        val assembledRows =
            contextAssembler.assemble(
                rows = topRows,
                tokenBudget = retrievalProperties.tokenBudget,
                neighborDedupMinOverlapChars = retrievalProperties.neighborDedupMinOverlapChars,
            )

        val bestCosineSimilarity = assembledRows.maxOfOrNull { it.cosineSimilarity }
        if (bestCosineSimilarity == null || bestCosineSimilarity < retrievalProperties.minCosineSimilarity) {
            return RetrievalResult.NoRelevantContext
        }

        val chunks =
            assembledRows.map { row ->
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
     *
     * Ambos os ramos usam `findAllById` (uma query `IN` por lote) em vez de `findById` num loop —
     * evita 1+N queries proporcional ao tamanho do conjunto (acervo inteiro em `AllBooks`, CA8
     * mira <100ms sobre ~50 mil chunks, ver `code-reviewer` da T4).
     */
    private fun resolveEligibleVersions(scope: RetrievalScope): Map<UUID, Book> =
        when (scope) {
            is RetrievalScope.Books -> {
                val booksById = bookRepository.findAllById(scope.bookIds).associateBy { it.id }
                val activeVersionIdToBook =
                    booksById.values
                        .mapNotNull { book -> book.activeVersionId?.let { it to book } }
                        .toMap()
                bookVersionRepository
                    .findAllById(activeVersionIdToBook.keys)
                    .filter { version -> version.status == BookVersionStatus.READY }
                    .associate { version -> version.id to activeVersionIdToBook.getValue(version.id) }
            }
            RetrievalScope.AllBooks -> {
                val activeVersionIdToBook =
                    bookRepository
                        .findAll()
                        .mapNotNull { book -> book.activeVersionId?.let { it to book } }
                        .toMap()
                bookVersionRepository
                    .findAllById(activeVersionIdToBook.keys)
                    .filter { version -> isEligible(version) }
                    .associate { version -> version.id to activeVersionIdToBook.getValue(version.id) }
            }
        }

    private fun isEligible(version: BookVersion): Boolean =
        version.status == BookVersionStatus.READY &&
            version.embeddingModel == voyageProperties.model &&
            version.embeddingModelVersion == voyageProperties.modelVersion
}
