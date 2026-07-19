package com.buscai.backend.config

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val FORWARDED_FOR_HEADER = "X-Forwarded-For"
private val WINDOW_DURATION: Duration = Duration.ofMinutes(1)

// "Algumas janelas" (task T3): entradas mais antigas que isso são removidas por
// [RateLimitFilter.evictStaleWindows] — 5 minutos é um ponto de partida, não medido contra tráfego
// real (mesma ressalva de ApiSecurityProperties.rateLimit).
private val EVICTION_THRESHOLD: Duration = WINDOW_DURATION.multipliedBy(5)

/**
 * ADR-0005: rate limit simples por IP — contador em memória, janela fixa de um minuto, acima do
 * limite configurado (`ApiSecurityProperties.rateLimit.requestsPerMinute`) responde `429` (CA8).
 * Registrado depois de [ApiKeyFilter] por [WebConfig].
 *
 * Chave do contador: o primeiro valor de `X-Forwarded-For`, quando presente. O backend roda atrás
 * do proxy reverso do Render/Fly (ADR-0006) — nesse cenário `request.remoteAddr` enxerga só o IP
 * do proxy (o mesmo para toda requisição, não diferencia clientes), então `X-Forwarded-For` é a
 * única forma de isolar IPs de clientes distintos; a queda para `request.remoteAddr` serve só para
 * execução sem proxy na frente (local/teste).
 *
 * O contador em memória (`ConcurrentHashMap`) cresceria indefinidamente sob um spray de IPs
 * distintos (deliberado ou não) se nada o esvaziasse — [evictStaleWindows], agendada via
 * `@Scheduled` (habilitado por `@EnableScheduling` em [WebConfig]), remove entradas de janelas mais
 * antigas que [EVICTION_THRESHOLD], protegendo a RAM do free tier (ADR-0006).
 */
class RateLimitFilter(
    private val properties: ApiSecurityProperties,
    private val clock: Clock = Clock.systemUTC(),
) : Filter {
    // internal (não private): permite RateLimitFilterTest inspecionar o tamanho do mapa antes/depois
    // de evictStaleWindows() sem expor esse estado como API pública do filtro.
    internal val windowsByIp = ConcurrentHashMap<String, RequestWindow>()

    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        val httpRequest = request as HttpServletRequest
        val httpResponse = response as HttpServletResponse

        if (ApiSecurityPaths.isExempt(httpRequest.requestURI)) {
            chain.doFilter(request, response)
            return
        }

        val ip = clientIp(httpRequest)
        val now = clock.instant()
        val window =
            windowsByIp.compute(ip) { _, existing ->
                if (existing == null || Duration.between(existing.startedAt, now) >= WINDOW_DURATION) {
                    RequestWindow(now)
                } else {
                    existing
                }
            }!!

        val requestsInWindow = window.count.incrementAndGet()
        if (requestsInWindow > properties.rateLimit.requestsPerMinute) {
            // jakarta.servlet.http.HttpServletResponse não define uma constante para 429 (RFC 6585
            // é posterior à especificação Servlet original) — literal documentado aqui.
            httpResponse.writeSecurityError(429, "rate limit exceeded")
            return
        }

        chain.doFilter(request, response)
    }

    private fun clientIp(request: HttpServletRequest): String {
        val forwardedFor = request.getHeader(FORWARDED_FOR_HEADER)
        val firstForwarded = forwardedFor?.substringBefore(',')?.trim()
        return firstForwarded.takeUnless { it.isNullOrBlank() } ?: request.remoteAddr
    }

    // Roda a cada minuto (mesma granularidade da janela) — não precisa de mais frequência, já que
    // uma entrada só é elegível para remoção depois de EVICTION_THRESHOLD (5 janelas) inteira sem
    // nenhuma requisição daquele IP.
    @Scheduled(fixedRate = 60_000)
    fun evictStaleWindows() {
        val now = clock.instant()
        windowsByIp.entries.removeIf { (_, window) -> Duration.between(window.startedAt, now) >= EVICTION_THRESHOLD }
    }

    // internal (não private) pela mesma razão de windowsByIp acima: precisa ser pelo menos tão
    // visível quanto o mapa que a expõe.
    internal class RequestWindow(
        val startedAt: Instant,
    ) {
        val count = AtomicInteger(0)
    }
}
