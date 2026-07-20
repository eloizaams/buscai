package com.buscai.backend.generation.web

import com.buscai.backend.generation.conversation.ConversationStore
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

private const val DEVICE_ID_HEADER = "X-Device-Id"

/**
 * `GET /conversations` e `GET /conversations/{id}` (ADR-0007, `specs/geracao/plan.md`, "Contratos
 * entre camadas"): histórico de conversas de um device. Passa pelos filtros de T3
 * ([com.buscai.backend.config.ApiKeyFilter]/[com.buscai.backend.config.RateLimitFilter]) como
 * qualquer outra rota — nenhuma exceção registrada em
 * [com.buscai.backend.config.ApiSecurityPaths].
 *
 * Depende só de [ConversationStore] (camada de serviço, CLAUDE.md — "sem acesso direto a
 * repositório fora da camada de serviço"), mesmo padrão de `ChatController` dependendo só de
 * `GenerationService`: este controller nunca injeta `ConversationRepository`/`MessageRepository`
 * diretamente.
 *
 * Isolamento por [DEVICE_ID_HEADER] é soft (ver ressalva do ADR-0007 — sem garantia contra um
 * device que descubra o id de outro): sem o header, `400` antes de tocar `ConversationStore`; com
 * o header, mas apontando para uma conversa de outro device (ou um id inexistente), `404` — nunca
 * `403`, para não confirmar a existência do id a quem não é dono dele. Essa distinção de status
 * HTTP é responsabilidade deste controller: [ConversationStore.findDetail] devolve `null` nos dois
 * casos.
 */
@RestController
class ConversationController(
    private val conversationStore: ConversationStore,
) {
    @GetMapping("/conversations")
    fun listConversations(
        @RequestHeader(DEVICE_ID_HEADER) deviceIdHeader: String?,
    ): List<ConversationSummaryResponse> {
        val deviceId = requireDeviceId(deviceIdHeader)

        return conversationStore.listByDevice(deviceId).map { conversation ->
            ConversationSummaryResponse(
                id = conversation.id,
                createdAt = conversation.createdAt,
                updatedAt = conversation.updatedAt,
            )
        }
    }

    @GetMapping("/conversations/{id}")
    fun getConversation(
        @RequestHeader(DEVICE_ID_HEADER) deviceIdHeader: String?,
        @PathVariable id: UUID,
    ): ConversationDetailResponse {
        val deviceId = requireDeviceId(deviceIdHeader)

        val detail =
            conversationStore.findDetail(id, deviceId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "conversa não encontrada")

        return ConversationDetailResponse(
            id = detail.conversation.id,
            createdAt = detail.conversation.createdAt,
            updatedAt = detail.conversation.updatedAt,
            messages =
                detail.messages.map { message ->
                    MessageResponse(
                        role = message.role,
                        content = message.content,
                        createdAt = message.createdAt,
                    )
                },
        )
    }

    private fun requireDeviceId(deviceIdHeader: String?): String =
        deviceIdHeader?.takeUnless { it.isBlank() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "cabeçalho $DEVICE_ID_HEADER ausente")
}
