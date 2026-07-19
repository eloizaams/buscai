package com.buscai.backend.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "buscai.api-key=probe-key",
        "buscai.rate-limit.requests-per-minute=1",
    ],
)
class FilterDoubleRegistrationProbeTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `filtro roda 1x por requisicao HTTP - 1a passa, 2a estoura o limite de 1`() {
        val first =
            mockMvc
                .perform(get("/probe-path-que-nao-existe").header("X-Api-Key", "probe-key"))
                .andReturn()
        println("STATUS1=" + first.response.status)
        // Se rodasse 2x por requisição (registro duplicado), a 1a já estouraria (429).
        assertNotEquals(429, first.response.status)

        val second =
            mockMvc
                .perform(get("/probe-path-que-nao-existe").header("X-Api-Key", "probe-key"))
                .andReturn()
        println("STATUS2=" + second.response.status)
        // Confirma que o rate limit realmente funciona (não é um falso-negativo por não incrementar
        // nada): a 2a requisição estoura o limite de 1/minuto.
        assertEquals(429, second.response.status)
    }
}
