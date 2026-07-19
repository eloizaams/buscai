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
