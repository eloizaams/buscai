package com.buscai.backend.retrieval

import java.util.UUID

/**
 * Trecho relevante já pronto para consumo pela geração (Fase 5) ou pelo `RetrievalDebugCommand`
 * (T7) — formato final de citação (livro + página), diferente da projeção crua
 * `HybridSearchRow` (`retrieval.search`): [bookId]/[bookTitle] já resolvidos via `catalog`
 * (`RetrievalService`), e [score] é o `HybridSearchRow.rrfScore` (ordenação final da fusão RRF),
 * não a `cosineSimilarity` bruta do ramo vetorial.
 */
data class RetrievedChunk(
    val chunkId: UUID,
    val bookId: String,
    val bookTitle: String,
    val page: Int,
    val chapter: String?,
    val text: String,
    val score: Double,
)
