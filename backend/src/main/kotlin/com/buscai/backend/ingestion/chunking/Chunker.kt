package com.buscai.backend.ingestion.chunking

import org.springframework.stereotype.Component
import kotlin.math.ceil

/** Alvo mínimo de tokens por chunk (ADR-0002, seção "Verificação estrutural de chunk" do ADR-0008). */
const val MIN_CHUNK_TOKENS = 300

/** Alvo máximo de tokens por chunk (ADR-0002/ADR-0008) — inclui o overlap herdado do chunk vizinho anterior. */
const val MAX_CHUNK_TOKENS = 800

/** Overlap mínimo aceito entre chunks vizinhos (ADR-0002/ADR-0008), como fração do chunk anterior. */
const val OVERLAP_MIN_RATIO = 0.10

/** Overlap máximo aceito entre chunks vizinhos (ADR-0002/ADR-0008), como fração do chunk anterior. */
const val OVERLAP_MAX_RATIO = 0.20

/**
 * Overlap-alvo usado pelo [Chunker] ao construir os chunks: o meio do intervalo aceito por
 * [ChunkValidator] ([OVERLAP_MIN_RATIO]..[OVERLAP_MAX_RATIO]), para sobrar margem dos dois lados
 * mesmo com arredondamento inteiro de tokens.
 */
private const val OVERLAP_TARGET_RATIO = 0.15

/**
 * Teto de tokens do conteúdo PRÓPRIO de um chunk (sem contar o overlap herdado do vizinho
 * anterior), usado ao agrupar parágrafos — deliberadamente menor que [MAX_CHUNK_TOKENS]. O
 * [MAX_CHUNK_TOKENS] validado por [ChunkValidator] é sobre o chunk final já com overlap
 * prefixado; se o conteúdo próprio já usasse o teto de 800 tokens, prefixar o overlap (até
 * [OVERLAP_TARGET_RATIO] de um vizinho do mesmo tamanho) estouraria esse limite. Dividir por
 * `1 + OVERLAP_TARGET_RATIO` garante que conteúdo próprio + overlap-alvo somados não passem de
 * [MAX_CHUNK_TOKENS], já que ambos os grupos vizinhos usados na conta têm conteúdo próprio
 * limitado por este mesmo teto.
 */
private val MAX_OWN_CONTENT_TOKENS = (MAX_CHUNK_TOKENS / (1.0 + OVERLAP_TARGET_RATIO)).toInt()

/** Define um "token" para fins de chunking: qualquer sequência maximal de caracteres não-espaço (ver [countTokens]). */
private val TOKEN_REGEX = Regex("\\S+")

/**
 * Duas quebras de linha (uma linha em branco entre parágrafos) marcam limite de parágrafo — mesma
 * convenção que [TextCleaner] usa e preserva deliberadamente para o [Chunker] consumir.
 */
private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")

/**
 * Aproximação pragmática de "token" usada por [Chunker] e [ChunkValidator]: contagem de palavras
 * (sequências separadas por espaço em branco), **não** o tokenizer BPE/subword real da Voyage AI
 * (ADR-0003). Justificativa: o tokenizer exato da Voyage não está disponível localmente sem trazer
 * uma dependência pesada, nem vale chamar a API só para contar tokens (gastaria crédito à toa); a
 * contagem de palavras é barata, determinística e correlaciona-se razoavelmente bem com a
 * contagem real de subtokens para texto em português/inglês corrido (tipicamente ~1-1.3 subtokens
 * por palavra) — suficiente para os limites ESTRUTURAIS de [MIN_CHUNK_TOKENS]-[MAX_CHUNK_TOKENS]
 * desta task, que são uma salvaguarda de tamanho de contexto, não um orçamento exato de custo de
 * API (isso é responsabilidade de quem chama a Voyage em T6, que pode reajustar lotes pelo uso
 * real medido na resposta da API).
 */
internal fun countTokens(text: String): Int = TOKEN_REGEX.findAll(text).count()

/** Lista ordenada dos tokens (ver [countTokens]) de [text] — usada por [ChunkValidator] para medir overlap real entre dois chunks. */
internal fun tokenize(text: String): List<String> = TOKEN_REGEX.findAll(text).map { it.value }.toList()

/**
 * Resultado do [Chunker]: um trecho de texto pronto para ser validado ([ChunkValidator]) e depois
 * persistido como `com.buscai.backend.book.Chunk` (T7, depois de gerar o embedding). Carrega só os
 * campos que o próprio chunking sabe preencher — [chapter] fica sempre nulo aqui: detecção de
 * capítulo está fora do escopo desta task, reservada para uma feature futura.
 */
data class ChunkDraft(
    val page: Int,
    val charOffset: Int,
    val tokenCount: Int,
    val text: String,
    val chapter: String? = null,
)

/**
 * Um parágrafo (ou fragmento de um parágrafo maior que [MAX_OWN_CONTENT_TOKENS] — ver
 * [Chunker.splitOversizedParagraph]) já localizado dentro de uma página.
 */
private data class ParagraphUnit(
    val page: Int,
    val charOffset: Int,
    val text: String,
    val tokenCount: Int,
)

/**
 * Agrupa o texto já limpo ([TextCleaner]) de um lote de páginas em chunks de [MIN_CHUNK_TOKENS]-
 * [MAX_CHUNK_TOKENS] tokens, com overlap de [OVERLAP_MIN_RATIO]-[OVERLAP_MAX_RATIO] entre
 * vizinhos, sem cortar um parágrafo ao meio quando evitável (ADR-0002). Ver [countTokens] para a
 * definição de "token" usada.
 *
 * Estratégia (decisão de design central desta task, documentada aqui):
 * 1. O texto de cada página é dividido em parágrafos por linha em branco (mesma convenção que
 *    [TextCleaner] usa para preservá-los), cada um carregando a página e o offset de caractere de
 *    origem dentro dela.
 * 2. Parágrafos são agrupados sequencialmente — nunca dois chunks dividem um mesmo parágrafo em
 *    partes diferentes, com uma exceção no item 3b — até a soma de tokens do grupo não caber mais
 *    um próximo parágrafo sem ultrapassar [MAX_OWN_CONTENT_TOKENS]. **MIN-first (T5b):** o grupo só
 *    pode fechar nesse ponto se já tiver atingido [MIN_CHUNK_TOKENS]; enquanto estiver abaixo do
 *    mínimo, ver 3b. Um grupo abaixo do mínimo só é aceito como está para o **último** grupo do
 *    texto inteiro (fim real do livro, ou livro curto demais) — cabe ao [ChunkValidator] (chamado
 *    depois pela `IngestionService`, T7) rejeitar esse caso, não a este chunker (ADR-0008).
 * 3. Um parágrafo sozinho maior que [MAX_OWN_CONTENT_TOKENS] não cabe inteiro em nenhum chunk —
 *    esse é o caso "de origem" em que este chunker corta um parágrafo, sempre em fronteira de
 *    token/palavra (nunca no meio de uma palavra), em fatias de até [MAX_OWN_CONTENT_TOKENS]
 *    tokens cada (ver [splitOversizedParagraph]). É inevitável: não há como manter tamanho de
 *    chunk limitado E nunca cortar um parágrafo maior que o próprio limite.
 * 3b. Segundo caso de corte, adicionado em T5b: se o próximo parágrafo não cabe inteiro no grupo
 *    atual (item 2) E o grupo ainda está abaixo de [MIN_CHUNK_TOKENS], esse parágrafo (que sozinho
 *    cabe dentro de [MAX_OWN_CONTENT_TOKENS], só não cabe somado ao que já está no grupo) é cortado
 *    na mesma fronteira de token, na medida exata para completar o grupo até
 *    [MAX_OWN_CONTENT_TOKENS] — o restante volta para compor o próximo grupo (ver
 *    [groupUnits]/[cutParagraphAt]). Sem essa exceção, um parágrafo curto seguido de um grande
 *    fecharia o grupo abaixo do mínimo em qualquer ponto do livro, não só no fim.
 * 4. A partir do segundo chunk, cada um recebe um prefixo de overlap: os últimos tokens do
 *    conteúdo PRÓPRIO do chunk anterior (~[OVERLAP_TARGET_RATIO] dele), copiados literalmente. O
 *    overlap não respeita fronteira de parágrafo — é sempre cortado em fronteira de token — porque
 *    é conteúdo duplicado só para dar contexto de continuidade, não conteúdo original perdido (o
 *    parágrafo completo continua íntegro, sem corte, no chunk anterior).
 * 5. `page`/`charOffset` do chunk final sempre refletem onde o conteúdo PRÓPRIO do chunk começa
 *    (ignorando o prefixo de overlap herdado, que pode vir de uma página anterior do mesmo lote)
 *    — é o ponto mais útil para uma citação (livro + página, CA1).
 *
 * Texto vazio (ou um lote de páginas sem nenhum parágrafo não vazio) produz uma lista vazia — não
 * há conteúdo para indexar, e um chunk vazio nunca é útil. Um lote com pouco texto (menor que
 * [MIN_CHUNK_TOKENS] no total) ainda produz um único chunk abaixo do mínimo: cabe ao
 * [ChunkValidator], chamado depois pela `IngestionService` (T7), rejeitar esse caso explicitamente
 * (ADR-0008) — o [Chunker] nunca descarta conteúdo real só para forçar um tamanho mínimo.
 */
@Component
class Chunker {
    fun chunk(pageTexts: Map<Int, String>): List<ChunkDraft> {
        val units =
            pageTexts
                .toSortedMap()
                .flatMap { (page, text) -> splitIntoParagraphs(page, text) }
                .flatMap { unit -> if (unit.tokenCount > MAX_OWN_CONTENT_TOKENS) splitOversizedParagraph(unit) else listOf(unit) }

        val groups = groupUnits(units)

        val drafts = mutableListOf<ChunkDraft>()
        var previousOwnText: String? = null
        var previousOwnTokenCount = 0
        for (group in groups) {
            val ownText = group.joinToString("\n\n") { it.text }
            val ownTokenCount = group.sumOf { it.tokenCount }
            val overlapText = previousOwnText?.let { lastTokensSubstring(it, overlapTargetTokens(previousOwnTokenCount)) }
            val fullText = if (overlapText.isNullOrEmpty()) ownText else "$overlapText\n\n$ownText"
            val first = group.first()
            drafts +=
                ChunkDraft(
                    page = first.page,
                    charOffset = first.charOffset,
                    tokenCount = countTokens(fullText),
                    text = fullText,
                )
            previousOwnText = ownText
            previousOwnTokenCount = ownTokenCount
        }
        return drafts
    }

    private fun overlapTargetTokens(previousOwnTokenCount: Int): Int =
        ceil(previousOwnTokenCount * OVERLAP_TARGET_RATIO).toInt().coerceAtLeast(1)

    /**
     * Agrupa [units] sequencialmente sem exceder [MAX_OWN_CONTENT_TOKENS] por grupo, com estratégia
     * MIN-first (T5b) — ver itens 2/3b do KDoc de [Chunker]: um grupo só fecha abaixo de
     * [MIN_CHUNK_TOKENS] quando não sobra nenhum parágrafo para completá-lo (fim do texto).
     */
    private fun groupUnits(units: List<ParagraphUnit>): List<List<ParagraphUnit>> {
        val groups = mutableListOf<List<ParagraphUnit>>()
        val remaining = ArrayDeque(units)
        while (remaining.isNotEmpty()) {
            val group = mutableListOf<ParagraphUnit>()
            var tokenSum = 0
            while (remaining.isNotEmpty() && tokenSum + remaining.first().tokenCount <= MAX_OWN_CONTENT_TOKENS) {
                val unit = remaining.removeFirst()
                group += unit
                tokenSum += unit.tokenCount
            }
            // MIN-first: se o grupo ainda não chegou ao mínimo e sobrou um parágrafo que não coube
            // inteiro, corta-o na fronteira de token necessária para completar o grupo até o teto,
            // devolvendo o restante para o próximo grupo — ver item 3b do KDoc de Chunker.
            if (tokenSum < MIN_CHUNK_TOKENS && remaining.isNotEmpty()) {
                val next = remaining.removeFirst()
                val (piece, remainder) = cutParagraphAt(next, MAX_OWN_CONTENT_TOKENS - tokenSum)
                group += piece
                if (remainder != null) remaining.addFirst(remainder)
            }
            // Salvaguarda contra loop infinito: não deveria ocorrer, pois splitOversizedParagraph já
            // garante que nenhuma unidade sozinha excede MAX_OWN_CONTENT_TOKENS (o primeiro parágrafo
            // restante sempre cabe sozinho no laço acima).
            if (group.isEmpty()) group += remaining.removeFirst()
            groups += group
        }
        return groups
    }

    private fun splitIntoParagraphs(
        page: Int,
        text: String,
    ): List<ParagraphUnit> {
        val units = mutableListOf<ParagraphUnit>()
        var start = 0
        for (match in PARAGRAPH_BREAK.findAll(text)) {
            addParagraphIfNotBlank(page, text, start, match.range.first, units)
            start = match.range.last + 1
        }
        addParagraphIfNotBlank(page, text, start, text.length, units)
        return units
    }

    private fun addParagraphIfNotBlank(
        page: Int,
        text: String,
        start: Int,
        end: Int,
        out: MutableList<ParagraphUnit>,
    ) {
        if (start >= end) return
        val raw = text.substring(start, end)
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        val leadingWhitespace = raw.length - raw.trimStart().length
        out += ParagraphUnit(page, start + leadingWhitespace, trimmed, countTokens(trimmed))
    }

    /** Corta um parágrafo maior que [MAX_OWN_CONTENT_TOKENS] em fatias de até esse tamanho, sempre em fronteira de token. */
    private fun splitOversizedParagraph(unit: ParagraphUnit): List<ParagraphUnit> {
        val pieces = mutableListOf<ParagraphUnit>()
        var remainder: ParagraphUnit? = unit
        while (remainder != null) {
            val (piece, rest) = cutParagraphAt(remainder, MAX_OWN_CONTENT_TOKENS)
            pieces += piece
            remainder = rest
        }
        return pieces
    }

    /**
     * Corta [unit] no limite de token [firstPieceTokenCount] (ou no fim do parágrafo, se ele tiver
     * menos tokens que isso), sempre em fronteira de token/palavra — usado tanto por
     * [splitOversizedParagraph] (parágrafo sozinho maior que [MAX_OWN_CONTENT_TOKENS]) quanto pela
     * estratégia MIN-first de [groupUnits] (T5b, parágrafo que não coube inteiro no grupo atual
     * ainda abaixo de [MIN_CHUNK_TOKENS]). Devolve o primeiro pedaço e o restante (`null` se
     * [firstPieceTokenCount] cobrir o parágrafo inteiro).
     */
    private fun cutParagraphAt(
        unit: ParagraphUnit,
        firstPieceTokenCount: Int,
    ): Pair<ParagraphUnit, ParagraphUnit?> {
        val matches = TOKEN_REGEX.findAll(unit.text).toList()
        val take = firstPieceTokenCount.coerceIn(1, matches.size)
        val pieceEndExclusive = matches[take - 1].range.last + 1
        val piece =
            ParagraphUnit(
                page = unit.page,
                charOffset = unit.charOffset,
                text = unit.text.substring(0, pieceEndExclusive),
                tokenCount = take,
            )
        if (take >= matches.size) return piece to null
        val remainderRaw = unit.text.substring(pieceEndExclusive)
        val remainderTrimmed = remainderRaw.trimStart()
        if (remainderTrimmed.isEmpty()) return piece to null
        val leadingWhitespace = remainderRaw.length - remainderTrimmed.length
        val remainder =
            ParagraphUnit(
                page = unit.page,
                charOffset = unit.charOffset + pieceEndExclusive + leadingWhitespace,
                text = remainderTrimmed,
                tokenCount = matches.size - take,
            )
        return piece to remainder
    }

    /** Últimos [tokenCount] tokens de [text], como substring literal (preserva espaçamento/quebras internas). */
    private fun lastTokensSubstring(
        text: String,
        tokenCount: Int,
    ): String {
        if (tokenCount <= 0) return ""
        val matches = TOKEN_REGEX.findAll(text).toList()
        if (matches.isEmpty()) return ""
        val take = minOf(tokenCount, matches.size)
        val start = matches[matches.size - take].range.first
        return text.substring(start)
    }
}
