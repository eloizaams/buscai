package com.buscai.backend.generation.conversation

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Um turno (pergunta OU resposta) de uma [Conversation], identificada por [conversationId]. */
@Entity
@Table(name = "message")
class Message(
    @Id
    @Column(name = "id")
    val id: UUID,
    @Column(name = "conversation_id", nullable = false)
    var conversationId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    var role: MessageRole,
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    var content: String,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)
