package com.buscai.backend.retrieval

import com.buscai.backend.ingestion.chunking.ReferenceType
import java.util.UUID

/**
 * Trecho relevante já pronto para consumo pela geração (Fase 5) ou pelo `RetrievalDebugCommand`
 * (T7) — formato final de citação (livro + capítulo/item, ADR-0013), diferente da projeção crua
 * `HybridSearchRow` (`retrieval.search`): [bookId]/[bookTitle] já resolvidos via `catalog`
 * (`RetrievalService`), e [score] é o `HybridSearchRow.rrfScore` (ordenação final da fusão RRF),
 * não a `cosineSimilarity` bruta do ramo vetorial. [reference]/[referenceType] substituem o antigo
 * `chapter` (`specs/referencia-estruturada/plan.md`) — `null`/`null` quando o chunk não tem
 * referência estruturada (livro ingerido sem `--reference-style`).
 */
data class RetrievedChunk(
    val chunkId: UUID,
    val bookId: String,
    val bookTitle: String,
    val page: Int,
    val reference: String?,
    val referenceType: ReferenceType?,
    val text: String,
    val score: Double,
)
