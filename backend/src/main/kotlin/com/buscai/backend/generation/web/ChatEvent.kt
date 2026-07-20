package com.buscai.backend.generation.web

import java.util.UUID

/**
 * Contrato de eventos SSE de `POST /chat` (`specs/geracao/plan.md`, seção "Modelos de dado"):
 * - [Conversation]: id da conversa — emitido só quando uma conversa **nova** foi criada nesta
 *   requisição (`ChatRequest.conversationId` ausente), sempre antes de qualquer [Token]
 *   ([ChatController] resolve/cria a conversa antes de chamar `GenerationService`).
 * - [Token]: um delta de texto da resposta, na ordem em que chega (streaming, CA3).
 * - [Done]: o stream terminou com sucesso — nunca aparece depois de um [Error], nem [Token]
 *   depois de [Done].
 * - [Error]: falha em qualquer etapa do pipeline (resolução de conversa, rewrite, retrieval,
 *   geração) — [message] é sempre um texto genérico (CA9, `specs/geracao/spec.md`: nunca o
 *   detalhe cru da exceção, que poderia vazar informação de infraestrutura ou de chave de API).
 */
sealed class ChatEvent {
    data class Conversation(
        val id: UUID,
    ) : ChatEvent()

    data class Token(
        val text: String,
    ) : ChatEvent()

    object Done : ChatEvent()

    data class Error(
        val message: String,
    ) : ChatEvent()
}
