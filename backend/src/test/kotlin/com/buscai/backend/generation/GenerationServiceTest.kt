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
import com.buscai.backend.embedding.EmbeddingClientException
import com.buscai.backend.embedding.EmbeddingInputType
import com.buscai.backend.generation.claude.ClaudeClient
import com.buscai.backend.generation.claude.ClaudeClientException
import com.buscai.backend.generation.claude.HistoryTurn
import com.buscai.backend.generation.conversation.ConversationRepository
import com.buscai.backend.generation.conversation.MessageRepository
import com.buscai.backend.generation.conversation.MessageRole
import com.buscai.backend.retrieval.RetrievalScope
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Testes de ponta a ponta de [GenerationService] (`specs/geracao/tasks.md`, T4) contra um Postgres
 * real via Testcontainers — mesmo padrão de `RetrievalServiceIntegrationTest`/
 * `ConversationRepositoriesIntegrationTest`, já que `RetrievalService` (consumido aqui como está)
 * depende de pgvector/tsvector reais. [FakeQueryEmbeddingClient] substitui a Voyage real (vetor
 * "one-hot" determinístico) e [FakeClaudeClient] substitui a Claude real, permitindo controlar
 * rewrite/generate sem chamada de rede.
 */
@Testcontainers
@ActiveProfiles("testcontainers")
@SpringBootTest
class GenerationServiceTest {
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
     * Ver KDoc da classe — substitui `VoyageEmbeddingClient`, registrando os textos embeddados
     * para provar qual pergunta (original/reescrita) chegou ao retrieval. [shouldFail] simula uma
     * falha do `RetrievalService` (erro de embedding, `specs/retrieval/plan.md`) sem precisar
     * mockar `RetrievalService` diretamente — a falha ocorre dentro da própria chamada real de
     * `RetrievalService.search`.
     */
    class FakeQueryEmbeddingClient : EmbeddingClient {
        val receivedTexts = mutableListOf<String>()
        var shouldFail: Boolean = false

        override fun embed(
            texts: List<String>,
            inputType: EmbeddingInputType,
        ): List<FloatArray> {
            if (shouldFail) {
                throw EmbeddingClientException("falha simulada do FakeQueryEmbeddingClient")
            }
            receivedTexts += texts
            return texts.map { oneHotEmbedding(0) }
        }
    }

    /** Fake determinístico de [ClaudeClient], sem chamar a Anthropic real. */
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
        var rewriteShouldFail: Boolean = false
        var generateTokens: List<String> = listOf("Resposta ", "completa.")
        var generateShouldFail: Boolean = false

        fun reset() {
            rewriteCalls.clear()
            generateCalls.clear()
            rewriteResult = "pergunta reescrita"
            rewriteShouldFail = false
            generateTokens = listOf("Resposta ", "completa.")
            generateShouldFail = false
        }

        override fun rewriteQuery(
            query: String,
            history: List<HistoryTurn>,
        ): String {
            rewriteCalls += RewriteCall(query, history)
            if (rewriteShouldFail) {
                throw ClaudeClientException("falha simulada do FakeClaudeClient (rewriteQuery)")
            }
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
                throw ClaudeClientException("falha simulada do FakeClaudeClient")
            }
            generateTokens.forEach(onToken)
        }
    }

    @Autowired
    lateinit var generationService: GenerationService

    @Autowired
    lateinit var conversationRepository: ConversationRepository

    @Autowired
    lateinit var messageRepository: MessageRepository

    @Autowired
    lateinit var bookRepository: BookRepository

    @Autowired
    lateinit var bookVersionRepository: BookVersionRepository

    @Autowired
    lateinit var chunkRepository: ChunkRepository

    @Autowired
    lateinit var fakeQueryEmbeddingClient: FakeQueryEmbeddingClient

    @Autowired
    lateinit var fakeClaudeClient: FakeClaudeClient

    @BeforeEach
    fun resetFakes() {
        fakeQueryEmbeddingClient.receivedTexts.clear()
        fakeQueryEmbeddingClient.shouldFail = false
        fakeClaudeClient.reset()
    }

    private fun persistBookWithChunk(
        suffix: UUID,
        text: String,
        page: Int = 1,
    ): Book {
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

    private fun noRelevantContextScope(): RetrievalScope = RetrievalScope.Books(setOf("livro-inexistente-${UUID.randomUUID()}"))

    @Test
    fun `conversa nova nao chama rewriteQuery`() {
        val deviceId = "device-${UUID.randomUUID()}"
        val suffix = UUID.randomUUID()
        val book = persistBookWithChunk(suffix, "Trecho de controle para a primeira pergunta.")

        generationService.answer(deviceId, null, "primeira pergunta", RetrievalScope.Books(setOf(book.id)))

        assertTrue(fakeClaudeClient.rewriteCalls.isEmpty(), "conversa nova não deveria chamar rewriteQuery")
        assertEquals(listOf("primeira pergunta"), fakeQueryEmbeddingClient.receivedTexts)
    }

    @Test
    fun `conversa com historico chama rewriteQuery antes do retrieval e usa a pergunta reescrita`() {
        val deviceId = "device-${UUID.randomUUID()}"
        val suffix = UUID.randomUUID()
        // Precisa de um bookId elegível (com versão READY) para que RetrievalService de fato chame
        // o EmbeddingClient — um scope sem versão elegível (RetrievalScope.Books de bookId
        // inexistente) devolve NoRelevantContext *antes* de embeddar a query, o que impediria provar
        // aqui que a pergunta reescrita (e não a original) chegou ao retrieval.
        val book = persistBookWithChunk(suffix, "Trecho de controle usado nas duas perguntas.")
        val scope = RetrievalScope.Books(setOf(book.id))

        val first = generationService.answer(deviceId, null, "pergunta original 1", scope)
        fakeQueryEmbeddingClient.receivedTexts.clear()

        fakeClaudeClient.rewriteResult = "pergunta sobre termoReescrito"
        generationService.answer(deviceId, first.conversationId, "e sobre isso?", scope)

        assertEquals(1, fakeClaudeClient.rewriteCalls.size)
        assertEquals("e sobre isso?", fakeClaudeClient.rewriteCalls.single().query)
        assertTrue(
            fakeClaudeClient.rewriteCalls
                .single()
                .history
                .isNotEmpty(),
            "histórico deveria conter o turno anterior",
        )

        assertEquals(listOf("pergunta sobre termoReescrito"), fakeQueryEmbeddingClient.receivedTexts)
    }

    @Test
    fun `NoRelevantContext produz a mensagem fixa sem chamar generate`() {
        val deviceId = "device-${UUID.randomUUID()}"

        val result = generationService.answer(deviceId, null, "pergunta sem contexto", noRelevantContextScope())

        assertEquals(GenerationService.NO_RELEVANT_CONTEXT_MESSAGE, result.text)
        assertTrue(fakeClaudeClient.generateCalls.isEmpty(), "não deveria chamar generate quando NoRelevantContext")

        val messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(result.conversationId)
        assertEquals(2, messages.size)
        assertEquals(MessageRole.ASSISTANT, messages[1].role)
        assertEquals(GenerationService.NO_RELEVANT_CONTEXT_MESSAGE, messages[1].content)
    }

    @Test
    fun `Found monta o prompt esperado e persiste a resposta completa acumulada dos deltas`() {
        val deviceId = "device-${UUID.randomUUID()}"
        val suffix = UUID.randomUUID()
        val book = persistBookWithChunk(suffix, "Bentinho e Capitu se conhecem quando crianças.", page = 42)

        val result =
            generationService.answer(
                deviceId,
                null,
                "quem é Capitu?",
                RetrievalScope.Books(setOf(book.id)),
            )

        assertEquals(1, fakeClaudeClient.generateCalls.size)
        val generateCall = fakeClaudeClient.generateCalls.single()
        assertTrue(generateCall.userPrompt.contains(book.title), generateCall.userPrompt)
        assertTrue(generateCall.userPrompt.contains("p. 42"), generateCall.userPrompt)
        assertTrue(generateCall.userPrompt.contains("Bentinho e Capitu"), generateCall.userPrompt)
        assertTrue(generateCall.userPrompt.contains("quem é Capitu?"), generateCall.userPrompt)
        assertEquals(2048L, generateCall.maxTokens)

        assertEquals("Resposta completa.", result.text)

        val messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(result.conversationId)
        assertEquals(2, messages.size)
        assertEquals(MessageRole.ASSISTANT, messages[1].role)
        assertEquals("Resposta completa.", messages[1].content)
    }

    @Test
    fun `excecao do ClaudeClient durante generate nao persiste mensagem de assistente, mas a pergunta ja esta persistida`() {
        val deviceId = "device-${UUID.randomUUID()}"
        val suffix = UUID.randomUUID()
        val book = persistBookWithChunk(suffix, "Trecho qualquer sobre um personagem.", page = 7)

        val first = generationService.answer(deviceId, null, "pergunta inicial sem contexto", noRelevantContextScope())
        assertEquals(2, messageRepository.findByConversationIdOrderByCreatedAtAsc(first.conversationId).size)

        fakeClaudeClient.generateShouldFail = true
        assertFailsWith<ClaudeClientException> {
            generationService.answer(
                deviceId,
                first.conversationId,
                "pergunta que vai falhar",
                RetrievalScope.Books(setOf(book.id)),
            )
        }

        val messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(first.conversationId)
        // 2 mensagens do primeiro turno (pergunta + resposta fixa) + a pergunta do segundo turno,
        // persistida antes de chamar generate — nenhuma linha de assistente do segundo turno.
        assertEquals(3, messages.size)
        assertEquals(MessageRole.USER, messages[2].role)
        assertEquals("pergunta que vai falhar", messages[2].content)
        assertTrue(messages.none { it.content.contains("Resposta") }, "não deveria haver resposta de assistente para o turno que falhou")
    }

    @Test
    fun `excecao do ClaudeClient durante rewriteQuery nao persiste mensagem de assistente, mas a pergunta ja esta persistida`() {
        val deviceId = "device-${UUID.randomUUID()}"

        // Primeiro turno bem-sucedido (NoRelevantContext) só para existir histórico — rewriteQuery
        // só roda quando já há histórico (GenerationService, `specs/geracao/plan.md`).
        val first = generationService.answer(deviceId, null, "pergunta inicial sem contexto", noRelevantContextScope())
        assertEquals(2, messageRepository.findByConversationIdOrderByCreatedAtAsc(first.conversationId).size)

        fakeClaudeClient.rewriteShouldFail = true
        assertFailsWith<ClaudeClientException> {
            generationService.answer(
                deviceId,
                first.conversationId,
                "pergunta cujo rewrite vai falhar",
                noRelevantContextScope(),
            )
        }

        val messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(first.conversationId)
        // 2 mensagens do primeiro turno (pergunta + resposta fixa) + a pergunta do segundo turno,
        // persistida antes de chamar rewriteQuery — nenhuma linha de assistente do segundo turno,
        // e o retrieval/generate nunca chegam a rodar (rewrite falha antes deles).
        assertEquals(3, messages.size)
        assertEquals(MessageRole.USER, messages[2].role)
        assertEquals("pergunta cujo rewrite vai falhar", messages[2].content)
        assertEquals(1, messages.count { it.role == MessageRole.ASSISTANT })
        assertTrue(fakeClaudeClient.generateCalls.isEmpty(), "generate não deveria ser chamado quando rewriteQuery falha")
    }

    @Test
    fun `excecao do RetrievalService durante search nao persiste mensagem de assistente, mas a pergunta ja esta persistida`() {
        val deviceId = "device-${UUID.randomUUID()}"
        val suffix = UUID.randomUUID()
        val book = persistBookWithChunk(suffix, "Trecho de controle para o primeiro turno.")
        val scope = RetrievalScope.Books(setOf(book.id))

        // Primeiro turno bem-sucedido (Found), usando o FakeClaudeClient normalmente, só para
        // existir histórico e conhecer o conversationId — esse turno já chama generate uma vez.
        val first = generationService.answer(deviceId, null, "pergunta inicial", scope)
        assertEquals(2, messageRepository.findByConversationIdOrderByCreatedAtAsc(first.conversationId).size)
        val generateCallsBeforeFailure = fakeClaudeClient.generateCalls.size

        // Simula uma falha do RetrievalService (erro de embedding, `specs/retrieval/plan.md`) sem
        // mockar RetrievalService diretamente — fazendo a própria dependência real dele
        // (EmbeddingClient) falhar.
        fakeQueryEmbeddingClient.shouldFail = true
        assertFailsWith<EmbeddingClientException> {
            generationService.answer(
                deviceId,
                first.conversationId,
                "pergunta que falha no retrieval",
                scope,
            )
        }

        val messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(first.conversationId)
        // 2 mensagens do primeiro turno (pergunta + resposta) + a pergunta do segundo turno,
        // persistida antes de chamar RetrievalService.search — nenhuma linha de assistente do
        // segundo turno.
        assertEquals(3, messages.size)
        assertEquals(MessageRole.USER, messages[2].role)
        assertEquals("pergunta que falha no retrieval", messages[2].content)
        assertEquals(1, messages.count { it.role == MessageRole.ASSISTANT })
        assertEquals(
            generateCallsBeforeFailure,
            fakeClaudeClient.generateCalls.size,
            "generate não deveria ser chamado de novo quando o retrieval falha (search roda antes de generate)",
        )
    }

    @Test
    fun `gravar uma Message atualiza Conversation updatedAt`() {
        val deviceId = "device-${UUID.randomUUID()}"

        val first = generationService.answer(deviceId, null, "pergunta 1", noRelevantContextScope())
        val afterFirst = conversationRepository.findById(first.conversationId).orElseThrow()

        Thread.sleep(5)

        generationService.answer(deviceId, first.conversationId, "pergunta 2", noRelevantContextScope())
        val afterSecond = conversationRepository.findById(first.conversationId).orElseThrow()

        assertTrue(
            afterSecond.updatedAt.isAfter(afterFirst.updatedAt),
            "updatedAt deveria avançar após novas mensagens: ${afterFirst.updatedAt} -> ${afterSecond.updatedAt}",
        )
    }
}
