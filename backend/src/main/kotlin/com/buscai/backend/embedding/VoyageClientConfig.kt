package com.buscai.backend.embedding

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

private val VOYAGE_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
private val VOYAGE_READ_TIMEOUT: Duration = Duration.ofSeconds(30)

/**
 * Monta o [RestClient] usado por [VoyageEmbeddingClient] (ADR-0003), isolado numa
 * `@Configuration` para manter a lógica de request/resposta em [VoyageEmbeddingClient] testável
 * sem subir o contexto Spring (basta injetar outro [RestClient] no construtor, ex. um vinculado a
 * `MockRestServiceServer` nos testes).
 *
 * Timeout de conexão/leitura explícitos — não confiar no default "sem timeout" do
 * `JdkClientHttpRequestFactory`: uma chamada travada indefinidamente numa API paga externa não
 * pode travar a ingestão inteira sem produzir um erro claro (CA7, `specs/ingestao-pdf/spec.md`).
 * A key só entra como header HTTP, nunca é interpolada em mensagem de log/erro (CLAUDE.md).
 *
 * `RestClient.builder()` é chamado diretamente (não injetado): `spring-boot-starter-webmvc` não
 * traz `spring-boot-restclient` (módulo que autoconfigura um bean `RestClient.Builder` no Spring
 * Boot 4) — adicioná-lo só para isso seria uma dependência nova sem necessidade real, já que
 * `RestClient` em si já vem de `spring-web` (transitivo do starter webmvc já presente).
 */
@Configuration
class VoyageClientConfig {
    @Bean("voyageRestClient")
    fun voyageRestClient(properties: VoyageProperties): RestClient {
        val httpClient = HttpClient.newBuilder().connectTimeout(VOYAGE_CONNECT_TIMEOUT).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(VOYAGE_READ_TIMEOUT) }
        return RestClient
            .builder()
            .requestFactory(requestFactory)
            .defaultHeader("Authorization", "Bearer ${properties.apiKey}")
            .build()
    }
}
