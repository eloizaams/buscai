package com.buscai.backend.generation.conversation

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ConversationRepository : JpaRepository<Conversation, UUID> {
    /**
     * Conversas de um device, mais recentemente atualizada primeiro — usado pelo futuro
     * `GET /conversations` (ADR-0007).
     */
    fun findByDeviceIdOrderByUpdatedAtDesc(deviceId: String): List<Conversation>
}
