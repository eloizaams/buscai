package com.buscai.backend.generation

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Binding de `buscai.generation.*` (`application.yml`, `specs/geracao/plan.md` — seção "Config
 * nova"). Registrado via `@ConfigurationPropertiesScan` em `BackendApplication` (mesmo mecanismo de
 * [com.buscai.backend.retrieval.RetrievalProperties]).
 *
 * Defaults espelham os defaults de `application.yml` — necessários porque
 * `src/test/resources/application.yml` substitui (não mescla) o `application.yml` principal na
 * classpath de teste, então `buscai.generation.*` fica ausente nos testes que sobem o contexto
 * Spring sem sobrescrever essas chaves.
 *
 * [maxTokens]: `max_tokens` da chamada de geração final (`ClaudeClient.generate`) — substitui a
 * constante `ANSWER_MAX_TOKENS` que existia temporariamente em `AnthropicClaudeClient` (T1); T4
 * conecta este valor à assinatura de `generate`, encerrando a divergência apontada no review da T1.
 * [historyTurns]: quantas mensagens recentes da conversa entram no rewrite (`ClaudeClient.
 * rewriteQuery`) e no histórico usado para decidir se o rewrite roda.
 */
@ConfigurationProperties(prefix = "buscai.generation")
data class GenerationProperties(
    val maxTokens: Long = 2048,
    val historyTurns: Int = 6,
)
