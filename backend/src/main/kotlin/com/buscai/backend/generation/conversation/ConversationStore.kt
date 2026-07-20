package com.buscai.backend.generation.conversation

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Erro ao resolver uma [Conversation] existente a partir de um `conversationId` informado
 * (`GenerationService`, `specs/geracao/plan.md`): id inexistente **ou** pertencente a outro
 * `deviceId` (soft isolation, ADR-0007 — mesma ressalva de `GET /conversations/{id}`, T6: não
 * distinguir os dois casos evita confirmar a existência do id a quem não é dono).
 */
class ConversationNotFoundException(
    conversationId: UUID,
) : RuntimeException("Conversa $conversationId não encontrada para este device")

/**
 * Uma conversa reaberta com todo o histórico, em ordem cronológica — devolvido por
 * [ConversationStore.findDetail] para `ConversationController` (`GET /conversations/{id}`, T6).
 */
data class ConversationDetail(
    val conversation: Conversation,
    val messages: List<Message>,
)

/**
 * Persistência de [Conversation]/[Message] usada por `GenerationService` (`specs/geracao/plan.md`,
 * seção "Execução assíncrona, transação e persistência"): cada método abre sua própria transação,
 * já que `GenerationService` roda numa thread de worker fora da sessão JPA do request (T5) — sem
 * isso, nem a leitura do histórico nem a gravação de `Message` teriam uma transação ativa.
 *
 * Extraído como um componente à parte (em vez de métodos privados de `GenerationService` anotados
 * `@Transactional`) porque o Spring AOP baseado em proxy não intercepta invocação interna
 * (self-invocation): um método `@Transactional` chamado via `this` de dentro da própria classe não
 * passa pelo proxy, e a anotação seria silenciosamente ignorada. Métodos aqui são sempre chamados a
 * partir de outro bean (`GenerationService`), então o proxy transacional funciona normalmente.
 */
@Component
class ConversationStore(
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
) {
    /**
     * [conversationId] ausente: cria uma [Conversation] nova associada a [deviceId] (CA11).
     * Presente: busca a existente, filtrando por [deviceId] — inexistente ou de outro device lança
     * [ConversationNotFoundException].
     */
    @Transactional
    fun resolveConversation(
        deviceId: String,
        conversationId: UUID?,
    ): Conversation {
        if (conversationId == null) {
            return conversationRepository.save(Conversation(id = UUID.randomUUID(), deviceId = deviceId))
        }
        val conversation =
            conversationRepository.findById(conversationId).orElse(null)
                ?: throw ConversationNotFoundException(conversationId)
        if (conversation.deviceId != deviceId) {
            throw ConversationNotFoundException(conversationId)
        }
        return conversation
    }

    /**
     * Últimas [limit] mensagens **já existentes** de [conversationId], mais antiga primeiro — nunca
     * inclui o turno que está sendo processado agora (`GenerationService` chama isto antes de
     * persistir a pergunta atual).
     */
    @Transactional(readOnly = true)
    fun recentHistory(
        conversationId: UUID,
        limit: Int,
    ): List<Message> = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).takeLast(limit)

    /**
     * Conversas de [deviceId], mais recentemente atualizada primeiro — usado por
     * `ConversationController.listConversations` (`GET /conversations`, T6), que não deve chamar
     * [ConversationRepository] diretamente (camada de serviço, CLAUDE.md).
     */
    @Transactional(readOnly = true)
    fun listByDevice(deviceId: String): List<Conversation> = conversationRepository.findByDeviceIdOrderByUpdatedAtDesc(deviceId)

    /**
     * Busca [id] e devolve seu histórico completo (mesmo finder de [recentHistory], sem o
     * `takeLast`) só se a conversa pertence a [deviceId] — `null` tanto se [id] não existe quanto
     * se existe mas é de outro device; a distinção de status HTTP (404 nos dois casos, ADR-0007)
     * é responsabilidade de `ConversationController`, não deste serviço.
     */
    @Transactional(readOnly = true)
    fun findDetail(
        id: UUID,
        deviceId: String,
    ): ConversationDetail? {
        val conversation =
            conversationRepository.findById(id).orElse(null)?.takeIf { it.deviceId == deviceId }
                ?: return null
        val messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.id)
        return ConversationDetail(conversation, messages)
    }

    /**
     * Persiste uma [Message] de [role] em [conversation] e atualiza `Conversation.updatedAt` — o
     * finder `findByDeviceIdOrderByUpdatedAtDesc` (T2) só ordena corretamente `GET /conversations`
     * (T6) se esse campo refletir a última atividade real.
     */
    @Transactional
    fun appendMessage(
        conversation: Conversation,
        role: MessageRole,
        content: String,
    ): Message {
        val message =
            messageRepository.save(
                Message(
                    id = UUID.randomUUID(),
                    conversationId = conversation.id,
                    role = role,
                    content = content,
                ),
            )
        conversation.updatedAt = Instant.now()
        conversationRepository.save(conversation)
        return message
    }
}
