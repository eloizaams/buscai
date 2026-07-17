package com.buscai.backend.generation.claude

import com.anthropic.client.AnthropicClient
import com.anthropic.errors.AnthropicException
import com.anthropic.models.messages.MessageCreateParams
import org.springframework.stereotype.Component

/**
 * `max_tokens` da chamada de reescrita — pergunta reescrita é sempre curta (uma frase), não precisa
 * de orçamento grande.
 */
private const val REWRITE_MAX_TOKENS = 300L

/**
 * `max_tokens` da chamada de geração da resposta final. **Nota de divergência (T1,
 * `specs/geracao/tasks.md`):** a assinatura de [ClaudeClient.generate] fixada nesta task
 * (`generate(systemPrompt, userPrompt, onToken)`) não recebe `maxTokens` como parâmetro, então este
 * valor é temporariamente uma constante aqui — deliberadamente igual ao default de
 * `buscai.generation.max-tokens` (2048) documentado em `specs/geracao/plan.md` ("Config nova") para
 * `GenerationProperties`, que só nasce na T4. A T4 (`GenerationService`) precisará estender esta
 * assinatura (ou expor o valor por outro canal) para tornar isso de fato configurável via
 * `application.yml`; até lá, este valor fixo é o comportamento efetivo.
 */
private const val ANSWER_MAX_TOKENS = 2048L

private const val REWRITE_SYSTEM_PROMPT =
    "Reescreva a pergunta do usuário incorporando o contexto necessário do histórico da conversa " +
        "abaixo, de forma que a pergunta reescrita faça sentido sozinha, sem depender do histórico. " +
        "Devolva somente a pergunta reescrita, nada mais — sem explicações, sem aspas, sem prefixos."

/**
 * Implementação de [ClaudeClient] sobre o SDK oficial `com.anthropic:anthropic-java` (ADR-0004,
 * `specs/geracao/plan.md` — seção "Decisão de biblioteca"). O [AnthropicClient] injetado (bean
 * `anthropicClient`, ver [ClaudeClientConfig]) já resolve `ANTHROPIC_API_KEY` do ambiente; esta
 * classe nunca referencia a key diretamente, então nenhuma mensagem de erro pode vazá-la
 * (CLAUDE.md, CA9).
 */
@Component
class AnthropicClaudeClient(
    private val client: AnthropicClient,
    private val properties: ClaudeProperties,
) : ClaudeClient {
    override fun rewriteQuery(
        query: String,
        history: List<HistoryTurn>,
    ): String {
        val builder =
            MessageCreateParams
                .builder()
                .model(properties.rewriteModel)
                .maxTokens(REWRITE_MAX_TOKENS)
                .system(REWRITE_SYSTEM_PROMPT)
        history.forEach { turn ->
            when (turn.role) {
                HistoryRole.USER -> builder.addUserMessage(turn.text)
                HistoryRole.ASSISTANT -> builder.addAssistantMessage(turn.text)
            }
        }
        builder.addUserMessage(query)

        val response =
            try {
                client.messages().create(builder.build())
            } catch (ex: AnthropicException) {
                throw ClaudeClientException(
                    "Falha ao chamar a Claude para reescrever a pergunta " +
                        "(modelo ${properties.rewriteModel}): ${ex.message}",
                    ex,
                )
            }

        return response
            .content()
            .mapNotNull { block -> block.text().orElse(null)?.text() }
            .joinToString(separator = "")
    }

    override fun generate(
        systemPrompt: String,
        userPrompt: String,
        onToken: (String) -> Unit,
    ) {
        val params =
            MessageCreateParams
                .builder()
                .model(properties.answerModel)
                .maxTokens(ANSWER_MAX_TOKENS)
                .system(systemPrompt)
                .addUserMessage(userPrompt)
                .build()

        try {
            client.messages().createStreaming(params).use { stream ->
                stream
                    .stream()
                    .flatMap { event -> event.contentBlockDelta().stream() }
                    .flatMap { deltaEvent -> deltaEvent.delta().text().stream() }
                    .forEach { textDelta -> onToken(textDelta.text()) }
            }
        } catch (ex: AnthropicException) {
            throw ClaudeClientException(
                "Falha ao chamar/consumir o streaming da Claude para gerar a resposta " +
                    "(modelo ${properties.answerModel}): ${ex.message}",
                ex,
            )
        }
    }
}
