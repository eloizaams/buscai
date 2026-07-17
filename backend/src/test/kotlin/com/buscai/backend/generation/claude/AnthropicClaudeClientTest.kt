package com.buscai.backend.generation.claude

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.assertFailsWith

private const val REWRITE_RESPONSE_BODY =
    """
    {
      "id": "msg_test_rewrite",
      "type": "message",
      "role": "assistant",
      "model": "claude-haiku-4-5",
      "content": [{"type": "text", "text": "O que acontece no capítulo seguinte ao que apresenta o narrador Bentinho?"}],
      "stop_reason": "end_turn",
      "stop_sequence": null,
      "usage": {"input_tokens": 20, "output_tokens": 15}
    }
    """

private const val GENERIC_SERVER_ERROR_BODY =
    """{"type":"error","error":{"type":"internal_server_error","message":"algo deu errado no servidor fake"}}"""

private const val UNAUTHORIZED_ERROR_BODY =
    """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""

/**
 * Testes de [AnthropicClaudeClient] contra um servidor HTTP fake real
 * ([com.sun.net.httpserver.HttpServer], já na JDK — sem dependência de teste nova, ver
 * `specs/geracao/tasks.md`, T1) apontado via `baseUrl` do próprio SDK oficial — nenhuma chamada de
 * rede à Anthropic de verdade. Cobre (a) `rewriteQuery` monta o request com modelo/prompt/histórico
 * esperados e devolve o texto da resposta; (b) `generate` invoca `onToken` uma vez por delta de
 * texto do SSE simulado, na ordem certa; (c) falha HTTP em qualquer uma das duas chamadas propaga
 * [ClaudeClientException], nunca é engolida silenciosamente (CA10, `specs/geracao/spec.md`).
 */
class AnthropicClaudeClientTest {
    private lateinit var server: HttpServer
    private var capturedRequestBody: String = ""
    private var responseHandler: (HttpExchange) -> Unit = { respondError(it, 500, GENERIC_SERVER_ERROR_BODY) }

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.createContext("/v1/messages") { exchange ->
            capturedRequestBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            responseHandler(exchange)
        }
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    private fun baseUrl(): String = "http://localhost:${server.address.port}"

    private fun claudeClient(properties: ClaudeProperties = ClaudeProperties()): AnthropicClaudeClient {
        val anthropicClient: AnthropicClient =
            AnthropicOkHttpClient
                .builder()
                .apiKey("test-key")
                .baseUrl(baseUrl())
                .maxRetries(0)
                .build()
        return AnthropicClaudeClient(anthropicClient, properties)
    }

    @Test
    fun `rewriteQuery monta o request com modelo, prompt e historico esperados e devolve o texto da resposta`() {
        responseHandler = { exchange -> respondJson(exchange, 200, REWRITE_RESPONSE_BODY) }
        val client = claudeClient(ClaudeProperties(rewriteModel = "claude-haiku-4-5", answerModel = "claude-sonnet-5"))

        val rewritten =
            client.rewriteQuery(
                query = "e no capítulo seguinte?",
                history =
                    listOf(
                        HistoryTurn(HistoryRole.USER, "quem é o narrador de Dom Casmurro?"),
                        HistoryTurn(HistoryRole.ASSISTANT, "Bentinho, o narrador em primeira pessoa."),
                    ),
            )

        assertEquals("O que acontece no capítulo seguinte ao que apresenta o narrador Bentinho?", rewritten)
        assertTrue(capturedRequestBody.contains("claude-haiku-4-5"), capturedRequestBody)
        assertTrue(capturedRequestBody.contains("Reescreva a pergunta do usuário"), capturedRequestBody)
        assertTrue(capturedRequestBody.contains("e no capítulo seguinte?"), capturedRequestBody)
        assertTrue(capturedRequestBody.contains("quem é o narrador de Dom Casmurro?"), capturedRequestBody)
        assertTrue(capturedRequestBody.contains("Bentinho, o narrador em primeira pessoa."), capturedRequestBody)
    }

    @Test
    fun `generate invoca onToken uma vez por delta de texto do SSE, na ordem certa`() {
        responseHandler = { exchange -> respondSse(exchange) }
        val client = claudeClient()
        val receivedTokens = mutableListOf<String>()

        client.generate(
            systemPrompt = "responda só com base no contexto fornecido",
            userPrompt = "qual a capital da frança?",
            onToken = { token -> receivedTokens += token },
        )

        assertEquals(listOf("Prefácio", " sem", " pretensões", "."), receivedTokens)
        assertTrue(capturedRequestBody.contains("claude-sonnet-5"), capturedRequestBody)
        assertTrue(capturedRequestBody.contains("responda só com base no contexto fornecido"), capturedRequestBody)
    }

    @Test
    fun `falha HTTP na chamada de generate propaga ClaudeClientException, sem engolir silenciosamente`() {
        responseHandler = { exchange -> respondError(exchange, 500, GENERIC_SERVER_ERROR_BODY) }
        val client = claudeClient()

        val ex =
            assertFailsWith<ClaudeClientException> {
                client.generate("sistema", "pergunta") { }
            }

        assertTrue(ex.message!!.contains("claude-sonnet-5"), "mensagem era: ${ex.message}")
        assertTrue(ex.cause != null, "esperava uma causa encadeada, mas era null")
    }

    @Test
    fun `falha HTTP na chamada de rewriteQuery propaga ClaudeClientException, sem engolir silenciosamente`() {
        responseHandler = { exchange -> respondError(exchange, 401, UNAUTHORIZED_ERROR_BODY) }
        val client = claudeClient()

        val ex =
            assertFailsWith<ClaudeClientException> {
                client.rewriteQuery("pergunta qualquer", emptyList())
            }

        assertTrue(ex.message!!.contains("claude-haiku-4-5"), "mensagem era: ${ex.message}")
        assertTrue(ex.cause != null, "esperava uma causa encadeada, mas era null")
    }
}

private fun respondJson(
    exchange: HttpExchange,
    status: Int,
    body: String,
) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

private fun respondError(
    exchange: HttpExchange,
    status: Int,
    body: String,
) = respondJson(exchange, status, body)

private fun contentBlockDeltaJson(
    index: Int,
    text: String,
): String = """{"type":"content_block_delta","index":$index,"delta":{"type":"text_delta","text":"$text"}}"""

/**
 * Simula o streaming SSE real da Messages API (formato confirmado em
 * `curl/examples.md` do skill `claude-api`): `message_start` → `content_block_start` →
 * N `content_block_delta` (um por token de texto) → `content_block_stop` → `message_delta` →
 * `message_stop`, cada evento como um bloco `event: .../data: ...` separado por linha em branco,
 * enviado com flush individual (chunked, `Content-Length` 0) para simular chegada incremental real.
 */
private fun respondSse(exchange: HttpExchange) {
    exchange.responseHeaders.add("Content-Type", "text/event-stream")
    exchange.sendResponseHeaders(200, 0)
    exchange.responseBody.use { out ->
        fun writeEvent(
            event: String,
            data: String,
        ) {
            out.write("event: $event\ndata: $data\n\n".toByteArray(StandardCharsets.UTF_8))
            out.flush()
        }

        writeEvent(
            "message_start",
            """{"type":"message_start","message":{"id":"msg_test_stream","type":"message","role":"assistant",""" +
                """"model":"claude-sonnet-5","content":[],"stop_reason":null,"stop_sequence":null,""" +
                """"usage":{"input_tokens":10,"output_tokens":1}}}""",
        )
        writeEvent(
            "content_block_start",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        )
        writeEvent("content_block_delta", contentBlockDeltaJson(0, "Prefácio"))
        writeEvent("content_block_delta", contentBlockDeltaJson(0, " sem"))
        writeEvent("content_block_delta", contentBlockDeltaJson(0, " pretensões"))
        writeEvent("content_block_delta", contentBlockDeltaJson(0, "."))
        writeEvent("content_block_stop", """{"type":"content_block_stop","index":0}""")
        writeEvent(
            "message_delta",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},""" +
                """"usage":{"output_tokens":12}}""",
        )
        writeEvent("message_stop", """{"type":"message_stop"}""")
    }
}
