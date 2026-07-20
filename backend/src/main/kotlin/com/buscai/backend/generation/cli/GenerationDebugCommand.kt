package com.buscai.backend.generation.cli

import com.buscai.backend.generation.GenerationAnswer
import com.buscai.backend.generation.GenerationService
import com.buscai.backend.retrieval.RetrievalScope
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * `deviceId` fixo usado por toda invocação de [GenerationDebugCommand] — não existe um dispositivo
 * real neste contexto de CLI (operador local), e um valor estável entre invocações é o que permite
 * que `--conversation-id` (de uma invocação anterior) resolva a mesma conversa na próxima chamada
 * (`ConversationStore.resolveConversation` filtra por `deviceId`, `specs/geracao/plan.md`). Sem
 * `--conversation-id`, cada invocação ainda cria uma [com.buscai.backend.generation.conversation.Conversation]
 * nova (CA11) — só o `deviceId` é compartilhado entre elas.
 */
internal const val CLI_DEVICE_ID = "cli-debug"

/**
 * Argumentos já validados de uma invocação de [GenerationDebugCommand] (ver
 * [GenerationDebugArgsParser]).
 */
data class GenerationDebugArgs(
    val query: String,
    val scope: RetrievalScope,
    val conversationId: UUID?,
)

/**
 * Resultado do parsing de argumentos de linha de comando de [GenerationDebugCommand] (T7): [Parsed]
 * segue para [GenerationService.answer]; [Error] carrega uma mensagem já pronta para o operador, sem
 * chamar [GenerationService.answer] — mesmo padrão de `RetrievalDebugArgsResult`
 * (`retrieval.cli.RetrievalDebugCommand`).
 */
sealed class GenerationDebugArgsResult {
    data class Parsed(
        val args: GenerationDebugArgs,
    ) : GenerationDebugArgsResult()

    data class Error(
        val message: String,
    ) : GenerationDebugArgsResult()
}

/**
 * Parseia e valida os argumentos de linha de comando de `GenerationDebugCommand` (T7). Formato
 * aceito: `--query=<pergunta>` (obrigatório), `--books=<id1,id2,...>` (opcional, lista separada por
 * vírgula — define [RetrievalScope.Books]; ausente ou em branco define [RetrievalScope.AllBooks]) e
 * `--conversation-id=<uuid>` (opcional — ausente inicia uma conversa nova, CA11; mesmo formato de
 * `--books`/`--query` de `RetrievalDebugArgsParser`).
 */
object GenerationDebugArgsParser {
    private const val QUERY_KEY = "query"
    private const val BOOKS_KEY = "books"
    private const val CONVERSATION_ID_KEY = "conversation-id"

    fun parse(args: Array<out String>): GenerationDebugArgsResult {
        val options = mutableMapOf<String, String>()

        for (arg in args) {
            if (arg.startsWith("--") && arg.contains("=")) {
                val (key, value) = arg.removePrefix("--").split("=", limit = 2)
                options[key] = value
            } else {
                return GenerationDebugArgsResult.Error(
                    "Argumento não reconhecido: '$arg'. Use --query=<pergunta> " +
                        "[--books=<id1,id2,...>] [--conversation-id=<uuid>].",
                )
            }
        }

        val query = options[QUERY_KEY]
        if (query.isNullOrBlank()) {
            return GenerationDebugArgsResult.Error("Argumento obrigatório ausente: --query=<pergunta>.")
        }

        val booksArg = options[BOOKS_KEY]
        val scope =
            if (booksArg.isNullOrBlank()) {
                RetrievalScope.AllBooks
            } else {
                val bookIds =
                    booksArg
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet()
                if (bookIds.isEmpty()) {
                    return GenerationDebugArgsResult.Error(
                        "Argumento --books informado sem nenhum bookId válido: '$booksArg'.",
                    )
                }
                RetrievalScope.Books(bookIds)
            }

        val conversationIdArg = options[CONVERSATION_ID_KEY]
        val conversationId =
            if (conversationIdArg.isNullOrBlank()) {
                null
            } else {
                try {
                    UUID.fromString(conversationIdArg)
                } catch (e: IllegalArgumentException) {
                    return GenerationDebugArgsResult.Error(
                        "Argumento --conversation-id não é um UUID válido: '$conversationIdArg'.",
                    )
                }
            }

        return GenerationDebugArgsResult.Parsed(
            GenerationDebugArgs(query = query, scope = scope, conversationId = conversationId),
        )
    }
}

/**
 * Formata as mensagens de status impressas por [GenerationDebugCommand] — extraído para ser
 * testável isoladamente, sem subir o Spring context (mesmo padrão de `RetrievalResultFormatter`,
 * `retrieval.cli.RetrievalDebugCommand`).
 */
object GenerationDebugOutputFormatter {
    /** Impressa uma vez, assim que a conversa é resolvida/criada — antes de qualquer token. */
    fun formatConversationResolved(
        conversationId: UUID,
        isNew: Boolean,
    ): String =
        if (isNew) {
            "[conversa nova: $conversationId]"
        } else {
            "[conversa: $conversationId]"
        }

    /**
     * Impressa ao final, depois de todos os tokens já terem chegado via `onToken` — cobre tanto uma
     * resposta gerada normalmente (`RetrievalResult.Found`) quanto a mensagem fixa de
     * [GenerationService.NO_RELEVANT_CONTEXT_MESSAGE] ([RetrievalResult.NoRelevantContext]): as duas
     * chegam da mesma forma em [GenerationAnswer.text] (`GenerationService`, "mesmo contrato de
     * eventos SSE do caminho normal", `plan.md`), então não há uma variante distinta a formatar aqui.
     */
    fun formatDone(answer: GenerationAnswer): String = "\n[fim — conversa ${answer.conversationId}]"

    /**
     * Mensagem de erro clara ao operador — nunca o detalhe cru de uma exceção que possa vazar
     * informação de infraestrutura ou de uma chave de API (CA9, `specs/geracao/spec.md`); a exceção
     * do SDK oficial e as demais exceções desta camada já não expõem a key nas mensagens que
     * produzem, então repassar `e.message` aqui é seguro (mesmo padrão de
     * `RetrievalDebugCommand`/`RetrievalResultFormatter`).
     */
    fun formatError(e: Exception): String = "Erro ao gerar resposta: ${e.message}"
}

/**
 * Entrypoint da CLI de debug de geração (T7, `specs/geracao/plan.md`, seção "Como roda"). Ativo só
 * sob o profile dedicado `generation-debug` ([Profile]) — nunca roda quando o backend sobe como
 * servidor de chat (perfil padrão/produção). Invocação:
 *
 * ```
 * SPRING_PROFILES_ACTIVE=generation-debug ./gradlew bootRun --args="--query='qual o nome do protagonista' --books=dom-casmurro"
 * SPRING_PROFILES_ACTIVE=generation-debug ./gradlew bootRun --args="--query='e no capítulo seguinte?' --conversation-id=<uuid-da-conversa>"
 * ```
 *
 * Roda [GenerationService.answer] diretamente — sem HTTP, sem os filtros de `config/` (T3), mesmo
 * espírito de `RetrievalDebugCommand` (consumidor interno). Imprime `event: conversation` (via
 * [GenerationDebugOutputFormatter.formatConversationResolved]) assim que a conversa é
 * resolvida/criada, cada token conforme chega (sem formatação — o texto puro do delta, para que a
 * saída no console leia como a resposta sendo digitada) e, ao final, uma linha indicando que a
 * resposta terminou ([GenerationDebugOutputFormatter.formatDone]).
 *
 * Guard único (CLAUDE.md, "método que orquestra vários passos sequenciais"): resolver conversa,
 * rewrite, retrieval e geração são passos internos de uma única chamada a
 * [GenerationService.answer] — qualquer exceção que ela propague (`ClaudeClientException`,
 * `EmbeddingClientException`/`DataAccessException` do retrieval, `ConversationNotFoundException` de
 * um `--conversation-id` inválido/de outro device) recebe o mesmo tratamento: uma mensagem de erro
 * clara no console, nunca uma exceção crua (mesmo padrão de [com.buscai.backend.retrieval.cli.RetrievalDebugCommand]).
 */
@Component
@Profile("generation-debug")
class GenerationDebugCommand(
    private val generationService: GenerationService,
) : CommandLineRunner {
    override fun run(vararg args: String) {
        when (val result = GenerationDebugArgsParser.parse(args)) {
            is GenerationDebugArgsResult.Error -> println("Erro: ${result.message}")
            is GenerationDebugArgsResult.Parsed -> runGeneration(result.args)
        }
    }

    private fun runGeneration(args: GenerationDebugArgs) {
        try {
            val answer =
                generationService.answer(
                    deviceId = CLI_DEVICE_ID,
                    conversationId = args.conversationId,
                    query = args.query,
                    scope = args.scope,
                    onConversationResolved = { conversationId, isNew ->
                        println(GenerationDebugOutputFormatter.formatConversationResolved(conversationId, isNew))
                    },
                    onToken = { token -> print(token) },
                )
            println(GenerationDebugOutputFormatter.formatDone(answer))
        } catch (e: Exception) {
            // Guard único (CLAUDE.md): ClaudeClientException, EmbeddingClientException/
            // DataAccessException (retrieval) e ConversationNotFoundException (--conversation-id
            // inválido/de outro device) recebem o mesmo tratamento — uma mensagem de erro clara,
            // nunca uma exceção crua no console.
            println(GenerationDebugOutputFormatter.formatError(e))
        }
    }
}
