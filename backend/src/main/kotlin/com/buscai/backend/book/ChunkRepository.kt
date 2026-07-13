package com.buscai.backend.book

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ChunkRepository : JpaRepository<Chunk, UUID> {
    /**
     * Remove todos os `Chunk`s de uma `BookVersion` — usado pelo swap atômico da reindexação (T9,
     * ADR-0008/`plan.md`, seção "Swap atômico"): ao apontar `Book.activeVersionId` para a nova
     * versão `READY`, a versão anterior e seus chunks são removidos na mesma transação curta.
     * Devolve a contagem de linhas removidas.
     */
    fun deleteByBookVersionId(bookVersionId: UUID): Long
}
