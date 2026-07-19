package com.buscai.backend.config

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

private const val API_KEY_HEADER = "X-Api-Key"
private const val CONFIGURED_KEY = "the-real-key"

/**
 * Teste unitário de [ApiKeyFilter], sem subir o Spring context — só o filtro com um
 * `MockHttpServletRequest`/`Response` (Spring Test) e uma [FilterChain] fake que registra se foi
 * chamada (T3, `specs/geracao/tasks.md`).
 */
class ApiKeyFilterTest {
    private class RecordingFilterChain : FilterChain {
        var called = false

        override fun doFilter(
            request: jakarta.servlet.ServletRequest,
            response: jakarta.servlet.ServletResponse,
        ) {
            called = true
        }
    }

    private fun filter(apiKey: String? = CONFIGURED_KEY) = ApiKeyFilter(ApiSecurityProperties(apiKey = apiKey))

    @Test
    fun `requisicao sem X-Api-Key e barrada com 401 antes da chain seguir`() {
        val request = MockHttpServletRequest("GET", "/chat")
        val response = MockHttpServletResponse()
        val chain = RecordingFilterChain()

        filter().doFilter(request, response, chain)

        assertFalse(chain.called)
        assertEquals(401, response.status)
    }

    @Test
    fun `requisicao com X-Api-Key errada e barrada com 401 antes da chain seguir`() {
        val request = MockHttpServletRequest("GET", "/chat")
        request.addHeader(API_KEY_HEADER, "chave-errada")
        val response = MockHttpServletResponse()
        val chain = RecordingFilterChain()

        filter().doFilter(request, response, chain)

        assertFalse(chain.called)
        assertEquals(401, response.status)
    }

    @Test
    fun `requisicao com X-Api-Key certa deixa a chain seguir`() {
        val request = MockHttpServletRequest("GET", "/chat")
        request.addHeader(API_KEY_HEADER, CONFIGURED_KEY)
        val response = MockHttpServletResponse()
        val chain = RecordingFilterChain()

        filter().doFilter(request, response, chain)

        assertTrue(chain.called)
    }

    @Test
    fun `chave configurada nula nega toda requisicao mesmo sem header algum`() {
        val request = MockHttpServletRequest("GET", "/chat")
        val response = MockHttpServletResponse()
        val chain = RecordingFilterChain()

        filter(apiKey = null).doFilter(request, response, chain)

        assertFalse(chain.called)
        assertEquals(401, response.status)
    }

    @Test
    fun `chave configurada em branco nega toda requisicao mesmo com header em branco`() {
        val request = MockHttpServletRequest("GET", "/chat")
        request.addHeader(API_KEY_HEADER, "")
        val response = MockHttpServletResponse()
        val chain = RecordingFilterChain()

        filter(apiKey = "   ").doFilter(request, response, chain)

        assertFalse(chain.called)
        assertEquals(401, response.status)
    }

    @Test
    fun `actuator health e isento do filtro mesmo sem X-Api-Key`() {
        val request = MockHttpServletRequest("GET", "/actuator/health")
        val response = MockHttpServletResponse()
        val chain = RecordingFilterChain()

        filter().doFilter(request, response, chain)

        assertTrue(chain.called)
    }
}
