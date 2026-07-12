package com.buscai.backend.book

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ChunkRepository : JpaRepository<Chunk, UUID>
