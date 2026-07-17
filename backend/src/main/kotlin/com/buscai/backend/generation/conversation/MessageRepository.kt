package com.buscai.backend.generation.conversation

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MessageRepository : JpaRepository<Message, UUID> {
    /** Mensagens de uma conversa em ordem cronológica — usado para reconstruir o histórico. */
    fun findByConversationIdOrderByCreatedAtAsc(conversationId: UUID): List<Message>
}
