package com.buscai.backend.generation.web

import com.buscai.backend.ingestion.chunking.ReferenceType
import java.util.UUID

/**
 * Contrato de eventos SSE de `POST /chat` (`specs/geracao/plan.md`, seção "Modelos de dado"):
 * - [Conversation]: id da conversa — emitido só quando uma conversa **nova** foi criada nesta
 *   requisição (`ChatRequest.conversationId` ausente), sempre antes de qualquer [Token]
 *   ([ChatController] resolve/cria a conversa antes de chamar `GenerationService`).
 * - [Sources]: lista dos chunks que fundamentaram a resposta (`docs/adr/0013-...md`, seção 3) —
 *   emitido uma única vez, no ramo `RetrievalResult.Found`, sempre antes de qualquer [Token];
 *   nunca emitido quando não há contexto relevante (`RetrievalResult.NoRelevantContext`, CA2 de
 *   `specs/referencia-estruturada/spec.md`).
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

    data class Sources(
        val sources: List<SourceItem>,
    ) : ChatEvent()

    data class Token(
        val text: String,
    ) : ChatEvent()

    object Done : ChatEvent()

    data class Error(
        val message: String,
    ) : ChatEvent()
}

/**
 * Um chunk recuperado, na forma exposta ao cliente via `event: sources` (`docs/adr/0013-...md`,
 * seção 3) — [reference]/[referenceType] vêm `null` quando o chunk não tem referência estruturada
 * (livro ingerido sem `--reference-style`), mas o item nunca é omitido da lista (CA6,
 * `specs/referencia-estruturada/spec.md`). [text] é o texto completo do chunk, sem truncamento.
 */
data class SourceItem(
    val chunkId: UUID,
    val bookId: String,
    val bookTitle: String,
    val reference: String?,
    val referenceType: ReferenceType?,
    val text: String,
)
