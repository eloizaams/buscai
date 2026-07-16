package com.buscai.backend.retrieval.search

import java.util.UUID

/**
 * Projeção crua de uma linha candidata da busca híbrida ([HybridSearchDao.search]), antes de
 * qualquer resolução de título de livro (feita depois, em `RetrievalService`/T4) ou de
 * dedup/orçamento (`ContextAssembler`/T5).
 *
 * [cosineSimilarity] e [rrfScore] são dois números distintos, não confundir:
 * - [cosineSimilarity]: similaridade bruta do ramo vetorial (`1 - distância de cosine`). Quando o
 *   chunk só apareceu no ramo léxico (fora do top-N do ramo vetorial), vem `0.0` por decisão de
 *   `HybridSearchDao` — não é "similaridade zero" medida de fato, é um valor de "não disponível"
 *   tratado como zero (documentado também na query nativa).
 * - [rrfScore]: score de fusão (Reciprocal Rank Fusion) usado para ordenar o resultado final —
 *   é por rank, não por distância; não deve ser lido como sinal de relevância absoluta (ver
 *   `plan.md`, seção "Contratos entre camadas", sobre o sinal de "sem contexto relevante").
 */
data class HybridSearchRow(
    val chunkId: UUID,
    val bookVersionId: UUID,
    val page: Int,
    val chapter: String?,
    val text: String,
    val tokenCount: Int,
    val cosineSimilarity: Double,
    val rrfScore: Double,
)
