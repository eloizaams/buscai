package com.buscai.backend.catalog

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Identidade estável de um livro (ADR-0008): [id] é o slug fornecido pelo operador via CLI, não
 * derivado de hash de arquivo. [activeVersionId] fica nulo enquanto a primeira ingestão não
 * termina, e aponta para a [BookVersion] `READY` servida pela busca depois do swap atômico.
 */
@Entity
@Table(name = "book")
class Book(
    @Id
    @Column(name = "id")
    val id: String,
    @Column(name = "title", nullable = false)
    var title: String,
    @Column(name = "active_version_id")
    var activeVersionId: UUID? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
