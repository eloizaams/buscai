package com.buscai.backend.book

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Array
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/** Dimensão de saída fixa do modelo `voyage-3` (ADR-0003), igual ao `vector(1024)` da migration V1. */
const val EMBEDDING_DIMENSIONS = 1024

/**
 * Um trecho de texto de uma [BookVersion] com seu vetor de embedding. [embedding] usa o suporte
 * nativo a `vector` do pgvector via `hibernate-vector` (Hibernate 6.4+, aqui 7.4.1.Final — ver
 * comentário em `build.gradle.kts`), não a classe `com.pgvector.PGvector` diretamente.
 */
@Entity
@Table(name = "chunk")
class Chunk(
    @Id
    @Column(name = "id")
    val id: UUID,
    @Column(name = "book_version_id", nullable = false)
    var bookVersionId: UUID,
    @Column(name = "page", nullable = false)
    var page: Int,
    @Column(name = "char_offset", nullable = false)
    var charOffset: Int,
    @Column(name = "token_count", nullable = false)
    var tokenCount: Int,
    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    var text: String,
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = EMBEDDING_DIMENSIONS)
    @Column(name = "embedding", nullable = false)
    var embedding: FloatArray,
    @Column(name = "chapter")
    var chapter: String? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)
