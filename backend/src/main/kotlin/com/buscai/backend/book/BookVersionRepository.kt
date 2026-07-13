package com.buscai.backend.book

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BookVersionRepository : JpaRepository<BookVersion, UUID> {
    /**
     * Chave de gatilho skip/reindex-necessário do ADR-0008: `(bookId, fileHash, embeddingModel,
     * embeddingModelVersion)`. `IngestionService` NÃO usa este finder para decidir entre
     * `Skipped`, `ReindexRequired` e ingestão normal — a decisão é feita comparando os campos da
     * `BookVersion` ATIVA do livro (via `bookRepository.findById(bookId)?.activeVersionId`),
     * deliberadamente, para não disparar um skip a partir de uma `BookVersion` órfã/antiga que
     * bata a chave mas não seja a versão ativa. Este finder fica disponível para outros
     * consumidores futuros (ex.: auditoria/debug) — hoje só é exercitado por
     * `BookRepositoriesIntegrationTest` — e pode ser removido se nenhum consumidor precisar dele.
     */
    fun findByBookIdAndFileHashAndEmbeddingModelAndEmbeddingModelVersion(
        bookId: String,
        fileHash: String,
        embeddingModel: String,
        embeddingModelVersion: String,
    ): BookVersion?
}
