package com.buscai.backend.catalog

/**
 * Espelha o `CHECK (status IN (...))` da coluna `book_version.status` (migration V1).
 */
enum class BookVersionStatus {
    INGESTING,
    READY,
    FAILED,
}
