package com.buscai.backend.generation.claude

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Binding de `buscai.claude.*` (`application.yml`, `specs/geracao/plan.md` — seção "Config nova").
 * Registrado via `@ConfigurationPropertiesScan` em `BackendApplication` (mesmo mecanismo de
 * [com.buscai.backend.embedding.VoyageProperties]). A API key (`ANTHROPIC_API_KEY`) não passa por
 * aqui — é lida direto do ambiente pelo SDK oficial (`AnthropicOkHttpClient.fromEnv()`,
 * constitution.md seção 1), então não existe campo `apiKey` nesta classe.
 *
 * [rewriteModel]: modelo barato para a reescrita de pergunta (query rewriting, ADR-0004 item 2).
 * [answerModel]: modelo de qualidade para a geração da resposta final.
 */
@ConfigurationProperties(prefix = "buscai.claude")
data class ClaudeProperties(
    val rewriteModel: String = "claude-haiku-4-5",
    val answerModel: String = "claude-sonnet-5",
)
