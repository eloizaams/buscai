package com.buscai.backend.generation

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val VALID_API_KEY = "generation-acceptance-test-key"
private const val FAKE_LEAKED_SECRET = "sk-ant-FAKE-SECRET-NUNCA-DEVERIA-APARECER"

/**
 * Teste de aceite de ponta a ponta via HTTP (`specs/geracao/tasks.md`, T8) — um cenário por
 * critério de aceite (CA1-CA11, exceto CA8, coberto em [GenerationRateLimitAcceptanceTest] por
 * precisar de uma configuração de rate limit própria que não deve vazar para os demais testes
 * desta classe). Mesmo padrão de dependências reais (Testcontainers Postgres) e fakes
 * ([FakeQueryEmbeddingClient]/[FakeClaudeClient]) já usado por `ChatControllerTest`/
 * `ConversationControllerTest`/`GenerationServiceTest` — aqui o pipeline inteiro é exercitado só
 * por HTTP (`POST /chat`, `GET /conversations/{id}`), nunca chamando `GenerationService`
 * diretamente.
 */
@Testcontainers
@ActiveProfiles("testcontainers")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["buscai.api-key=$VALID_API_KEY"])
class GenerationAcceptanceTest {
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

    /**
     * Ver KDoc da classe — substitui `VoyageEmbeddingClient`: todo chunk/pergunta usa o mesmo vetor
     * one-hot, garantindo similaridade máxima. [embedCallCount] (mesmo padrão de
     * `FakeClaudeClient.rewriteCalls`/`generateCalls`) permite provar, no CA5, que uma requisição
     * barrada por `ApiKeyFilter` nunca chega a gastar uma chamada de embedding (Voyage), não só a
     * de Claude.
     */
    class FakeQueryEmbeddingClient : EmbeddingClient {
        var embedCallCount: Int = 0

        override fun embed(
            texts: List<String>,
            inputType: EmbeddingInputType,
        ): List<FloatArray> {
            embedCallCount++
            return texts.map { oneHotEmbedding(0) }
        }
    }

    /**
     * Fake determinístico de [ClaudeClient], sem chamar a Anthropic real. [generateFailAfterTokens]
     * permite simular uma falha **no meio** do stream (CA10): os primeiros tokens são entregues via
     * `onToken` normalmente, só então a exceção é lançada.
     */
    class FakeClaudeClient : ClaudeClient {
        data class RewriteCall(
            val query: String,
            val history: List<HistoryTurn>,
        )

        data class GenerateCall(
            val systemPrompt: String,
            val userPrompt: String,
            val maxTokens: Long,
        )

        val rewriteCalls = mutableListOf<RewriteCall>()
        val generateCalls = mutableListOf<GenerateCall>()
        var rewriteResult: String = "pergunta reescrita"
        var generateTokens: List<String> = listOf("Resposta ", "completa.")
        var generateShouldFail: Boolean = false
        var generateFailAfterTokens: Int = 0
        var generateFailureMessage: String = "falha simulada do FakeClaudeClient"

        fun reset() {
            rewriteCalls.clear()
            generateCalls.clear()
            rewriteResult = "pergunta reescrita"
            generateTokens = listOf("Resposta ", "completa.")
            generateShouldFail = false
            generateFailAfterTokens = 0
            generateFailureMessage = "falha simulada do FakeClaudeClient"
        }

        override fun rewriteQuery(
            query: String,
            history: List<HistoryTurn>,
        ): String {
            rewriteCalls += RewriteCall(query, history)
            return rewriteResult
        }

        override fun generate(
            systemPrompt: String,
            userPrompt: String,
            maxTokens: Long,
            onToken: (String) -> Unit,
        ) {
            generateCalls += GenerateCall(systemPrompt, userPrompt, maxTokens)
            if (generateShouldFail) {
                generateTokens.take(generateFailAfterTokens).forEach(onToken)
                throw ClaudeClientException(generateFailureMessage)
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

    @Autowired
    lateinit var fakeQueryEmbeddingClient: FakeQueryEmbeddingClient

    @BeforeEach
    fun resetFakes() {
        fakeClaudeClient.reset()
        fakeQueryEmbeddingClient.embedCallCount = 0
    }

    private fun persistBookWithChunk(
        text: String,
        page: Int = 1,
    ): Book {
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
                page = page,
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

    private fun postChat(
        deviceId: String,
        query: String,
        bookIds: Set<String>? = null,
        conversationId: UUID? = null,
        apiKey: String = VALID_API_KEY,
    ): MvcResult =
        mockMvc
            .perform(
                post("/chat")
                    .header("X-Api-Key", apiKey)
                    .header("X-Device-Id", deviceId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(chatRequestJson(query, bookIds, conversationId)),
            ).andReturn()

    /** Extrai o valor de `data:` do primeiro `event:conversation` do corpo SSE. */
    private fun extractConversationId(body: String): UUID {
        val marker = "event:conversation\ndata:"
        val start = body.indexOf(marker)
        require(start >= 0) { "esperava event:conversation no corpo:\n$body" }
        val idStart = start + marker.length
        val idEnd = body.indexOf('\n', idStart)
        return UUID.fromString(body.substring(idStart, idEnd))
    }

    /** Extrai, em ordem, o valor de `data:` de cada `event:token` do corpo SSE. */
    private fun extractTokens(body: String): List<String> {
        val marker = "event:token\ndata:"
        val tokens = mutableListOf<String>()
        var searchFrom = 0
        while (true) {
            val start = body.indexOf(marker, searchFrom)
            if (start < 0) break
            val dataStart = start + marker.length
            val dataEnd = body.indexOf('\n', dataStart)
            tokens += body.substring(dataStart, dataEnd)
            searchFrom = dataEnd
        }
        return tokens
    }

    // Força UTF-8 explicitamente na leitura, independente do charset já declarado pelo
    // ChatController (Content-Type: text/event-stream;charset=UTF-8) — defensivo, não workaround.
    private fun MvcResult.bodyUtf8(): String = response.getContentAsString(StandardCharsets.UTF_8)

    private fun awaitSseCompletion(
        result: MvcResult,
        timeoutMillis: Long = 5_000,
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val body = result.bodyUtf8()
            if (body.contains("event:done") || body.contains("event:error")) {
                return body
            }
            Thread.sleep(20)
        }
        error("timeout esperando o SSE completar: ${result.bodyUtf8()}")
    }

    @Test
    fun `CA1 - pergunta com contexto relevante produz resposta citando livro, nunca pagina`() {
        val book = persistBookWithChunk("Bentinho e Capitu se conhecem quando crianças.", page = 42)
        fakeClaudeClient.generateTokens = listOf("Conforme o livro, ", "Bentinho e Capitu se conheciam.")

        val result = postChat("device-${UUID.randomUUID()}", "quem é Capitu?", setOf(book.id))
        val body = awaitSseCompletion(result)

        val generateCall = fakeClaudeClient.generateCalls.single()
        assertTrue(generateCall.userPrompt.contains(book.title), generateCall.userPrompt)
        assertFalse(generateCall.userPrompt.contains("p. 42"), generateCall.userPrompt)

        val tokens = extractTokens(body)
        assertEquals(fakeClaudeClient.generateTokens, tokens, "a resposta do fake deveria ser entregue integralmente ao cliente")
        assertTrue(body.contains("event:done"), body)
    }

    @Test
    fun `CA2 - pergunta sem contexto relevante produz a mensagem fixa de sem fundamento sem chamar generate`() {
        // bookId nunca persistido: RetrievalScope.Books com ele nunca contribui uma versão elegível
        // (RetrievalScope, `specs/retrieval/`), levando a RetrievalResult.NoRelevantContext.
        val inexistentBookId = "livro-inexistente-${UUID.randomUUID()}"

        val result = postChat("device-${UUID.randomUUID()}", "pergunta sem contexto", setOf(inexistentBookId))
        val body = awaitSseCompletion(result)

        assertTrue(fakeClaudeClient.generateCalls.isEmpty(), "não deveria chamar generate quando NoRelevantContext")
        val tokens = extractTokens(body)
        assertEquals(listOf(GenerationService.NO_RELEVANT_CONTEXT_MESSAGE), tokens, body)
        assertTrue(body.contains("event:done"), body)
    }

    @Test
    fun `CA3 - resposta chega em streaming, multiplos event token antes de event done`() {
        val book = persistBookWithChunk("Trecho qualquer para forçar múltiplos deltas.")
        fakeClaudeClient.generateTokens = listOf("primeiro ", "segundo ", "terceiro.")

        val result = postChat("device-${UUID.randomUUID()}", "pergunta qualquer", setOf(book.id))
        val body = awaitSseCompletion(result)

        val tokens = extractTokens(body)
        assertEquals(listOf("primeiro ", "segundo ", "terceiro."), tokens, body)
        assertTrue(tokens.size > 1, "esperava mais de um evento token, entrega não deveria ser um bloco único:\n$body")
        assertTrue(body.indexOf("terceiro.") < body.indexOf("event:done"), "todos os tokens deveriam vir antes de event:done:\n$body")
    }

    @Test
    fun `CA4 - segunda pergunta na mesma conversa aciona rewrite e usa o historico`() {
        val book = persistBookWithChunk("Texto de contexto compartilhado pelas duas perguntas.")
        val deviceId = "device-${UUID.randomUUID()}"

        val firstResult = postChat(deviceId, "primeira pergunta", setOf(book.id))
        val conversationId = extractConversationId(awaitSseCompletion(firstResult))
        assertTrue(fakeClaudeClient.rewriteCalls.isEmpty(), "conversa nova não deveria chamar rewriteQuery")

        val secondResult = postChat(deviceId, "e sobre isso?", setOf(book.id), conversationId)
        awaitSseCompletion(secondResult)

        assertEquals(1, fakeClaudeClient.rewriteCalls.size)
        val rewriteCall = fakeClaudeClient.rewriteCalls.single()
        assertEquals("e sobre isso?", rewriteCall.query)
        assertTrue(rewriteCall.history.isNotEmpty(), "rewrite deveria usar o histórico da conversa")
    }

    @Test
    fun `CA5 - requisicao sem X-Api-Key valida e barrada antes de qualquer chamada ao ClaudeClient ou ao embedding`() {
        val result = postChat("device-${UUID.randomUUID()}", "pergunta qualquer", apiKey = "chave-errada")

        assertEquals(401, result.response.status)
        assertTrue(fakeClaudeClient.rewriteCalls.isEmpty())
        assertTrue(fakeClaudeClient.generateCalls.isEmpty())
        assertEquals(0, fakeQueryEmbeddingClient.embedCallCount, "nao deveria gastar credito de embedding (Voyage) sem X-Api-Key valido")
    }

    @Test
    fun `CA6 - escopo de livros e respeitado, resposta nunca se fundamenta em livro fora do escopo`() {
        val bookA = persistBookWithChunk("Trecho do livro A sobre o tema perguntado.")
        val bookB = persistBookWithChunk("Trecho do livro B, igualmente relevante, mas fora do escopo pedido.")

        val result = postChat("device-${UUID.randomUUID()}", "pergunta sobre o tema", setOf(bookA.id))
        awaitSseCompletion(result)

        val generateCall = fakeClaudeClient.generateCalls.single()
        assertTrue(generateCall.userPrompt.contains(bookA.title), generateCall.userPrompt)
        assertFalse(generateCall.userPrompt.contains(bookB.title), generateCall.userPrompt)
    }

    @Test
    fun `CA7 - reabrir conversa recupera as perguntas e respostas trocadas na ordem certa`() {
        val book = persistBookWithChunk("Texto usado nas duas perguntas da conversa.")
        val deviceId = "device-${UUID.randomUUID()}"

        val firstResult = postChat(deviceId, "pergunta um", setOf(book.id))
        val conversationId = extractConversationId(awaitSseCompletion(firstResult))

        fakeClaudeClient.generateTokens = listOf("resposta dois.")
        val secondResult = postChat(deviceId, "pergunta dois", setOf(book.id), conversationId)
        awaitSseCompletion(secondResult)

        mockMvc
            .perform(
                get("/conversations/$conversationId")
                    .header("X-Api-Key", VALID_API_KEY)
                    .header("X-Device-Id", deviceId),
            ).andExpect(jsonPath("$.messages.length()").value(4))
            .andExpect(jsonPath("$.messages[0].role").value("USER"))
            .andExpect(jsonPath("$.messages[0].content").value("pergunta um"))
            .andExpect(jsonPath("$.messages[1].role").value("ASSISTANT"))
            .andExpect(jsonPath("$.messages[2].role").value("USER"))
            .andExpect(jsonPath("$.messages[2].content").value("pergunta dois"))
            .andExpect(jsonPath("$.messages[3].role").value("ASSISTANT"))
            .andExpect(jsonPath("$.messages[3].content").value("resposta dois."))
    }

    @Test
    fun `CA9 - nenhuma chave de API aparece em nenhuma resposta de erro`() {
        val result401 = postChat("device-${UUID.randomUUID()}", "pergunta qualquer", apiKey = "chave-errada")
        assertEquals(401, result401.response.status)
        assertFalse(result401.bodyUtf8().contains(VALID_API_KEY), result401.bodyUtf8())

        val book = persistBookWithChunk("Trecho qualquer.")
        fakeClaudeClient.generateShouldFail = true
        fakeClaudeClient.generateFailureMessage = "erro interno vazando segredo: $FAKE_LEAKED_SECRET"

        val result = postChat("device-${UUID.randomUUID()}", "pergunta que vai falhar", setOf(book.id))
        val body = awaitSseCompletion(result)

        assertTrue(body.contains("event:error"), body)
        assertFalse(body.contains(FAKE_LEAKED_SECRET), body)
        assertFalse(body.contains(VALID_API_KEY), body)
    }

    @Test
    fun `CA10 - falha simulada no meio do stream produz event error sem persistir resposta parcial`() {
        val book = persistBookWithChunk("Trecho qualquer sobre um personagem.")
        val deviceId = "device-${UUID.randomUUID()}"

        val firstResult = postChat(deviceId, "pergunta inicial", setOf(book.id))
        val conversationId = extractConversationId(awaitSseCompletion(firstResult))

        fakeClaudeClient.generateTokens = listOf("parcial um ", "parcial dois ", "nunca deveria persistir")
        fakeClaudeClient.generateShouldFail = true
        fakeClaudeClient.generateFailAfterTokens = 2
        fakeClaudeClient.generateFailureMessage = "falha simulada no meio do stream"

        val secondResult = postChat(deviceId, "pergunta que vai falhar no meio", setOf(book.id), conversationId)
        val body = awaitSseCompletion(secondResult)

        assertTrue(body.contains("event:token"), body)
        assertTrue(body.contains("event:error"), body)
        assertFalse(body.contains("event:done"), body)

        val detail =
            mockMvc
                .perform(
                    get("/conversations/$conversationId")
                        .header("X-Api-Key", VALID_API_KEY)
                        .header("X-Device-Id", deviceId),
                ).andReturn()
        val detailBody = detail.bodyUtf8()

        assertFalse(detailBody.contains("parcial"), detailBody)
        mockMvc
            .perform(
                get("/conversations/$conversationId")
                    .header("X-Api-Key", VALID_API_KEY)
                    .header("X-Device-Id", deviceId),
            ).andExpect(jsonPath("$.messages.length()").value(3))
            .andExpect(jsonPath("$.messages[2].role").value("USER"))
            .andExpect(jsonPath("$.messages[2].content").value("pergunta que vai falhar no meio"))
    }

    @Test
    fun `CA11 - pergunta sem conversationId inicia conversa nova, com conversationId continua a existente`() {
        val book = persistBookWithChunk("Trecho qualquer usado nas duas perguntas.")
        val deviceId = "device-${UUID.randomUUID()}"

        val firstResult = postChat(deviceId, "pergunta inicial", setOf(book.id))
        val firstBody = awaitSseCompletion(firstResult)
        assertTrue(firstBody.contains("event:conversation"), firstBody)
        val conversationId = extractConversationId(firstBody)

        val secondResult = postChat(deviceId, "pergunta de continuacao", setOf(book.id), conversationId)
        val secondBody = awaitSseCompletion(secondResult)
        assertFalse(secondBody.contains("event:conversation"), secondBody)

        mockMvc
            .perform(
                get("/conversations/$conversationId")
                    .header("X-Api-Key", VALID_API_KEY)
                    .header("X-Device-Id", deviceId),
            ).andExpect(jsonPath("$.messages.length()").value(4))
    }
}
