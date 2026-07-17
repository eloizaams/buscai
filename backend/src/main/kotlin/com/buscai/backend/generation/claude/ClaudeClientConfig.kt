package com.buscai.backend.generation.claude

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Monta o [AnthropicClient] (SDK oficial `com.anthropic:anthropic-java`) usado por
 * [AnthropicClaudeClient], isolado numa `@Configuration` para manter o adapter testável sem subir
 * o contexto Spring (basta injetar outro [AnthropicClient] no construtor, ex. um apontando para um
 * servidor HTTP fake nos testes — mesmo espírito de
 * [com.buscai.backend.embedding.VoyageClientConfig]).
 *
 * [AnthropicOkHttpClient.fromEnv] lê `ANTHROPIC_API_KEY` direto da variável de ambiente (nunca
 * hardcoded/logada — CLAUDE.md, constitution.md seção 1); ficar vazia/ausente não impede o contexto
 * Spring de subir, só falha de fato ao chamar a Claude real ([AnthropicClaudeClient]).
 */
@Configuration
class ClaudeClientConfig {
    @Bean
    fun anthropicClient(): AnthropicClient = AnthropicOkHttpClient.fromEnv()
}
