package com.buscai.backend.generation.conversation

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Estado de uma conversa (ADR-0007): identificada por [id], associada a um [deviceId] (soft
 * isolation — ver ressalva do ADR-0007, não há garantia contra um device que descubra o id de
 * outro). Não faz parte do acervo (`catalog/`) — ADR-0009, estado de conversa não é acervo.
 */
@Entity
@Table(name = "conversation")
class Conversation(
    @Id
    @Column(name = "id")
    val id: UUID,
    @Column(name = "device_id", nullable = false)
    var deviceId: String,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
