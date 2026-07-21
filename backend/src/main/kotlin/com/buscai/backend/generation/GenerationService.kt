package com.buscai.backend.generation

import com.buscai.backend.generation.claude.ClaudeClient
import com.buscai.backend.generation.claude.HistoryRole
import com.buscai.backend.generation.claude.HistoryTurn
import com.buscai.backend.generation.conversation.ConversationStore
import com.buscai.backend.generation.conversation.Message
import com.buscai.backend.generation.conversation.MessageRole
import com.buscai.backend.ingestion.chunking.ReferenceType
import com.buscai.backend.retrieval.RetrievalResult
import com.buscai.backend.retrieval.RetrievalScope
import com.buscai.backend.retrieval.RetrievalService
import com.buscai.backend.retrieval.RetrievedChunk
import org.springframework.stereotype.Service
import java.util.UUID

/** Resultado de sucesso de [GenerationService.answer] — [conversationId] é sempre devolvido (novo ou existente), mesmo quando a resposta é a mensagem fixa de [RetrievalResult.NoRelevantContext]. */
data class GenerationAnswer(
    val conversationId: UUID,
    val text: String,
)

private const val ANSWER_SYSTEM_PROMPT =
    "Você responde perguntas sobre livros usando SOMENTE o contexto (trechos recuperados) " +
        "fornecido abaixo, nunca conhecimento próprio alheio a ele. Cite o livro de onde vem cada " +
        "informação relevante diretamente no texto da resposta, junto com a referência do trecho " +
        "quando ela vier indicada entre colchetes antes do trecho: se vier \"capítulo: X\", cite o " +
        "livro e o capítulo X (ex.: \"conforme Dom Casmurro, capítulo XII, ...\"); se vier \"item: " +
        "X\", cite o livro e o item/pergunta X (ex.: \"conforme O Livro dos Espíritos, item 157, " +
        "...\"); se o trecho não tiver nenhuma referência entre colchetes além do título do livro, " +
        "cite só o livro, sem inventar capítulo ou item. Nunca cite número de página em nenhuma " +
        "circunstância, mesmo que ela apareça em algum trecho do contexto. Se o contexto fornecido " +
        "não cobrir algum aspecto da pergunta, diga isso explicitamente na resposta, em vez de " +
        "inventar uma informação que não esteja nos trechos fornecidos."

/**
 * Único ponto de entrada da lógica de geração (`specs/geracao/plan.md`, seção "Contratos entre
 * camadas"): resolve/cria a [com.buscai.backend.generation.conversation.Conversation] (T2, via
 * [ConversationStore]), busca o histórico recente, persiste a pergunta do usuário imediatamente
 * (CA7/CA10), reescreve a pergunta via [ClaudeClient.rewriteQuery] só se havia histórico (economia
 * de custo, ADR-0004), chama [RetrievalService.search] com a pergunta final, e ou monta a mensagem
 * fixa de "sem contexto relevante" (CA2, sem gastar uma chamada de geração) ou monta o prompt e
 * chama [ClaudeClient.generate] (CA1/CA6), persistindo a resposta do assistente só em caso de
 * sucesso. [onSourcesResolved] (`specs/referencia-estruturada/plan.md`) é invocado uma única vez,
 * no ramo [RetrievalResult.Found], com os chunks recuperados — antes da chamada a
 * [ClaudeClient.generate], na mesma janela em que [onConversationResolved] já roda; não é
 * invocado no ramo [RetrievalResult.NoRelevantContext].
 *
 * Deliberadamente **não** anotada `@Transactional`: as chamadas de rede à Claude (rewrite/generate)
 * não devem rodar dentro de uma transação de banco aberta (poderiam levar segundos) — cada leitura/
 * gravação de [com.buscai.backend.generation.conversation.Message] usa sua própria transação, via
 * [ConversationStore] (`specs/geracao/plan.md`, seção "Execução assíncrona, transação e
 * persistência").
 *
 * Falha durante [ClaudeClient.rewriteQuery], [RetrievalService.search] ou [ClaudeClient.generate]
 * propaga a exceção original ao chamador (T5, `ChatController`) — nenhum código aqui a intercepta,
 * então nenhuma [Message] de assistente é persistida nesses casos (a persistência da resposta só
 * acontece depois que o passo correspondente retorna com sucesso), coerente com CA10 ("nunca uma
 * resposta parcial como se fosse completa"). A pergunta do usuário já está persistida antes de
 * qualquer uma dessas chamadas (CA7: reabrir a conversa mostra a pergunta feita, mesmo sem
 * resposta).
 */
@Service
class GenerationService(
    private val conversationStore: ConversationStore,
    private val claudeClient: ClaudeClient,
    private val retrievalService: RetrievalService,
    private val generationProperties: GenerationProperties,
) {
    companion object {
        /**
         * Mensagem fixa devolvida quando `RetrievalService.search` sinaliza
         * [RetrievalResult.NoRelevantContext] (CA2) — declara explicitamente a ausência de
         * fundamento no acervo, nunca inventa uma resposta. Persistida como resposta do assistente
         * como qualquer outra, sem chamar [ClaudeClient.generate] (decisão de custo, `plan.md`).
         */
        const val NO_RELEVANT_CONTEXT_MESSAGE =
            "Não encontrei essa informação no acervo de livros indexado até agora. A pergunta pode " +
                "estar fora do que já foi ingerido, ou talvez ajude reformulá-la — prefiro dizer isso " +
                "a inventar uma resposta sem fundamento nos livros disponíveis."
    }

    /**
     * Processa uma pergunta ([query]) de [deviceId] na conversa [conversationId] (`null` = conversa
     * nova, CA11) restrita a [scope] (CA6). [onConversationResolved] é invocado uma única vez, logo
     * após a conversa ser resolvida/criada — antes de qualquer chamada de rewrite/retrieval/geração
     * — com o id definitivo e se ela é nova nesta chamada (`conversationId` de entrada era `null`);
     * existe para que o chamador (T5, `ChatController`) saiba emitir `event: conversation` no
     * momento certo **sem precisar chamar [ConversationStore] por conta própria** (`GenerationService`
     * continua sendo a única porta de entrada da lógica, `specs/geracao/plan.md`, "Contratos entre
     * camadas"). [onToken] é invocado uma vez por delta de texto — tanto para os deltas reais de
     * [ClaudeClient.generate] quanto, num único evento, para a mensagem fixa de
     * [RetrievalResult.NoRelevantContext] (`plan.md`: "mesmo contrato de eventos SSE do caminho
     * normal"). [onSourcesResolved] é invocado uma única vez, no ramo [RetrievalResult.Found], com
     * os chunks recuperados — antes de qualquer [onToken] e antes da chamada a
     * [ClaudeClient.generate] — e não é invocado no ramo [RetrievalResult.NoRelevantContext]
     * (`specs/referencia-estruturada/plan.md`).
     */
    fun answer(
        deviceId: String,
        conversationId: UUID?,
        query: String,
        scope: RetrievalScope,
        onConversationResolved: (conversationId: UUID, isNew: Boolean) -> Unit = { _, _ -> },
        onSourcesResolved: (List<RetrievedChunk>) -> Unit = {},
        onToken: (String) -> Unit = {},
    ): GenerationAnswer {
        val conversation = conversationStore.resolveConversation(deviceId, conversationId)
        onConversationResolved(conversation.id, conversationId == null)
        val history = conversationStore.recentHistory(conversation.id, generationProperties.historyTurns)

        conversationStore.appendMessage(conversation, MessageRole.USER, query)

        val finalQuery =
            if (history.isEmpty()) {
                query
            } else {
                claudeClient.rewriteQuery(query, history.map { it.toHistoryTurn() })
            }

        val retrievalResult = retrievalService.search(finalQuery, scope)

        val answerText =
            when (retrievalResult) {
                RetrievalResult.NoRelevantContext -> {
                    onToken(NO_RELEVANT_CONTEXT_MESSAGE)
                    NO_RELEVANT_CONTEXT_MESSAGE
                }
                is RetrievalResult.Found -> {
                    onSourcesResolved(retrievalResult.chunks)
                    val buffer = StringBuilder()
                    claudeClient.generate(
                        systemPrompt = ANSWER_SYSTEM_PROMPT,
                        userPrompt = buildUserPrompt(retrievalResult.chunks, finalQuery),
                        maxTokens = generationProperties.maxTokens,
                        onToken = { token ->
                            buffer.append(token)
                            onToken(token)
                        },
                    )
                    buffer.toString()
                }
            }

        conversationStore.appendMessage(conversation, MessageRole.ASSISTANT, answerText)

        return GenerationAnswer(conversationId = conversation.id, text = answerText)
    }

    /**
     * Turno do usuário (CA1/CA6): trechos recuperados formatados (livro + referência estruturada,
     * ADR-0013 — nunca página) seguidos da pergunta final.
     */
    private fun buildUserPrompt(
        chunks: List<RetrievedChunk>,
        query: String,
    ): String {
        val chunksBlock =
            chunks.joinToString(separator = "\n\n") { chunk ->
                "[${chunk.bookTitle}${referenceLabel(chunk)}]\n${chunk.text}"
            }
        return "Trechos recuperados:\n\n$chunksBlock\n\nPergunta: $query"
    }

    /**
     * Rótulo de referência do bloco de contexto (ADR-0013): `, capítulo: $reference` para
     * [ReferenceType.CHAPTER], `, item: $reference` para [ReferenceType.NUMBERED_ITEM], ou vazio
     * quando o chunk não tem referência estruturada (livro ingerido sem `--reference-style`).
     */
    private fun referenceLabel(chunk: RetrievedChunk): String =
        when (chunk.referenceType) {
            ReferenceType.CHAPTER -> ", capítulo: ${chunk.reference}"
            ReferenceType.NUMBERED_ITEM -> ", item: ${chunk.reference}"
            null -> ""
        }

    private fun Message.toHistoryTurn(): HistoryTurn =
        HistoryTurn(
            role =
                when (role) {
                    MessageRole.USER -> HistoryRole.USER
                    MessageRole.ASSISTANT -> HistoryRole.ASSISTANT
                },
            text = content,
        )
}
