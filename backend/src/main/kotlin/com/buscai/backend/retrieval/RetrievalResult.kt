package com.buscai.backend.retrieval

/**
 * Resultado de `RetrievalService.search`. Nesta task (T4), [NoRelevantContext] cobre só o caminho
 * "nenhuma `BookVersion` elegível" (escopo resolvido para um conjunto vazio, ver
 * `RetrievalScope`/`plan.md`) — o sinal completo de "sem contexto relevante" baseado na melhor
 * `cosineSimilarity` dos candidatos (CA7, `spec.md`) é T6, ainda não implementado: uma lista de
 * candidatos vazia vinda da busca híbrida também é devolvida como `Found(emptyList())` por
 * enquanto, não como `NoRelevantContext`.
 */
sealed class RetrievalResult {
    data class Found(
        val chunks: List<RetrievedChunk>,
    ) : RetrievalResult()

    object NoRelevantContext : RetrievalResult()
}
