package com.buscai.backend.generation

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
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import kotlin.test.assertEquals

private const val VALID_API_KEY = "generation-rate-limit-acceptance-test-key"

/**
 * CA8 (`specs/geracao/tasks.md`, T8): N+1 requisições na mesma janela de rate limit recebem `429`.
 * Classe própria, separada de [GenerationAcceptanceTest], porque precisa de um
 * `buscai.rate-limit.requests-per-minute` propositalmente baixo (mesmo padrão de
 * `com.buscai.backend.config.FilterOrderIntegrationTest`) — um contexto Spring compartilhado com
 * os demais cenários (CA1-CA7, CA9-CA11) vazaria esse limite baixo para eles, quebrando testes que
 * não têm nada a ver com rate limit. Usa `GET /conversations` (não `/chat`): não precisa de
 * `ClaudeClient`/embedding fake nenhum, só de um endpoint autenticado e válido que toca o banco
 * (Testcontainers) depois dos filtros de T3.
 */
@Testcontainers
@ActiveProfiles("testcontainers")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "buscai.api-key=$VALID_API_KEY",
        "buscai.rate-limit.requests-per-minute=2",
    ],
)
class GenerationRateLimitAcceptanceTest {
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

    @Test
    fun `CA8 - N+1 requisicoes na mesma janela do rate limit recebem 429`() {
        val deviceId = "device-${UUID.randomUUID()}"

        repeat(2) { attempt ->
            val response =
                mockMvc
                    .perform(
                        get("/conversations")
                            .header("X-Api-Key", VALID_API_KEY)
                            .header("X-Device-Id", deviceId),
                    ).andReturn()
                    .response

            assertEquals(200, response.status, "tentativa ${attempt + 1} deveria estar dentro do limite configurado (2/min)")
        }

        val thirdResponse =
            mockMvc
                .perform(
                    get("/conversations")
                        .header("X-Api-Key", VALID_API_KEY)
                        .header("X-Device-Id", deviceId),
                ).andReturn()
                .response

        assertEquals(429, thirdResponse.status, "a (limite+1)-ésima requisição deveria vir 429")
    }
}
