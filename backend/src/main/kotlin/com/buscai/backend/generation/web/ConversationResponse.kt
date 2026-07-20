package com.buscai.backend.generation.web

import com.buscai.backend.generation.conversation.MessageRole
import java.time.Instant
import java.util.UUID

/**
 * Um item de `GET /conversations` (`specs/geracao/plan.md`, seção "Modelos de dado"): resumo de
 * uma conversa, sem as mensagens (essas só vêm em `GET /conversations/{id}`).
 */
data class ConversationSummaryResponse(
    val id: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Um turno (pergunta ou resposta) devolvido por `GET /conversations/{id}`. */
data class MessageResponse(
    val role: MessageRole,
    val content: String,
    val createdAt: Instant,
)

/**
 * Corpo de `GET /conversations/{id}`: a conversa reaberta com todo o histórico, em ordem
 * cronológica (CA7, `specs/geracao/spec.md`).
 */
data class ConversationDetailResponse(
    val id: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
    val messages: List<MessageResponse>,
)
