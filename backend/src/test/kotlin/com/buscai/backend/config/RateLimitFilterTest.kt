package com.buscai.backend.config

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private const val FORWARDED_FOR_HEADER = "X-Forwarded-For"

/**
 * Teste unitário de [RateLimitFilter], sem subir o Spring context — só o filtro com um
 * `MockHttpServletRequest`/`Response` (Spring Test) e uma [FilterChain] fake (T3,
 * `specs/geracao/tasks.md`).
 */
class RateLimitFilterTest {
    private class RecordingFilterChain : FilterChain {
        var calls = 0

        override fun doFilter(
            request: jakarta.servlet.ServletRequest,
            response: jakarta.servlet.ServletResponse,
        ) {
            calls++
        }
    }

    /** [Clock] com instante mutável, para controlar o avanço do tempo nos testes de janela/eviction. */
    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

    private fun requestFrom(
        remoteAddr: String,
        forwardedFor: String? = null,
    ): MockHttpServletRequest {
        val request = MockHttpServletRequest("GET", "/chat")
        request.remoteAddr = remoteAddr
        if (forwardedFor != null) {
            request.addHeader(FORWARDED_FOR_HEADER, forwardedFor)
        }
        return request
    }

    private fun filter(
        requestsPerMinute: Int = 2,
        clock: Clock = Clock.systemUTC(),
    ) = RateLimitFilter(ApiSecurityProperties(apiKey = "any", rateLimit = ApiSecurityProperties.RateLimit(requestsPerMinute)), clock)

    @Test
    fun `N+1-esima requisicao na mesma janela do mesmo IP e barrada com 429`() {
        val rateLimitFilter = filter(requestsPerMinute = 2)
        val chain = RecordingFilterChain()

        repeat(2) {
            val response = MockHttpServletResponse()
            rateLimitFilter.doFilter(requestFrom("10.0.0.1"), response, chain)
            assertEquals(200, response.status)
        }

        val thirdResponse = MockHttpServletResponse()
        rateLimitFilter.doFilter(requestFrom("10.0.0.1"), thirdResponse, chain)

        assertEquals(429, thirdResponse.status)
        assertEquals(2, chain.calls)
    }

    @Test
    fun `IPs diferentes tem contadores independentes`() {
        val rateLimitFilter = filter(requestsPerMinute = 1)
        val chain = RecordingFilterChain()

        val responseIpA = MockHttpServletResponse()
        rateLimitFilter.doFilter(requestFrom("10.0.0.1"), responseIpA, chain)
        assertEquals(200, responseIpA.status)

        // IP A já esgotou a cota — a próxima requisição dele é barrada.
        val responseIpABarrada = MockHttpServletResponse()
        rateLimitFilter.doFilter(requestFrom("10.0.0.1"), responseIpABarrada, chain)
        assertEquals(429, responseIpABarrada.status)

        // IP B nunca fez requisição — não é afetado pela cota de A.
        val responseIpB = MockHttpServletResponse()
        rateLimitFilter.doFilter(requestFrom("10.0.0.2"), responseIpB, chain)
        assertEquals(200, responseIpB.status)
    }

    @Test
    fun `X-Forwarded-For presente usa esse valor como chave, nao o remoteAddr`() {
        val rateLimitFilter = filter(requestsPerMinute = 1)

        // Mesmo X-Forwarded-For, remoteAddr diferente (simula duas requisições atravessando o
        // mesmo proxy do Render/Fly, vindas do mesmo cliente) — devem compartilhar a cota.
        val chain = RecordingFilterChain()
        val firstResponse = MockHttpServletResponse()
        rateLimitFilter.doFilter(requestFrom(remoteAddr = "172.16.0.5", forwardedFor = "203.0.113.9"), firstResponse, chain)
        assertEquals(200, firstResponse.status)

        val secondResponse = MockHttpServletResponse()
        rateLimitFilter.doFilter(requestFrom(remoteAddr = "172.16.0.9", forwardedFor = "203.0.113.9"), secondResponse, chain)
        assertEquals(429, secondResponse.status, "mesmo X-Forwarded-For deveria compartilhar a cota, ignorando remoteAddr")
    }

    @Test
    fun `X-Forwarded-For com lista de IPs usa apenas o primeiro valor`() {
        val rateLimitFilter = filter(requestsPerMinute = 1)
        val chain = RecordingFilterChain()

        val firstResponse = MockHttpServletResponse()
        rateLimitFilter.doFilter(requestFrom(remoteAddr = "172.16.0.5", forwardedFor = "203.0.113.9, 10.0.0.1"), firstResponse, chain)
        assertEquals(200, firstResponse.status)

        val secondResponse = MockHttpServletResponse()
        rateLimitFilter.doFilter(requestFrom(remoteAddr = "172.16.0.9", forwardedFor = "203.0.113.9"), secondResponse, chain)
        assertEquals(429, secondResponse.status)
    }

    @Test
    fun `actuator health e isento do rate limit`() {
        val rateLimitFilter = filter(requestsPerMinute = 1)
        val chain = RecordingFilterChain()

        repeat(5) {
            val response = MockHttpServletResponse()
            val request = MockHttpServletRequest("GET", "/actuator/health")
            request.remoteAddr = "10.0.0.1"
            rateLimitFilter.doFilter(request, response, chain)
            assertEquals(200, response.status)
        }
    }

    @Test
    fun `evictStaleWindows remove entradas mais antigas que o limiar, preserva as recentes`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val rateLimitFilter = filter(requestsPerMinute = 10, clock = clock)
        val chain = RecordingFilterChain()

        rateLimitFilter.doFilter(requestFrom("10.0.0.1"), MockHttpServletResponse(), chain)
        assertEquals(1, rateLimitFilter.windowsByIp.size)

        // Menos de "algumas janelas" (limiar de eviction) — a entrada ainda deve sobreviver.
        clock.advance(Duration.ofMinutes(2))
        rateLimitFilter.evictStaleWindows()
        assertTrue(rateLimitFilter.windowsByIp.containsKey("10.0.0.1"))

        // Passa do limiar (5 janelas de 1 minuto) sem nenhuma requisição nova daquele IP.
        clock.advance(Duration.ofMinutes(10))
        rateLimitFilter.evictStaleWindows()
        assertFalse(rateLimitFilter.windowsByIp.containsKey("10.0.0.1"))
    }
}
