package com.buscai.backend.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

/**
 * Teste de integração (Spring context real) que prova em runtime a ordem real registrada por
 * [WebConfig]: [ApiKeyFilter] antes de [RateLimitFilter] (ADR-0005). Fecha a lacuna apontada pelo
 * code-reviewer após T3+T4 — os testes unitários (`ApiKeyFilterTest`, `RateLimitFilterTest`) e o
 * probe de registro duplicado (`FilterDoubleRegistrationProbeTest`) cobrem cada filtro isolado ou a
 * ausência de registro duplicado, mas nenhum prova a ordem relativa entre os dois em runtime.
 *
 * Rate limit configurado propositalmente baixo (1/min, via `@TestPropertySource`, mesmo padrão de
 * [FilterDoubleRegistrationProbeTest]) para tornar fácil estourá-lo com poucas requisições.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "buscai.api-key=integration-order-key",
        "buscai.rate-limit.requests-per-minute=1",
    ],
)
class FilterOrderIntegrationTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    /**
     * Se a ordem estivesse invertida (RateLimitFilter antes de ApiKeyFilter), a partir da
     * (limite+1)-ésima requisição o RateLimitFilter já teria contado e barrado com 429 antes mesmo
     * de o ApiKeyFilter checar a chave — mascarando o 401 esperado. Como a ordem real é ApiKeyFilter
     * primeiro, ele barra com 401 sem nunca chamar a chain adiante; o RateLimitFilter nunca roda
     * para essas requisições, então todas continuam vindo 401, nunca 429, mesmo bem acima do limite
     * configurado (1/min) neste teste.
     */
    @Test
    fun `requisicoes sem X-Api-Key valida continuam vindo 401, nunca 429, mesmo acima do rate limit`() {
        repeat(3) { attempt ->
            val response =
                mockMvc
                    .perform(get("/probe-path-ordem-filtros").header("X-Api-Key", "chave-errada"))
                    .andReturn()
                    .response

            assertEquals(401, response.status, "tentativa ${attempt + 1} deveria ser 401 (ApiKeyFilter), nao 429")
        }
    }
}
