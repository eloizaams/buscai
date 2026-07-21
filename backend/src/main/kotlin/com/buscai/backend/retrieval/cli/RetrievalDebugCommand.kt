package com.buscai.backend.retrieval.cli

import com.buscai.backend.embedding.EmbeddingClientException
import com.buscai.backend.retrieval.RetrievalResult
import com.buscai.backend.retrieval.RetrievalScope
import com.buscai.backend.retrieval.RetrievalService
import com.buscai.backend.retrieval.RetrievedChunk
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Profile
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import java.util.Locale

/** Quantidade máxima de caracteres do trecho de texto impresso por chunk (ver [RetrievalResultFormatter]). */
private const val TEXT_EXCERPT_MAX_CHARS = 200

/**
 * Argumentos já validados de uma invocação de [RetrievalDebugCommand] (ver
 * [RetrievalDebugArgsParser]).
 */
data class RetrievalDebugArgs(
    val query: String,
    val scope: RetrievalScope,
)

/**
 * Resultado do parsing de argumentos de linha de comando de [RetrievalDebugCommand] (T7): [Parsed]
 * segue para [RetrievalService.search]; [Error] carrega uma mensagem já pronta para o operador, sem
 * chamar [RetrievalService.search] — mesmo padrão de `IngestArgsResult`
 * (`ingestion.cli.IngestCommand`, `specs/ingestao-pdf/tasks.md` T10).
 */
sealed class RetrievalDebugArgsResult {
    data class Parsed(
        val args: RetrievalDebugArgs,
    ) : RetrievalDebugArgsResult()

    data class Error(
        val message: String,
    ) : RetrievalDebugArgsResult()
}

/**
 * Parseia e valida os argumentos de linha de comando de `RetrievalDebugCommand` (T7). Formato
 * aceito: `--query=<pergunta>` (obrigatório) e `--books=<id1,id2,...>` (opcional, lista separada
 * por vírgula — define [RetrievalScope.Books]; ausente ou em branco define [RetrievalScope.AllBooks]).
 */
object RetrievalDebugArgsParser {
    private const val QUERY_KEY = "query"
    private const val BOOKS_KEY = "books"

    fun parse(args: Array<out String>): RetrievalDebugArgsResult {
        val options = mutableMapOf<String, String>()

        for (arg in args) {
            if (arg.startsWith("--") && arg.contains("=")) {
                val (key, value) = arg.removePrefix("--").split("=", limit = 2)
                options[key] = value
            } else {
                return RetrievalDebugArgsResult.Error(
                    "Argumento não reconhecido: '$arg'. Use --query=<pergunta> [--books=<id1,id2,...>].",
                )
            }
        }

        val query = options[QUERY_KEY]
        if (query.isNullOrBlank()) {
            return RetrievalDebugArgsResult.Error("Argumento obrigatório ausente: --query=<pergunta>.")
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
                    return RetrievalDebugArgsResult.Error(
                        "Argumento --books informado sem nenhum bookId válido: '$booksArg'.",
                    )
                }
                RetrievalScope.Books(bookIds)
            }

        return RetrievalDebugArgsResult.Parsed(RetrievalDebugArgs(query = query, scope = scope))
    }
}

/**
 * Formata um [RetrievalResult] numa saída legível ao operador — extraído de
 * [RetrievalDebugCommand.run] para ser testável isoladamente, sem subir o Spring context (mesmo
 * padrão de `IngestionOutcomeFormatter`, `ingestion.cli.IngestCommand`).
 */
object RetrievalResultFormatter {
    fun format(result: RetrievalResult): String =
        when (result) {
            RetrievalResult.NoRelevantContext -> "Nenhum contexto relevante encontrado para a pergunta."
            is RetrievalResult.Found ->
                if (result.chunks.isEmpty()) {
                    "Nenhum contexto relevante encontrado para a pergunta."
                } else {
                    result.chunks.joinToString(separator = "\n") { formatChunk(it) }
                }
        }

    private fun formatChunk(chunk: RetrievedChunk): String {
        val referenceInfo = chunk.reference?.let { " · referência: $it" } ?: ""
        val excerpt =
            if (chunk.text.length > TEXT_EXCERPT_MAX_CHARS) {
                "${chunk.text.take(TEXT_EXCERPT_MAX_CHARS)}..."
            } else {
                chunk.text
            }
        val score = "%.4f".format(Locale.ROOT, chunk.score)
        return "[${chunk.bookId}] p.${chunk.page}$referenceInfo · score=$score — $excerpt"
    }
}

/**
 * Entrypoint da CLI de debug de retrieval (T7, `specs/retrieval/plan.md`, seção "Como roda"). Ativo
 * só sob o profile dedicado `retrieval-debug` ([Profile]) — nunca roda quando o backend sobe como
 * servidor de chat (perfil padrão/produção). Invocação:
 *
 * ```
 * SPRING_PROFILES_ACTIVE=retrieval-debug ./gradlew bootRun --args="--query='qual o nome do protagonista' --books=dom-casmurro"
 * SPRING_PROFILES_ACTIVE=retrieval-debug ./gradlew bootRun --args="--query='qual o nome do protagonista'"
 * ```
 *
 * Parseia os argumentos ([RetrievalDebugArgsParser]) antes de chamar [RetrievalService.search] —
 * erro de parsing vira uma mensagem no console, sem tocar o pipeline. Imprime cada
 * [RetrievedChunk] (bookId, página, referência estruturada, score, trecho do texto) ou a mensagem
 * de "sem contexto relevante" ([RetrievalResultFormatter]).
 *
 * Falha ao chamar [RetrievalService.search] (`EmbeddingClientException` — Voyage indisponível/erro
 * de rede — ou `DataAccessException` — Postgres indisponível/erro de query) nunca chega crua ao
 * console (mesmo padrão de [com.buscai.backend.ingestion.cli.IngestionOutcomeFormatter]): vira uma
 * mensagem de erro clara, já que `RetrievalService`/`plan.md` (seção "Contratos entre camadas")
 * deixam essa decisão a cargo de quem consome — aqui, o próprio comando de debug.
 */
@Component
@Profile("retrieval-debug")
class RetrievalDebugCommand(
    private val retrievalService: RetrievalService,
) : CommandLineRunner {
    override fun run(vararg args: String) {
        when (val result = RetrievalDebugArgsParser.parse(args)) {
            is RetrievalDebugArgsResult.Error -> println("Erro: ${result.message}")
            is RetrievalDebugArgsResult.Parsed -> {
                try {
                    val searchResult = retrievalService.search(result.args.query, result.args.scope)
                    println(RetrievalResultFormatter.format(searchResult))
                } catch (e: EmbeddingClientException) {
                    println("Erro ao buscar contexto: ${e.message}")
                } catch (e: DataAccessException) {
                    println("Erro ao buscar contexto: ${e.message}")
                }
            }
        }
    }
}
