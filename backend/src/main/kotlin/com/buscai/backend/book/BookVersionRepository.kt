package com.buscai.backend.book

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BookVersionRepository : JpaRepository<BookVersion, UUID> {
    /**
     * Chave de gatilho skip/reindex-necessário do ADR-0008: `(bookId, fileHash, embeddingModel,
     * embeddingModelVersion)`. Usado por `IngestionService` para decidir entre `Skipped`,
     * `ReindexRequired` e ingestão normal.
     */
    fun findByBookIdAndFileHashAndEmbeddingModelAndEmbeddingModelVersion(
        bookId: String,
        fileHash: String,
        embeddingModel: String,
        embeddingModelVersion: String,
    ): BookVersion?
}
