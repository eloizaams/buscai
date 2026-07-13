package com.buscai.backend.ingestion.embedding

import com.buscai.backend.catalog.EMBEDDING_DIMENSIONS
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

private const val VOYAGE_EMBEDDINGS_PATH = "https://api.voyageai.com/v1/embeddings"

/**
 * Implementação HTTP de [EmbeddingClient] contra a API de embeddings da Voyage AI (ADR-0003).
 * Contrato de request/response — `POST /v1/embeddings`, corpo `{"input": [...], "model": "..."}`,
 * resposta `{"data": [{"embedding": [...], "index": n}, ...]}`, erro `{"detail": "..."}` — foi
 * confirmado por chamada real à API durante a implementação desta task (T6,
 * `specs/ingestao-pdf/tasks.md`), além da documentação em
 * `docs.voyageai.com/reference/embeddings-api`.
 *
 * O [RestClient] injetado (bean `voyageRestClient`, ver [VoyageClientConfig]) já vem com o header
 * `Authorization`/timeouts configurados — esta classe só monta o corpo da requisição e trata a
 * resposta/erro (CA7, `specs/ingestao-pdf/spec.md`); nunca referencia a API key diretamente, então
 * nenhuma mensagem de erro pode vazá-la (CLAUDE.md).
 */
@Component
class VoyageEmbeddingClient(
    @Qualifier("voyageRestClient") private val restClient: RestClient,
    private val properties: VoyageProperties,
) : EmbeddingClient {
    override fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()

        val response =
            try {
                restClient
                    .post()
                    .uri(VOYAGE_EMBEDDINGS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(VoyageEmbeddingRequest(input = texts, model = properties.model))
                    .retrieve()
                    .body(VoyageEmbeddingResponse::class.java)
            } catch (ex: RestClientResponseException) {
                throw EmbeddingClientException(
                    "Voyage AI respondeu ${ex.statusCode.value()} ao gerar embeddings " +
                        "(modelo ${properties.model}, ${texts.size} texto(s)): ${ex.responseBodyAsString}",
                    ex,
                )
            } catch (ex: RestClientException) {
                throw EmbeddingClientException(
                    "Falha de rede/timeout ao chamar a Voyage AI (modelo ${properties.model}, " +
                        "${texts.size} texto(s)): ${ex.message}",
                    ex,
                )
            }

        val data =
            response?.data
                ?: throw EmbeddingClientException(
                    "Voyage AI devolveu resposta vazia ao gerar embeddings (modelo ${properties.model})",
                )
        if (data.size != texts.size) {
            throw EmbeddingClientException(
                "Voyage AI devolveu ${data.size} vetor(es) para ${texts.size} texto(s) enviados " +
                    "(modelo ${properties.model})",
            )
        }

        val vectors = data.sortedBy { it.index }.map { it.embedding.toFloatArray() }
        vectors.forEachIndexed { index, vector ->
            if (vector.size != EMBEDDING_DIMENSIONS) {
                throw EmbeddingClientException(
                    "Voyage AI devolveu vetor de ${vector.size} dimensão(ões) para o texto de índice $index, " +
                        "esperado $EMBEDDING_DIMENSIONS (modelo ${properties.model}) — chunk.embedding (T1/T2) " +
                        "está fixado em vector($EMBEDDING_DIMENSIONS)",
                )
            }
        }
        return vectors
    }
}

/** Corpo de request da Voyage AI (`POST /v1/embeddings`) — nomes de campo exigidos pela API. */
internal data class VoyageEmbeddingRequest(
    val input: List<String>,
    val model: String,
)

/** Corpo de resposta da Voyage AI — só os campos usados aqui; extras (ex. `usage`, `object`) são ignorados. */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class VoyageEmbeddingResponse(
    val data: List<VoyageEmbeddingDatum>?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class VoyageEmbeddingDatum(
    val embedding: List<Float>,
    val index: Int,
)
