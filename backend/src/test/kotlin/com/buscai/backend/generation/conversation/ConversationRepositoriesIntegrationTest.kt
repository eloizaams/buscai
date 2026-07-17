package com.buscai.backend.generation.conversation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import kotlin.test.assertEquals

// Mesmo padrão de BookRepositoriesIntegrationTest (catalog/): Postgres real via Testcontainers,
// já que a migration V3 exige a FK message.conversation_id -> conversation.id (H2 do
// contextLoads não roda essa migration).
@Testcontainers
@ActiveProfiles("testcontainers")
@SpringBootTest
class ConversationRepositoriesIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16"))

        @DynamicPropertySource
        @JvmStatic
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired
    lateinit var conversationRepository: ConversationRepository

    @Autowired
    lateinit var messageRepository: MessageRepository

    @Test
    fun `persiste uma Conversation com duas Messages e os finders devolvem a ordem esperada`() {
        val deviceId = "device-${UUID.randomUUID()}"
        val conversation =
            conversationRepository.save(
                Conversation(id = UUID.randomUUID(), deviceId = deviceId),
            )

        val userMessage =
            messageRepository.save(
                Message(
                    id = UUID.randomUUID(),
                    conversationId = conversation.id,
                    role = MessageRole.USER,
                    content = "Quem é Capitu?",
                ),
            )
        val assistantMessage =
            messageRepository.save(
                Message(
                    id = UUID.randomUUID(),
                    conversationId = conversation.id,
                    role = MessageRole.ASSISTANT,
                    content = "Capitu é uma personagem de Dom Casmurro.",
                ),
            )

        val history = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.id)
        assertEquals(listOf(userMessage.id, assistantMessage.id), history.map { it.id })
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), history.map { it.role })

        val conversationsOfDevice = conversationRepository.findByDeviceIdOrderByUpdatedAtDesc(deviceId)
        assertEquals(listOf(conversation.id), conversationsOfDevice.map { it.id })
    }

    @Test
    fun `Message referenciando conversationId inexistente viola a FK`() {
        assertThrows<DataIntegrityViolationException> {
            messageRepository.saveAndFlush(
                Message(
                    id = UUID.randomUUID(),
                    conversationId = UUID.randomUUID(),
                    role = MessageRole.USER,
                    content = "Pergunta órfã.",
                ),
            )
        }
    }
}
