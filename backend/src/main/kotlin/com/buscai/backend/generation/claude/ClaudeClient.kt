package com.buscai.backend.generation.claude

/**
 * Porta de acesso à Claude (ADR-0004), isolando `GenerationService` (`specs/geracao/`, T4) do SDK
 * concreto ([AnthropicClaudeClient], sobre `com.anthropic:anthropic-java`). Interface
 * deliberadamente síncrona/bloqueante — este backend não usa coroutines/WebFlux em nenhum outro
 * ponto (`JdbcTemplate`, `WebClient` bloqueante da Voyage), então nenhuma exceção nova a essa regra
 * seria justificada aqui.
 */
interface ClaudeClient {
    /**
     * Reescreve [query] incorporando o contexto necessário de [history] (mensagens já trocadas
     * nesta conversa, mais antiga primeiro) — usada só quando a conversa já tem histórico (ADR-0004
     * item 2, `specs/geracao/plan.md`). Chamada não-streaming, barata
     * ([ClaudeProperties.rewriteModel]). Devolve só o texto da pergunta reescrita.
     *
     * @throws ClaudeClientException se a chamada falhar (rede, HTTP, resposta inesperada) — nunca
     *   engolida silenciosamente (CA10, `specs/geracao/spec.md`).
     */
    fun rewriteQuery(
        query: String,
        history: List<HistoryTurn>,
    ): String

    /**
     * Gera a resposta final a partir de [systemPrompt] (instruções fixas de fundamentação/citação,
     * montadas por `GenerationService`) e [userPrompt] (pergunta final + trechos recuperados já
     * formatados). Consome o stream de deltas de texto da Claude
     * ([ClaudeProperties.answerModel]), invocando [onToken] uma vez por delta de texto recebido, na
     * ordem em que chegam — chamada bloqueante: só retorna quando o stream termina (com sucesso ou
     * exceção), nunca devolve nada ela mesma (o texto acumulado é responsabilidade de quem chama,
     * via [onToken]).
     *
     * @throws ClaudeClientException se a chamada/streaming falhar a qualquer momento (rede, HTTP,
     *   queda de conexão no meio do stream) — nunca engolida silenciosamente (CA10). Quem chama
     *   nunca deve tratar uma exceção daqui como se os tokens já recebidos via [onToken] formassem
     *   uma resposta completa.
     */
    fun generate(
        systemPrompt: String,
        userPrompt: String,
        onToken: (String) -> Unit,
    )
}

/** Papel de um turno de histórico — independente da entidade `Message` (`specs/geracao/`, T2), que só existe a partir de T2; `GenerationService` (T4) mapeia `MessageRole` para este tipo ao montar o histórico. */
enum class HistoryRole {
    USER,
    ASSISTANT,
}

/** Um turno de conversa já trocado (pergunta OU resposta), usado como contexto de [ClaudeClient.rewriteQuery]. */
data class HistoryTurn(
    val role: HistoryRole,
    val text: String,
)

/**
 * Erro ao chamar a Claude (ADR-0004) — timeout/erro de rede, resposta HTTP de erro, ou falha no
 * meio do streaming. Mensagem nunca inclui a API key (CLAUDE.md, CA9 `specs/geracao/spec.md`) — o
 * SDK oficial já não a expõe nas mensagens de exceção que produz.
 */
class ClaudeClientException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
