package com.buscai.backend.retrieval.search

import java.util.UUID

/**
 * Projeção crua de uma linha candidata da busca híbrida ([HybridSearchDao.search]), antes de
 * qualquer resolução de título de livro (feita depois, em `RetrievalService`/T4) ou de
 * dedup/orçamento (`ContextAssembler`/T5).
 *
 * [charOffset] é o offset de caractere, dentro da página, de onde o conteúdo próprio do chunk
 * começa (`Chunk.charOffset`, `catalog`) — usado por `ContextAssembler` (T5) para comparar a
 * janela `[charOffset, charOffset + text.length)` de chunks vizinhos e detectar sobreposição
 * significativa (dedup, CA4). A comparação só faz sentido entre candidatos da mesma
 * [bookVersionId] **e** da mesma [page] — [charOffset] é relativo à página, não ao livro inteiro
 * (`ContextAssembler.overlapsSignificantly` aplica os dois filtros antes de comparar janelas).
 *
 * [cosineSimilarity] e [rrfScore] são dois números distintos, não confundir:
 * - [cosineSimilarity]: similaridade bruta do ramo vetorial (`1 - distância de cosine`). Quando o
 *   chunk só apareceu no ramo léxico (fora do top-N do ramo vetorial), vem `0.0` por decisão de
 *   `HybridSearchDao` — não é "similaridade zero" medida de fato, é um valor de "não disponível"
 *   tratado como zero (documentado também na query nativa). **Por isso [cosineSimilarity] sozinho
 *   nunca deve decidir se um chunk é irrelevante** — ver [matchedLexicalBranch].
 * - [rrfScore]: score de fusão (Reciprocal Rank Fusion) usado para ordenar o resultado final —
 *   é por rank, não por distância; não deve ser lido como sinal de relevância absoluta (ver
 *   `plan.md`, seção "Contratos entre camadas", sobre o sinal de "sem contexto relevante").
 *
 * [matchedLexicalBranch]: `true` quando o chunk apareceu na CTE `lexical_rank` de
 * `HybridSearchDao` (match léxico exato dentro de `lexicalCandidates`), independente de também ter
 * aparecido no ramo vetorial. Distingue o [cosineSimilarity] `0.0` genuíno (chunk não apareceu em
 * nenhum dos dois ramos com sinal forte) do `0.0` de "não disponível" (chunk só veio do ramo
 * léxico) — corrige um bug de CA7 em que um match léxico exato de um termo/nome próprio (CA3, o
 * cenário que a fusão RRF existe para resolver) era descartado como "sem contexto relevante" só
 * por não ter aparecido entre os top-N vetoriais (nota datada em `plan.md`, seção sobre o sinal de
 * "sem contexto relevante", 2026-07-17).
 */
data class HybridSearchRow(
    val chunkId: UUID,
    val bookVersionId: UUID,
    val page: Int,
    val charOffset: Int,
    val chapter: String?,
    val text: String,
    val tokenCount: Int,
    val cosineSimilarity: Double,
    val rrfScore: Double,
    val matchedLexicalBranch: Boolean,
)
