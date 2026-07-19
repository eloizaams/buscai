package com.buscai.backend.config

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

private const val API_KEY_HEADER = "X-Api-Key"

/**
 * ADR-0005: barra toda requisição sem a chave estática certa **antes** de deixá-la chegar a
 * qualquer controller — e, portanto, antes de qualquer chamada às APIs pagas de Claude/Voyage
 * (constitution.md seção 1). Registrado primeiro na cadeia por [WebConfig] (antes de
 * [RateLimitFilter]): uma requisição sem chave válida nem deveria consumir cota de rate limit.
 *
 * [ApiSecurityProperties.apiKey] nulo, vazio ou só espaço em branco é tratado como "negar tudo" —
 * ver KDoc de [ApiSecurityProperties] para a decisão sobre `BUSCAI_API_KEY` ausente.
 *
 * Corpo de erro simples (`{"error":"unauthorized"}`, sem detalhe interno, CA9) e único caminho
 * isento é [ApiSecurityPaths].
 */
class ApiKeyFilter(
    private val properties: ApiSecurityProperties,
) : Filter {
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

        val configuredKey = properties.apiKey
        val providedKey = httpRequest.getHeader(API_KEY_HEADER)

        if (configuredKey.isNullOrBlank() || providedKey.isNullOrBlank() || providedKey != configuredKey) {
            httpResponse.writeSecurityError(HttpServletResponse.SC_UNAUTHORIZED, "unauthorized")
            return
        }

        chain.doFilter(request, response)
    }
}
