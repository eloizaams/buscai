package com.buscai.backend

import com.buscai.backend.catalog.ChunkRepository
import com.buscai.backend.catalog.EMBEDDING_DIMENSIONS
import com.buscai.backend.embedding.EmbeddingClient
import com.buscai.backend.embedding.EmbeddingInputType
import com.buscai.backend.generation.claude.ClaudeClient
import com.buscai.backend.generation.claude.ClaudeClientException
import com.buscai.backend.generation.claude.HistoryTurn
import com.buscai.backend.ingestion.IngestionOutcome
import com.buscai.backend.ingestion.IngestionService
import com.buscai.backend.ingestion.chunking.ReferenceType
import com.buscai.backend.ingestion.pdf.PdfFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.util.UUID

private const val VALID_API_KEY = "referencia-estruturada-acceptance-test-key"

/** Filler ASCII determinístico — usado só para inflar a contagem de tokens de um parágrafo até o tamanho desejado, sem interferir na detecção de referência (nunca começa com dígito seguido de ponto). */
private fun words(range: IntRange): String = range.joinToString(" ") { "filler$it" }

/**
 * Item "157" isolado (ADR-0013, CA4): grande o bastante (prefixo + filler, ~674 tokens) para que o
 * `Chunker` (greedy, `MAX_OWN_CONTENT_TOKENS` ~695) feche o grupo sozinho assim que o próximo item
 * (158, ~34 tokens) não couber mais — nunca mistura dois itens no mesmo chunk. Sem acentuação/travessão
 * deliberadamente: `PdfFixtures.textPdf` renderiza via `Standard14Fonts.HELVETICA` (WinAnsiEncoding),
 * mesma convenção sem acento já usada por `IngestionServiceTest`/`ChunkerTest` para não depender de
 * mapeamento de glifo do PDFBox neste teste.
 */
private fun item157Text(): String =
    "157. Que e a morte? - E a destruicao do corpo mais grosseiro, o involucro que o espirito " +
        "abandona ao deixar a vida material. ${words(1..650)}"

/** Item "158", curto o bastante para se agrupar com o item "159" seguinte num único chunk (reference em intervalo). */
private fun item158Text(): String =
    "158. E como se da essa separacao entre o espirito e o corpo? - Ocorre quando os orgaos " +
        "ficam tao alterados que a vida material se torna impossivel de continuar, e o espirito parte."

/** Item "159", curto — junto com [item158Text] forma o par agrupado num só chunk (CA5, `reference` = "158-159"). */
private fun item159Text(): String =
    "159. Que sentimento acompanha esse momento? - Geralmente um alivio, semelhante ao de quem " +
        "se livra de uma veste pesada e incomoda que ja nao serve mais."

/** Livro ingerido sem `--reference-style` (CA6/CA7): um único parágrafo, ~338 tokens, sem nenhum marcador de item/capítulo. */
private fun noReferenceBookText(): String =
    "Trecho de um livro classico ingerido sem --reference-style, mantendo compatibilidade total " +
        "com o comportamento anterior a esta feature. ${words(1..320)}"

/**
 * Teste de aceite de ponta a ponta de `specs/referencia-estruturada/` (ADR-0013, `tasks.md` T7):
 * ingere via [IngestionService] diretamente (nunca via CLI) e pergunta via HTTP (`POST /chat`,
 * `ChatController`), cobrindo o pipeline inteiro — ingestão (chunking atômico por item, T1/T2),
 * retrieval (propagação de `reference`/`referenceType`, T3), geração (prompt por capítulo/item,
 * T4) e o evento SSE `event: sources` (T5).
 *
 * Mesmo padrão de dependências reais (Testcontainers Postgres) já usado por
 * `IngestionServiceTest`/`ChatControllerTest`/`GenerationAcceptanceTest`: [FakeEmbeddingClient]
 * substitui a Voyage real por um vetor one-hot único para todo texto (garante
 * `cosineSimilarity == 1.0` para qualquer par pergunta/chunk, sempre acima de
 * `RetrievalProperties.minCosineSimilarity`) — o cenário é controlado por escopo (`bookIds`) e por
 * ter poucos chunks por livro, não por semântica real do embedding (CA5 da spec: "sob as condições
 * controladas deste teste... não uma garantia geral de busca"). [FakeClaudeClient] substitui a
 * Claude real (mesmo padrão de `ChatControllerTest`).
 *
 * CA7 (`ingestão sem --reference-style continua idêntica ao comportamento anterior`) não é
 * reexercitado aqui — já coberto por `IngestionServiceTest` (T2, incluindo a asserção explícita de
 * `chunk.reference`/`referenceType` nulos sem a flag) e pela suíte pré-existente de
 * `specs/ingestao-pdf/`; esta classe só confirma, via [noReferenceBookText], que um chunk desse
 * tipo de livro aparece corretamente no `event: sources` (CA6).
 */
@Testcontainers
@ActiveProfiles("testcontainers")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["buscai.api-key=$VALID_API_KEY"])
class ReferenciaEstruturadaAcceptanceTest {
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
        fun fakeEmbeddingClient(): FakeEmbeddingClient = FakeEmbeddingClient()

        @Bean
        @Primary
        fun fakeClaudeClient(): FakeClaudeClient = FakeClaudeClient()
    }

    /**
     * Substitui o `EmbeddingClient` real tanto para a ingestão (`EmbeddingInputType.DOCUMENT`)
     * quanto para o retrieval (`EmbeddingInputType.QUERY`) — mesmo bean, mesmo vetor one-hot
     * determinístico para qualquer texto (ver KDoc da classe).
     */
    class FakeEmbeddingClient : EmbeddingClient {
        override fun embed(
            texts: List<String>,
            inputType: EmbeddingInputType,
        ): List<FloatArray> = texts.map { oneHotEmbedding(0) }
    }

    /** Fake determinístico de [ClaudeClient], sem chamar a Anthropic real (mesmo padrão de `ChatControllerTest`). */
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
    lateinit var ingestionService: IngestionService

    @Autowired
    lateinit var chunkRepository: ChunkRepository

    @Autowired
    lateinit var fakeClaudeClient: FakeClaudeClient

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun resetFakes() {
        fakeClaudeClient.reset()
    }

    private fun postChat(
        deviceId: String,
        query: String,
        bookIds: Set<String>,
    ): MvcResult {
        val booksJson = bookIds.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        val requestJson = "{\"bookIds\":$booksJson,\"query\":\"$query\"}"
        return mockMvc
            .perform(
                post("/chat")
                    .header("X-Api-Key", VALID_API_KEY)
                    .header("X-Device-Id", deviceId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestJson),
            ).andReturn()
    }

    /** Extrai o valor cru de `data:` do primeiro `event:$eventName` do corpo SSE (mesmo padrão de `ChatControllerTest`). */
    private fun extractEventData(
        body: String,
        eventName: String,
    ): String {
        val marker = "event:$eventName\ndata:"
        val start = body.indexOf(marker)
        require(start >= 0) { "esperava event:$eventName no corpo:\n$body" }
        val dataStart = start + marker.length
        val dataEnd = body.indexOf('\n', dataStart)
        return body.substring(dataStart, dataEnd)
    }

    private fun countOccurrences(
        body: String,
        substring: String,
    ): Int = body.split(substring).size - 1

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

    private fun sourceItems(sourcesArray: JsonNode): List<JsonNode> = (0 until sourcesArray.size()).map { sourcesArray[it] }

    @Test
    fun `CA1, CA3, CA4, CA5 - item isolado citado por numero exato e par de itens citado por intervalo, sem mencionar pagina`() {
        val suffix = UUID.randomUUID()
        val bookId = "livro-espiritos-$suffix"
        val file = PdfFixtures.textPdf(tempDir, listOf(item157Text(), item158Text(), item159Text()))

        val outcome =
            ingestionService.ingest(
                bookId = bookId,
                title = "O Livro dos Espíritos (fixture)",
                file = file,
                referenceType = ReferenceType.NUMBERED_ITEM,
            )
        val completed = outcome as? IngestionOutcome.Completed
        assertNotNull(completed, "esperava IngestionOutcome.Completed, obteve: $outcome")
        checkNotNull(completed)
        assertEquals(
            2,
            completed.chunkCount,
            "esperava exatamente 2 chunks: item 157 isolado + par 158-159 agrupado",
        )

        fakeClaudeClient.generateTokens =
            listOf(
                "Conforme O Livro dos Espíritos, item 157, ",
                "a morte é a destruição do corpo mais grosseiro.",
            )

        val result = postChat(deviceId = "device-$suffix", query = "O que e a morte?", bookIds = setOf(bookId))
        val body = awaitSseCompletion(result)

        // CA1: event:sources único, antes do primeiro event:token.
        assertEquals(1, countOccurrences(body, "event:sources"), body)
        val sourcesIndex = body.indexOf("event:sources")
        val firstTokenIndex = body.indexOf("event:token")
        assertTrue(sourcesIndex in 0..<firstTokenIndex, "sources deveria vir antes do primeiro token:\n$body")

        val sourcesArray = objectMapper.readTree(extractEventData(body, "sources")).get("sources")
        val sources = sourceItems(sourcesArray)
        assertEquals(2, sources.size, sourcesArray.toString())

        val isolatedSource = sources.single { it.get("reference").asString() == "157" }
        assertEquals(bookId, isolatedSource.get("bookId").asString())
        assertEquals("NUMBERED_ITEM", isolatedSource.get("referenceType").asString())
        // CA4: item isolado citado pelo número exato, nunca um intervalo (separador "–", ver Chunker.groupReference).
        assertFalse(isolatedSource.get("reference").asString().contains("–"), isolatedSource.toString())
        // CA5 (sob as condições controladas deste teste): a pergunta recupera o chunk do item 157.
        assertTrue(isolatedSource.get("text").asString().contains("157."), isolatedSource.toString())

        val groupedSource = sources.single { it.get("reference").asString() == "158–159" }
        assertEquals("NUMBERED_ITEM", groupedSource.get("referenceType").asString())
        assertTrue(groupedSource.get("text").asString().contains("158."), groupedSource.toString())
        assertTrue(groupedSource.get("text").asString().contains("159."), groupedSource.toString())

        // CA3: nenhuma citação, nem inline nem em sources, menciona página.
        assertFalse(body.contains("página", ignoreCase = true), body)
        assertFalse(body.contains("pagina", ignoreCase = true), body)
        sources.forEach { source -> assertFalse(source.toString().contains("gina"), source.toString()) }

        val generateCall = fakeClaudeClient.generateCalls.single()
        assertFalse(generateCall.contains("p. "), generateCall)
        assertTrue(generateCall.contains(", item: 157"), generateCall)
        assertTrue(generateCall.contains(", item: 158–159"), generateCall)
    }

    @Test
    fun `CA2 - pergunta sem contexto relevante nao produz event sources`() {
        val result =
            postChat(
                deviceId = "device-${UUID.randomUUID()}",
                query = "pergunta sem contexto",
                bookIds = setOf("livro-inexistente-${UUID.randomUUID()}"),
            )
        val body = awaitSseCompletion(result)

        assertFalse(body.contains("event:sources"), body)
        assertTrue(fakeClaudeClient.generateCalls.isEmpty(), "NoRelevantContext não deveria chamar generate")
    }

    @Test
    fun `CA6 - chunk de livro ingerido sem --reference-style aparece em sources com reference e referenceType null, nunca omitido`() {
        val suffix = UUID.randomUUID()
        val bookId = "livro-classico-$suffix"
        val file = PdfFixtures.textPdf(tempDir, listOf(noReferenceBookText()))

        val outcome = ingestionService.ingest(bookId = bookId, title = "Livro Clássico Sem Referência", file = file)
        val completed = outcome as? IngestionOutcome.Completed
        assertNotNull(completed, "esperava IngestionOutcome.Completed, obteve: $outcome")
        checkNotNull(completed)
        assertEquals(1, completed.chunkCount)

        val expectedChunk = chunkRepository.findAll().single { it.bookVersionId == completed.versionId }
        assertEquals(null, expectedChunk.reference, "sem --reference-style, o chunk persistido não deveria ter reference")
        assertEquals(null, expectedChunk.referenceType, "sem --reference-style, o chunk persistido não deveria ter referenceType")

        val result =
            postChat(deviceId = "device-$suffix", query = "do que trata este livro?", bookIds = setOf(bookId))
        val body = awaitSseCompletion(result)

        val sourcesArray = objectMapper.readTree(extractEventData(body, "sources")).get("sources")
        val sources = sourceItems(sourcesArray)
        assertEquals(1, sources.size, sourcesArray.toString())

        val source = sources.single()
        assertEquals(expectedChunk.id.toString(), source.get("chunkId").asString())
        assertEquals(bookId, source.get("bookId").asString())
        assertTrue(source.has("reference"), source.toString())
        assertTrue(source.get("reference").isNull, source.toString())
        assertTrue(source.has("referenceType"), source.toString())
        assertTrue(source.get("referenceType").isNull, source.toString())
        assertEquals(expectedChunk.text, source.get("text").asString())
    }
}
