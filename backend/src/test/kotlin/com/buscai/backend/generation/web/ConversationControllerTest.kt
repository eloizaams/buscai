package com.buscai.backend.generation.web

import com.buscai.backend.generation.conversation.Conversation
import com.buscai.backend.generation.conversation.ConversationRepository
import com.buscai.backend.generation.conversation.Message
import com.buscai.backend.generation.conversation.MessageRepository
import com.buscai.backend.generation.conversation.MessageRole
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals

private const val VALID_API_KEY = "conversation-controller-test-key"

/**
 * Testes HTTP de `GET /conversations` e `GET /conversations/{id}` (`specs/geracao/tasks.md`, T6),
 * subindo o contexto Spring inteiro (filtros de T3 incluídos) contra um Postgres real via
 * Testcontainers — mesmo padrão de `ChatControllerTest`/`GenerationServiceTest`. Fixtures são
 * inseridas diretamente via `ConversationRepository`/`MessageRepository`, sem passar por
 * `ChatController`/`GenerationService`.
 */
@Testcontainers
@ActiveProfiles("testcontainers")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["buscai.api-key=$VALID_API_KEY"])
class ConversationControllerTest {
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
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var conversationRepository: ConversationRepository

    @Autowired
    lateinit var messageRepository: MessageRepository

    private fun newConversation(
        deviceId: String,
        updatedAt: Instant = Instant.now(),
    ): Conversation =
        conversationRepository.save(
            Conversation(id = UUID.randomUUID(), deviceId = deviceId, createdAt = updatedAt, updatedAt = updatedAt),
        )

    private fun newMessage(
        conversationId: UUID,
        role: MessageRole,
        content: String,
        createdAt: Instant = Instant.now(),
    ): Message =
        messageRepository.save(
            Message(id = UUID.randomUUID(), conversationId = conversationId, role = role, content = content, createdAt = createdAt),
        )

    @Test
    fun `GET conversations devolve so as conversas do device do header, mais recente primeiro`() {
        val deviceA = "device-${UUID.randomUUID()}"
        val deviceB = "device-${UUID.randomUUID()}"
        val now = Instant.now()

        val older = newConversation(deviceA, updatedAt = now.minus(2, ChronoUnit.HOURS))
        val newer = newConversation(deviceA, updatedAt = now)
        newConversation(deviceB, updatedAt = now)

        mockMvc
            .perform(
                get("/conversations")
                    .header("X-Api-Key", VALID_API_KEY)
                    .header("X-Device-Id", deviceA),
            ).andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value(newer.id.toString()))
            .andExpect(jsonPath("$[1].id").value(older.id.toString()))
    }

    @Test
    fun `GET conversations id devolve as mensagens do mesmo device em ordem cronologica`() {
        val deviceId = "device-${UUID.randomUUID()}"
        val conversation = newConversation(deviceId)
        val now = Instant.now()
        val userMessage = newMessage(conversation.id, MessageRole.USER, "Quem é Capitu?", createdAt = now.minusSeconds(10))
        val assistantMessage =
            newMessage(conversation.id, MessageRole.ASSISTANT, "Capitu é personagem de Dom Casmurro.", createdAt = now)

        mockMvc
            .perform(
                get("/conversations/${conversation.id}")
                    .header("X-Api-Key", VALID_API_KEY)
                    .header("X-Device-Id", deviceId),
            ).andExpect(jsonPath("$.id").value(conversation.id.toString()))
            .andExpect(jsonPath("$.messages.length()").value(2))
            .andExpect(jsonPath("$.messages[0].role").value("USER"))
            .andExpect(jsonPath("$.messages[0].content").value(userMessage.content))
            .andExpect(jsonPath("$.messages[1].role").value("ASSISTANT"))
            .andExpect(jsonPath("$.messages[1].content").value(assistantMessage.content))
    }

    @Test
    fun `GET conversations id de outro device devolve 404`() {
        val ownerDeviceId = "device-${UUID.randomUUID()}"
        val otherDeviceId = "device-${UUID.randomUUID()}"
        val conversation = newConversation(ownerDeviceId)

        val result =
            mockMvc
                .perform(
                    get("/conversations/${conversation.id}")
                        .header("X-Api-Key", VALID_API_KEY)
                        .header("X-Device-Id", otherDeviceId),
                ).andReturn()

        assertEquals(404, result.response.status)
    }

    @Test
    fun `GET conversations id inexistente devolve 404`() {
        val deviceId = "device-${UUID.randomUUID()}"

        val result =
            mockMvc
                .perform(
                    get("/conversations/${UUID.randomUUID()}")
                        .header("X-Api-Key", VALID_API_KEY)
                        .header("X-Device-Id", deviceId),
                ).andReturn()

        assertEquals(404, result.response.status)
    }

    @Test
    fun `GET conversations sem X-Device-Id devolve 400`() {
        val result =
            mockMvc
                .perform(
                    get("/conversations")
                        .header("X-Api-Key", VALID_API_KEY),
                ).andReturn()

        assertEquals(400, result.response.status)
    }

    @Test
    fun `GET conversations id sem X-Device-Id devolve 400`() {
        val result =
            mockMvc
                .perform(
                    get("/conversations/${UUID.randomUUID()}")
                        .header("X-Api-Key", VALID_API_KEY),
                ).andReturn()

        assertEquals(400, result.response.status)
    }
}
