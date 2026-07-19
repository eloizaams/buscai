package com.buscai.backend.config

private const val HEALTH_CHECK_PATH = "/actuator/health"

/**
 * Único caminho isento de [ApiKeyFilter]/[RateLimitFilter]: o health check do Actuator
 * (`/actuator/health`), consumido pela própria plataforma de hospedagem (Render/Fly, ADR-0006)
 * para decidir se o container está vivo — esse probe de infraestrutura não tem como enviar um
 * header `X-Api-Key` customizado.
 *
 * Decisão deliberada e restrita a este único path, não a todo o namespace `/actuator` do Spring
 * Boot Actuator: o endpoint não gasta crédito em nenhuma API paga (ADR-0005 só exige a chave antes
 * de chamadas a Claude/Voyage) e, sem `management.endpoint.health.show-details` configurado em
 * `application.yml`
 * (default do Spring Boot é `never`), a resposta não vaza nenhum detalhe interno a quem não tem a
 * chave. Qualquer outro endpoint do Actuator que vier a ser exposto no futuro (ex. `/actuator/
 * metrics`, `/actuator/env`) continua exigindo a chave normalmente — não é uma brecha geral.
 */
internal object ApiSecurityPaths {
    fun isExempt(requestUri: String): Boolean = requestUri == HEALTH_CHECK_PATH || requestUri.startsWith("$HEALTH_CHECK_PATH/")
}
