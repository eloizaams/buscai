package com.buscai.backend.ingestion

import com.buscai.backend.book.EMBEDDING_DIMENSIONS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.net.SocketTimeoutException
import kotlin.test.assertFailsWith

private const val VOYAGE_URL = "https://api.voyageai.com/v1/embeddings"

/**
 * Testes de [VoyageEmbeddingClient] contra um [MockRestServiceServer] (nenhuma chamada de rede
 * real à Voyage) — cobre o caminho feliz em lote (CA da T6), status de erro da API e timeout de
 * rede (CA7, `specs/ingestao-pdf/spec.md`).
 */
class VoyageEmbeddingClientTest {
    private val properties = VoyageProperties(apiKey = "test-key", model = "voyage-3")

    private fun clientWithMockServer(): Pair<VoyageEmbeddingClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val restClient = builder.build()
        return VoyageEmbeddingClient(restClient, properties) to server
    }

    private fun vector(seed: Float): FloatArray = FloatArray(EMBEDDING_DIMENSIONS) { i -> seed + i * 0.0001f }

    private fun FloatArray.toJson(): String = joinToString(prefix = "[", postfix = "]") { it.toString() }

    @Test
    fun `embed devolve os vetores do batch na mesma ordem dos textos de entrada`() {
        val (client, server) = clientWithMockServer()
        val vectorA = vector(0.1f)
        val vectorB = vector(0.2f)
        // Resposta deliberadamente fora de ordem (index 1 antes de 0) para garantir que o client
        // reordena pelo campo `index`, não confia na ordem de chegada do array `data`.
        val responseBody =
            """
            {"data": [
              {"embedding": ${vectorB.toJson()}, "index": 1},
              {"embedding": ${vectorA.toJson()}, "index": 0}
            ]}
            """.trimIndent()
        server
            .expect(requestTo(VOYAGE_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.model").value("voyage-3"))
            .andExpect(jsonPath("$.input[0]").value("primeiro texto"))
            .andExpect(jsonPath("$.input[1]").value("segundo texto"))
            .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON))

        val result = client.embed(listOf("primeiro texto", "segundo texto"))

        assertEquals(2, result.size)
        assertTrue(vectorA.contentEquals(result[0]))
        assertTrue(vectorB.contentEquals(result[1]))
        server.verify()
    }

    @Test
    fun `embed com lista vazia devolve lista vazia sem chamar a API`() {
        val (client, server) = clientWithMockServer()

        val result = client.embed(emptyList())

        assertEquals(emptyList<FloatArray>(), result)
        server.verify()
    }

    @Test
    fun `embed lanca EmbeddingClientException com status e corpo quando a Voyage responde erro`() {
        val (client, server) = clientWithMockServer()
        server
            .expect(requestTo(VOYAGE_URL))
            .andRespond(
                withStatus(HttpStatus.UNAUTHORIZED)
                    .body("""{"detail": "Provided API key is invalid."}""")
                    .contentType(MediaType.APPLICATION_JSON),
            )

        val ex = assertFailsWith<EmbeddingClientException> { client.embed(listOf("texto")) }

        assertTrue(ex.message!!.contains("401"), "mensagem era: ${ex.message}")
        assertTrue(ex.message!!.contains("Provided API key is invalid"), "mensagem era: ${ex.message}")
    }

    @Test
    fun `embed lanca EmbeddingClientException com mensagem clara em timeout de rede`() {
        val (client, server) = clientWithMockServer()
        server
            .expect(requestTo(VOYAGE_URL))
            .andRespond { throw SocketTimeoutException("Read timed out") }

        val ex = assertFailsWith<EmbeddingClientException> { client.embed(listOf("texto")) }

        assertTrue(ex.message!!.contains("Voyage", ignoreCase = true), "mensagem era: ${ex.message}")
        assertTrue(ex.cause is ResourceAccessException, "causa era: ${ex.cause}")
    }

    @Test
    fun `embed lanca EmbeddingClientException quando a Voyage devolve menos vetores que textos enviados`() {
        val (client, server) = clientWithMockServer()
        val responseBody = """{"data": [{"embedding": ${vector(0.1f).toJson()}, "index": 0}]}"""
        server
            .expect(requestTo(VOYAGE_URL))
            .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON))

        val ex = assertFailsWith<EmbeddingClientException> { client.embed(listOf("texto 1", "texto 2")) }

        assertTrue(ex.message!!.contains("1"), "mensagem era: ${ex.message}")
        assertTrue(ex.message!!.contains("2"), "mensagem era: ${ex.message}")
    }

    @Test
    fun `embed lanca EmbeddingClientException quando a dimensao do vetor nao bate com o schema de Chunk`() {
        val (client, server) = clientWithMockServer()
        val wrongDimensionVector = FloatArray(8) { 0.1f }
        val responseBody =
            """{"data": [{"embedding": ${wrongDimensionVector.toJson()}, "index": 0}]}"""
        server
            .expect(requestTo(VOYAGE_URL))
            .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON))

        val ex = assertFailsWith<EmbeddingClientException> { client.embed(listOf("texto")) }

        assertTrue(ex.message!!.contains("8"), "mensagem era: ${ex.message}")
        assertTrue(ex.message!!.contains("$EMBEDDING_DIMENSIONS"), "mensagem era: ${ex.message}")
    }
}
