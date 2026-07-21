package com.buscai.backend.catalog

import com.buscai.backend.ingestion.chunking.ReferenceType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Uma geração de ingestão de um [Book] (ADR-0008). [bookId], [fileHash], [embeddingModel] e
 * [embeddingModelVersion] juntos formam a chave de gatilho skip/reindex-necessário (ver índice
 * `idx_book_version_trigger_key` da migration V1 e o finder em [BookVersionRepository]).
 * [referenceType] (ADR-0013, migration V4) registra o estilo de referência declarado via
 * `--reference-style` nesta ingestão — proveniência usada por `ChunkValidator` para decidir qual
 * invariante estrutural de chunk aplicar; a citação em si sempre usa o par `reference`/
 * `referenceType` de cada [Chunk], não este campo.
 */
@Entity
@Table(name = "book_version")
class BookVersion(
    @Id
    @Column(name = "id")
    val id: UUID,
    @Column(name = "book_id", nullable = false)
    var bookId: String,
    @Column(name = "file_hash", nullable = false, length = 64)
    var fileHash: String,
    @Column(name = "embedding_model", nullable = false)
    var embeddingModel: String,
    @Column(name = "embedding_model_version", nullable = false)
    var embeddingModelVersion: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: BookVersionStatus,
    @Column(name = "page_count")
    var pageCount: Int? = null,
    @Column(name = "chunk_count")
    var chunkCount: Int? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type")
    var referenceType: ReferenceType? = null,
    @Column(name = "started_at", nullable = false)
    var startedAt: Instant = Instant.now(),
    @Column(name = "completed_at")
    var completedAt: Instant? = null,
)
