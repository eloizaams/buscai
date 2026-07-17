package com.buscai.backend.retrieval

/**
 * Resultado de `RetrievalService.search`. [NoRelevantContext] cobre dois caminhos: "nenhuma
 * `BookVersion` elegível" (escopo resolvido para um conjunto vazio, ver `RetrievalScope`/`plan.md`,
 * T4) e "melhor `cosineSimilarity` dos candidatos pós-`ContextAssembler` abaixo do limiar
 * configurado" (CA7, T6) — inclui o caso de lista de candidatos vazia após dedup/orçamento, que
 * também é tratado como `NoRelevantContext`, não `Found(emptyList())`.
 */
sealed class RetrievalResult {
    data class Found(
        val chunks: List<RetrievedChunk>,
    ) : RetrievalResult()

    object NoRelevantContext : RetrievalResult()
}
