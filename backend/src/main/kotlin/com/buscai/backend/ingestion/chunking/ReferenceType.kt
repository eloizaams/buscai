package com.buscai.backend.ingestion.chunking

/**
 * Estilo de referência estruturada de um chunk (ADR-0013): capítulo (prosa) ou item numerado
 * (catecismo/livro em itens/perguntas numeradas). Tipo único, reaproveitado por [ChunkDraft],
 * `Chunk` (catalog), `IngestArgs` (CLI de ingestão) e `RetrievedChunk`/`SourceItem` (retrieval e
 * evento SSE `sources`) — nenhuma camada declara sua própria variante.
 */
enum class ReferenceType {
    CHAPTER,
    NUMBERED_ITEM,
}
