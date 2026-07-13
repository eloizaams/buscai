package com.buscai.backend.ingestion.cli

import com.buscai.backend.ingestion.IngestionOutcome
import com.buscai.backend.ingestion.IngestionService
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.io.File
import java.time.Duration
import java.time.Instant

/** Argumentos já validados de uma invocação de [IngestCommand] (ver [IngestArgsParser]). */
data class IngestArgs(
    val bookId: String,
    val file: File,
    val title: String,
    val reindex: Boolean,
)

/**
 * Resultado do parsing de argumentos de linha de comando de [IngestCommand] (T10): [Parsed] segue
 * para [IngestionService.ingest]; [Error] carrega uma mensagem já pronta para o operador, sem
 * chamar [IngestionService.ingest] (CA7, `specs/ingestao-pdf/spec.md`) — argumento obrigatório
 * ausente/malformado ou arquivo inexistente/ilegível nunca chega a estourar uma exceção crua de
 * `File`/PDFBox no console.
 */
sealed class IngestArgsResult {
    data class Parsed(
        val args: IngestArgs,
    ) : IngestArgsResult()

    data class Error(
        val message: String,
    ) : IngestArgsResult()
}

/**
 * Parseia e valida os argumentos de linha de comando de `IngestCommand` (T10). Formato aceito:
 * `--book-id=<slug>` e `--file=<caminho-do-pdf>` (obrigatórios), `--title=<titulo>` (opcional —
 * quando ausente, usa o próprio `book-id` como título; o operador pode corrigir depois direto no
 * banco, e um título ausente não impede a busca — não vale a pena obrigar um argumento extra só
 * para isso) e `--reindex` (flag booleana: presença = `true`, ADR-0008).
 */
object IngestArgsParser {
    private const val BOOK_ID_KEY = "book-id"
    private const val FILE_KEY = "file"
    private const val TITLE_KEY = "title"
    private const val REINDEX_FLAG = "--reindex"

    fun parse(args: Array<out String>): IngestArgsResult {
        val options = mutableMapOf<String, String>()
        var reindex = false

        for (arg in args) {
            when {
                arg == REINDEX_FLAG -> reindex = true
                arg.startsWith("--") && arg.contains("=") -> {
                    val (key, value) = arg.removePrefix("--").split("=", limit = 2)
                    options[key] = value
                }
                else ->
                    return IngestArgsResult.Error(
                        "Argumento não reconhecido: '$arg'. Use --book-id=<slug> --file=<caminho-do-pdf> " +
                            "[--title=<titulo>] [--reindex].",
                    )
            }
        }

        val bookId = options[BOOK_ID_KEY]
        if (bookId.isNullOrBlank()) {
            return IngestArgsResult.Error("Argumento obrigatório ausente: --book-id=<slug>.")
        }

        val filePath = options[FILE_KEY]
        if (filePath.isNullOrBlank()) {
            return IngestArgsResult.Error("Argumento obrigatório ausente: --file=<caminho-do-pdf>.")
        }

        val file = File(filePath)
        if (!file.exists() || !file.isFile || !file.canRead()) {
            return IngestArgsResult.Error("Arquivo não encontrado ou ilegível: '$filePath'.")
        }

        val title = options[TITLE_KEY]?.takeIf { it.isNotBlank() } ?: bookId

        return IngestArgsResult.Parsed(
            IngestArgs(bookId = bookId, file = file, title = title, reindex = reindex),
        )
    }
}

/**
 * Formata um [IngestionOutcome] numa mensagem clara ao operador (CA3/CA4/CA6/CA7, `spec.md`) —
 * extraído de [IngestCommand.run] para ser testável isoladamente, sem subir o Spring context
 * (T10).
 */
object IngestionOutcomeFormatter {
    fun format(
        outcome: IngestionOutcome,
        elapsed: Duration,
    ): String =
        when (outcome) {
            is IngestionOutcome.Skipped ->
                "Livro '${outcome.bookId}' já ingerido (versão ${outcome.existingVersionId}) — nada a fazer."

            is IngestionOutcome.ReindexRequired ->
                "Livro '${outcome.bookId}' já tem uma versão diferente ingerida (versão " +
                    "${outcome.existingVersionId}) — o arquivo ou a configuração de embedding mudaram desde a " +
                    "última ingestão. Rode de novo com --reindex para reprocessar."

            is IngestionOutcome.Completed ->
                "Ingestão de '${outcome.bookId}' concluída: ${outcome.pageCount} páginas, " +
                    "${outcome.chunkCount} trechos gerados, em ${formatDuration(elapsed)}."

            is IngestionOutcome.Failed ->
                "Falha ao ingerir '${outcome.bookId}': ${outcome.reason}"
        }

    private fun formatDuration(duration: Duration): String {
        val totalSeconds = duration.toMillis() / 1000.0
        return "%.1fs".format(totalSeconds)
    }
}

/**
 * Entrypoint da CLI de ingestão (T10, `specs/ingestao-pdf/plan.md`, seção "Como roda"). Ativo só
 * sob o profile dedicado `ingest` ([Profile]) — nunca roda quando o backend sobe como servidor de
 * chat (perfil padrão/produção). Invocação:
 *
 * ```
 * SPRING_PROFILES_ACTIVE=ingest ./gradlew bootRun --args="--book-id=dom-casmurro --file=/caminho/livro.pdf"
 * SPRING_PROFILES_ACTIVE=ingest ./gradlew bootRun --args="--book-id=dom-casmurro --file=/caminho/livro-corrigido.pdf --reindex"
 * ```
 *
 * Parseia os argumentos ([IngestArgsParser]) antes de chamar [IngestionService.ingest] — erro de
 * parsing vira uma mensagem no console, sem tocar o pipeline. Mede o tempo decorrido em torno da
 * chamada a [IngestionService.ingest] (CA6/CA1) e formata o [IngestionOutcome] resultante
 * ([IngestionOutcomeFormatter]) numa mensagem final ao operador. Progresso incremental durante o
 * processamento (CA6) é dado pelos logs INFO já emitidos por
 * [IngestionService] a cada lote de chunks processado (console via Logback) — não há necessidade
 * de um callback de progresso dedicado para isso.
 */
@Component
@Profile("ingest")
class IngestCommand(
    private val ingestionService: IngestionService,
) : CommandLineRunner {
    override fun run(vararg args: String) {
        when (val result = IngestArgsParser.parse(args)) {
            is IngestArgsResult.Error -> println("Erro: ${result.message}")
            is IngestArgsResult.Parsed -> {
                val parsed = result.args
                val start = Instant.now()
                val outcome =
                    ingestionService.ingest(
                        bookId = parsed.bookId,
                        title = parsed.title,
                        file = parsed.file,
                        reindex = parsed.reindex,
                    )
                val elapsed = Duration.between(start, Instant.now())
                println(IngestionOutcomeFormatter.format(outcome, elapsed))
            }
        }
    }
}
