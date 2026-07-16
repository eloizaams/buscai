package com.buscai.backend.embedding

/**
 * Parâmetro assimétrico `input_type` da API de embeddings da Voyage AI (ADR-0010): distingue texto
 * de conteúdo indexado (`DOCUMENT`) de texto de pergunta de busca (`QUERY`), melhorando recall de
 * forma mensurável em relação a embeddar os dois tipos sem distinção.
 *
 * Mapeado 1:1 para o valor em minúsculo esperado pela Voyage (`"document"`/`"query"`) — confirmado
 * em `docs.voyageai.com/docs/embeddings` (`input_type="document"`/`input_type` "to `query` or
 * `document`"), ver [VoyageEmbeddingRequest][com.buscai.backend.embedding.VoyageEmbeddingRequest].
 */
enum class EmbeddingInputType {
    DOCUMENT,
    QUERY,
}
