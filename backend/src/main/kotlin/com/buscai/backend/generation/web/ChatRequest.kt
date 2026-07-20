package com.buscai.backend.generation.web

import java.util.UUID

/**
 * Corpo de `POST /chat` (`specs/geracao/plan.md`, seção "Modelos de dado"). [conversationId]
 * ausente inicia uma conversa nova (CA11, `specs/geracao/spec.md`); presente continua uma conversa
 * existente do mesmo `X-Device-Id` (ADR-0007). [bookIds] ausente ou `null` busca em todo o acervo
 * (`RetrievalScope.AllBooks`); presente restringe a busca a esse subconjunto
 * (`RetrievalScope.Books`, CA6).
 */
data class ChatRequest(
    val conversationId: UUID? = null,
    val query: String,
    val bookIds: Set<String>? = null,
)
