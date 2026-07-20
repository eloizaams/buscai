package com.buscai.backend.config

private const val HEALTH_CHECK_PATH = "/actuator/health"

/**
 * Arquivos estáticos do cliente web fino (`web/`, servido same-origin pelo próprio Spring Boot via
 * o resource handler default de `classpath:/static/`, ver `backend/build.gradle.kts` — task
 * `copyWebStatic` — e `specs/cliente-web/plan.md`, seção "Arquivos estáticos"). `app.js`/
 * `styles.css` ainda não existem
 * fisicamente nesta task (chegam em T3 de `specs/cliente-web/tasks.md`), mas já isentar agora evita
 * mais uma task tocando este arquivo depois. Servir HTML/CSS/JS não gasta crédito em API paga
 * (ADR-0005 só exige a chave antes de chamadas a Claude/Voyage) — as chamadas de API que o JS
 * carregado faz depois (`/chat`, `/conversations`, `/books`) continuam 100% protegidas, sem exceção.
 */
private val STATIC_WEB_PATHS = setOf("/", "/index.html", "/app.js", "/styles.css")

/**
 * Caminhos isentos de [ApiKeyFilter]/[RateLimitFilter]: o health check do Actuator
 * (`/actuator/health`), consumido pela própria plataforma de hospedagem (Render/Fly, ADR-0006)
 * para decidir se o container está vivo — esse probe de infraestrutura não tem como enviar um
 * header `X-Api-Key` customizado — e os arquivos estáticos do cliente web (ver
 * [STATIC_WEB_PATHS]).
 *
 * Allowlist explícita, não um glob "tudo que não é API conhecida" — evita isentar por acidente uma
 * rota de API futura. `/actuator/health` continua com a checagem por prefixo de sempre: decisão
 * deliberada e restrita a este único path, não a todo o namespace `/actuator` do Spring Boot
 * Actuator; sem `management.endpoint.health.show-details` configurado em `application.yml`
 * (default do Spring Boot é `never`), a resposta não vaza nenhum detalhe interno a quem não tem a
 * chave. Qualquer outro endpoint do Actuator que vier a ser exposto no futuro (ex. `/actuator/
 * metrics`, `/actuator/env`) continua exigindo a chave normalmente — não é uma brecha geral.
 */
internal object ApiSecurityPaths {
    fun isExempt(requestUri: String): Boolean =
        requestUri == HEALTH_CHECK_PATH || requestUri.startsWith("$HEALTH_CHECK_PATH/") || requestUri in STATIC_WEB_PATHS
}
