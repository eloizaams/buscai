package com.buscai.backend.config

import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Registra a cadeia de filtros de segurança do ADR-0005, na ordem [ApiKeyFilter] → [RateLimitFilter]
 * (autenticação primeiro: uma requisição sem chave válida nem chega a consumir cota de rate
 * limit). Aplicada a todas as rotas — inclui rotas que ainda não existem (ex. `POST /chat`,
 * T5), exceto o único caminho isento documentado em [ApiSecurityPaths].
 *
 * `@EnableScheduling` habilita a tarefa `@Scheduled` de limpeza de
 * [RateLimitFilter.evictStaleWindows] — única `@Configuration` do projeto que precisa de
 * agendamento até esta task.
 *
 * Cada filtro é exposto como `@Bean` próprio (não só como `.filter` dentro do
 * `FilterRegistrationBean`): [RateLimitFilter] precisa disso para o
 * `ScheduledAnnotationBeanPostProcessor` do Spring processar seu método `@Scheduled` (métodos
 * anotados só são detectados em beans reais do container, não em objetos construídos "à mão"
 * dentro de outro método `@Bean`). Isso não registra o filtro duas vezes na cadeia servlet: ao
 * processar um `FilterRegistrationBean`, `org.springframework.boot.web.servlet.
 * ServletContextInitializerBeans` marca o `Filter` que ele envolve como "já visto" antes de varrer
 * beans `Filter` soltos — o bean `apiKeyFilter`/`rateLimitFilter` nunca vira uma segunda
 * `FilterRegistrationBean` automática.
 */
@Configuration
@EnableScheduling
class WebConfig {
    @Bean
    fun apiKeyFilter(properties: ApiSecurityProperties): ApiKeyFilter = ApiKeyFilter(properties)

    @Bean
    fun apiKeyFilterRegistration(apiKeyFilter: ApiKeyFilter): FilterRegistrationBean<ApiKeyFilter> =
        FilterRegistrationBean(apiKeyFilter).apply {
            order = 1
            addUrlPatterns("/*")
        }

    @Bean
    fun rateLimitFilter(properties: ApiSecurityProperties): RateLimitFilter = RateLimitFilter(properties)

    @Bean
    fun rateLimitFilterRegistration(rateLimitFilter: RateLimitFilter): FilterRegistrationBean<RateLimitFilter> =
        FilterRegistrationBean(rateLimitFilter).apply {
            order = 2
            addUrlPatterns("/*")
        }
}
