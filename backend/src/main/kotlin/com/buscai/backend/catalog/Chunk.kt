package com.buscai.backend.catalog

import com.buscai.backend.ingestion.chunking.ReferenceType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
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
 * comentário em `build.gradle.kts`), não a classe `com.pgvector.PGvector` diretamente. [reference]/
 * [referenceType] (ADR-0013) substituem o antigo `chapter` (migration V4) — preenchidos só quando a
 * ingestão declara `--reference-style`; `null`/`null` para livros ingeridos sem a flag. [itemStart]/
 * [itemEnd] (busca-exata-item, migration V5) são a mesma faixa de [reference] já derivada em
 * inteiro, preenchidos só para `referenceType == NUMBERED_ITEM`.
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
    @Column(name = "reference")
    var reference: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type")
    var referenceType: ReferenceType? = null,
    @Column(name = "item_start")
    var itemStart: Int? = null,
    @Column(name = "item_end")
    var itemEnd: Int? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)
