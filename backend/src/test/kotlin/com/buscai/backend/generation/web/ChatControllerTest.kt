package com.buscai.backend.generation.web

import com.buscai.backend.catalog.Book
import com.buscai.backend.catalog.BookRepository
import com.buscai.backend.catalog.BookVersion
import com.buscai.backend.catalog.BookVersionRepository
import com.buscai.backend.catalog.BookVersionStatus
import com.buscai.backend.catalog.Chunk
import com.buscai.backend.catalog.ChunkRepository
import com.buscai.backend.catalog.EMBEDDING_DIMENSIONS
import com.buscai.backend.embedding.EmbeddingClient
import com.buscai.backend.embedding.EmbeddingInputType
import com.buscai.backend.generation.GenerationService
import com.buscai.backend.generation.claude.ClaudeClient
import com.buscai.backend.generation.claude.ClaudeClientException
import com.buscai.backend.generation.claude.HistoryTurn
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val VALID_API_KEY = "chat-controller-test-key"

/**
 * Testes HTTP de ponta a ponta de `POST /chat` (`specs/geracao/tasks.md`, T5), subindo o contexto
 * Spring inteiro (filtros de T3 incluídos) contra um Postgres real via Testcontainers — mesmo
 * padrão de dependências reais de `GenerationServiceTest` (T4), com [FakeQueryEmbeddingClient] e
 * [FakeClaudeClient] no lugar da Voyage/Claude reais. Usa `MockMvc` com o padrão de teste de
 * controller assíncrono do Spring MVC: `asyncStarted()` para confirmar que o processamento foi
 * delegado ao executor dedicado, seguido de um polling curto sobre o corpo já escrito na resposta
 * (`awaitSseCompletion`) até a tarefa de worker terminar — mais simples e robusto aqui do que tentar
 * usar `asyncDispatch`, que é pensado para `Callable`/`DeferredResult`, não para `SseEmitter`
 * (que escreve direto na resposta assim que cada evento é enviado).
 */
@Testcontainers
@ActiveProfiles("testcontainers")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["buscai.api-key=$VALID_API_KEY"])
class ChatControllerTest {
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

        private fun oneHotEmbedding(hotIndex: Int): FloatArray =
            FloatArray(EMBEDDING_DIMENSIONS) { index -> if (index == hotIndex) 1f else 0f }
    }

    @TestConfiguration
    class FakeConfig {
        @Bean
        @Primary
        fun fakeQueryEmbeddingClient(): FakeQueryEmbeddingClient = FakeQueryEmbeddingClient()

        @Bean
        @Primary
        fun fakeClaudeClient(): FakeClaudeClient = FakeClaudeClient()
    }

    /** Ver KDoc da classe — substitui `VoyageEmbeddingClient` (mesmo padrão de `GenerationServiceTest`). */
    class FakeQueryEmbeddingClient : EmbeddingClient {
        override fun embed(
            texts: List<String>,
            inputType: EmbeddingInputType,
        ): List<FloatArray> = texts.map { oneHotEmbedding(0) }
    }

    /** Fake determinístico de [ClaudeClient], sem chamar a Anthropic real (mesmo padrão de `GenerationServiceTest`). */
    class FakeClaudeClient : ClaudeClient {
        val rewriteCalls = mutableListOf<String>()
        val generateCalls = mutableListOf<String>()
        var generateTokens: List<String> = listOf("Resposta ", "completa.")
        var generateShouldFail: Boolean = false

        fun reset() {
            rewriteCalls.clear()
            generateCalls.clear()
            generateTokens = listOf("Resposta ", "completa.")
            generateShouldFail = false
        }

        override fun rewriteQuery(
            query: String,
            history: List<HistoryTurn>,
        ): String {
            rewriteCalls += query
            return query
        }

        override fun generate(
            systemPrompt: String,
            userPrompt: String,
            maxTokens: Long,
            onToken: (String) -> Unit,
        ) {
            generateCalls += userPrompt
            if (generateShouldFail) {
                throw ClaudeClientException("falha simulada do FakeClaudeClient")
            }
            generateTokens.forEach(onToken)
        }
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var bookRepository: BookRepository

    @Autowired
    lateinit var bookVersionRepository: BookVersionRepository

    @Autowired
    lateinit var chunkRepository: ChunkRepository

    @Autowired
    lateinit var fakeClaudeClient: FakeClaudeClient

    @BeforeEach
    fun resetFakes() {
        fakeClaudeClient.reset()
    }

    private fun persistBookWithChunk(text: String): Book {
        val suffix = UUID.randomUUID()
        val book = bookRepository.save(Book(id = "livro-$suffix", title = "Livro $suffix"))
        val version =
            bookVersionRepository.save(
                BookVersion(
                    id = UUID.randomUUID(),
                    bookId = book.id,
                    fileHash =
                        suffix
                            .toString()
                            .replace("-", "")
                            .repeat(2)
                            .take(64),
                    embeddingModel = "voyage-3",
                    embeddingModelVersion = "v1",
                    status = BookVersionStatus.READY,
                ),
            )
        book.activeVersionId = version.id
        val activatedBook = bookRepository.save(book)
        chunkRepository.save(
            Chunk(
                id = UUID.randomUUID(),
                bookVersionId = version.id,
                page = 1,
                charOffset = 0,
                tokenCount = 10,
                text = text,
                embedding = oneHotEmbedding(0),
            ),
        )
        return activatedBook
    }

    private fun chatRequestJson(
        query: String,
        bookIds: Set<String>? = null,
        conversationId: UUID? = null,
    ): String {
        val booksPart = bookIds?.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        val booksField = booksPart?.let { "\"bookIds\":$it," } ?: ""
        val conversationField = conversationId?.let { "\"conversationId\":\"$it\"," } ?: ""
        return "{$conversationField$booksField\"query\":\"$query\"}"
    }

    /** Extrai o valor de `data:` do primeiro `event:conversation` do corpo SSE (ver KDoc da classe). */
    private fun extractConversationId(body: String): UUID {
        val marker = "event:conversation\ndata:"
        val start = body.indexOf(marker)
        require(start >= 0) { "esperava event:conversation no corpo:\n$body" }
        val idStart = start + marker.length
        val idEnd = body.indexOf('\n', idStart)
        return UUID.fromString(body.substring(idStart, idEnd))
    }

    private fun awaitSseCompletion(
        result: MvcResult,
        timeoutMillis: Long = 5_000,
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val body = result.response.contentAsString
            if (body.contains("event:done") || body.contains("event:error")) {
                return body
            }
            Thread.sleep(20)
        }
        error("timeout esperando o SSE completar: ${result.response.contentAsString}")
    }

    @Test
    fun `requisicao sem X-Api-Key valida nunca chega ao controller e volta 401`() {
        val result =
            mockMvc
                .perform(
                    post("/chat")
                        .header("X-Api-Key", "chave-errada")
                        .header("X-Device-Id", "device-${UUID.randomUUID()}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatRequestJson("pergunta qualquer")),
                ).andReturn()

        assertEquals(401, result.response.status)
        assertTrue(fakeClaudeClient.rewriteCalls.isEmpty())
        assertTrue(fakeClaudeClient.generateCalls.isEmpty())
    }

    @Test
    fun `requisicao sem X-Device-Id devolve 400 sem chamar GenerationService`() {
        val result =
            mockMvc
                .perform(
                    post("/chat")
                        .header("X-Api-Key", VALID_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatRequestJson("pergunta qualquer")),
                ).andReturn()

        assertEquals(400, result.response.status)
        assertTrue(fakeClaudeClient.rewriteCalls.isEmpty())
        assertTrue(fakeClaudeClient.generateCalls.isEmpty())
    }

    @Test
    fun `conversa nova recebe event conversation antes de qualquer event token, seguido de event done`() {
        val book = persistBookWithChunk("Bentinho e Capitu se conhecem quando crianças.")

        val result =
            mockMvc
                .perform(
                    post("/chat")
                        .header("X-Api-Key", VALID_API_KEY)
                        .header("X-Device-Id", "device-${UUID.randomUUID()}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatRequestJson("quem é Capitu?", setOf(book.id))),
                ).andReturn()

        val body = awaitSseCompletion(result)

        val conversationIndex = body.indexOf("event:conversation")
        val firstTokenIndex = body.indexOf("event:token")
        val firstTokenDataIndex = body.indexOf("data:Resposta ")
        val secondTokenDataIndex = body.indexOf("data:completa.")
        val doneIndex = body.indexOf("event:done")

        assertTrue(conversationIndex >= 0, body)
        assertTrue(firstTokenIndex >= 0, body)
        assertTrue(conversationIndex < firstTokenIndex, "conversation deveria vir antes do primeiro token:\n$body")
        assertTrue(firstTokenDataIndex in 0..<secondTokenDataIndex, "deltas deveriam chegar na ordem certa:\n$body")
        assertTrue(secondTokenDataIndex < doneIndex, "done deveria vir depois de todos os tokens:\n$body")
    }

    @Test
    fun `conversa existente nao recebe event conversation, so tokens seguidos de done`() {
        val book = persistBookWithChunk("Bentinho e Capitu se conhecem quando crianças.")
        val deviceId = "device-${UUID.randomUUID()}"

        val firstResult =
            mockMvc
                .perform(
                    post("/chat")
                        .header("X-Api-Key", VALID_API_KEY)
                        .header("X-Device-Id", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatRequestJson("quem é Capitu?", setOf(book.id))),
                ).andReturn()
        val conversationId = extractConversationId(awaitSseCompletion(firstResult))

        val secondResult =
            mockMvc
                .perform(
                    post("/chat")
                        .header("X-Api-Key", VALID_API_KEY)
                        .header("X-Device-Id", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatRequestJson("e sobre o pai dela?", setOf(book.id), conversationId)),
                ).andReturn()
        val secondBody = awaitSseCompletion(secondResult)

        assertFalse(secondBody.contains("event:conversation"), secondBody)
        assertTrue(secondBody.contains("event:token"), secondBody)
        assertTrue(secondBody.contains("event:done"), secondBody)
    }

    @Test
    fun `falha no ClaudeClient produz event error sem event done`() {
        val book = persistBookWithChunk("Trecho qualquer sobre um personagem.")
        fakeClaudeClient.generateShouldFail = true

        val result =
            mockMvc
                .perform(
                    post("/chat")
                        .header("X-Api-Key", VALID_API_KEY)
                        .header("X-Device-Id", "device-${UUID.randomUUID()}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatRequestJson("pergunta que vai falhar", setOf(book.id))),
                ).andReturn()

        val body = awaitSseCompletion(result)

        assertTrue(body.contains("event:error"), body)
        assertFalse(body.contains("event:done"), body)
    }

    /**
     * Regressão de bug de produção descoberto na T8 (`specs/geracao/tasks.md`): sem um `Content-Type`
     * declarado com charset explícito, `StringHttpMessageConverter` caía no charset default
     * (ISO-8859-1), corrompendo qualquer acento em português na resposta SSE. Usa
     * `RetrievalResult.NoRelevantContext` (escopo com um `bookId` inexistente, mesmo padrão de
     * `GenerationServiceTest.noRelevantContextScope`) para forçar a mensagem fixa
     * `GenerationService.NO_RELEVANT_CONTEXT_MESSAGE`, que já contém acentuação ("não",
     * "reformulá-la") — decodificar os bytes crus como UTF-8 só reproduz o texto original
     * exatamente se o servidor de fato escreveu em UTF-8.
     */
    @Test
    fun `resposta SSE declara charset UTF-8 e preserva acentuacao`() {
        val result =
            mockMvc
                .perform(
                    post("/chat")
                        .header("X-Api-Key", VALID_API_KEY)
                        .header("X-Device-Id", "device-${UUID.randomUUID()}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            chatRequestJson(
                                "pergunta sem contexto",
                                setOf("livro-inexistente-${UUID.randomUUID()}"),
                            ),
                        ),
                ).andReturn()

        awaitSseCompletion(result)

        val contentType = result.response.contentType
        assertTrue(
            contentType != null && contentType.uppercase().contains("UTF-8"),
            "Content-Type deveria declarar charset=UTF-8 explicitamente: $contentType",
        )

        val bodyUtf8 = String(result.response.contentAsByteArray, StandardCharsets.UTF_8)
        assertTrue(
            bodyUtf8.contains(GenerationService.NO_RELEVANT_CONTEXT_MESSAGE),
            "corpo decodificado como UTF-8 deveria conter a mensagem fixa intacta, sem acentos corrompidos:\n$bodyUtf8",
        )
    }
}
