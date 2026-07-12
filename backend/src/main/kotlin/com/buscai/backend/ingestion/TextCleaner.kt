package com.buscai.backend.ingestion

import org.springframework.stereotype.Component

/**
 * Número mínimo de páginas necessário para tentar detectar headers/footers repetidos — com uma
 * única página não há o que comparar, então [TextCleaner.removeRepeatedHeaderFooterLines] devolve
 * o texto inalterado nesse caso.
 */
private const val HEADER_FOOTER_MIN_PAGES = 2

/**
 * Fração mínima de páginas em que uma linha de borda (primeira ou última linha não-vazia da
 * página) precisa se repetir — após normalizar números, ver [TextCleaner] `headerFooterSignature`
 * — para ser tratada como header/footer e removida. Maioria simples (>50%) evita remover conteúdo
 * real que coincidentemente se repete em só um par de páginas (ex. um subtítulo curto reaproveitado).
 */
private const val HEADER_FOOTER_REPETITION_RATIO = 0.5

/** Sequência de dígitos, usada para tratar "Página 12" e "Página 13" como a mesma assinatura. */
private val DIGIT_RUN = Regex("\\d+")

/** Qualquer sequência de espaço em branco, usada só ao montar a assinatura de header/footer. */
private val EXTRA_WHITESPACE = Regex("\\s+")

/** Sequência de espaços/tabs (não inclui quebra de linha), alvo da normalização de espaços. */
private val HORIZONTAL_WHITESPACE_RUN = Regex("[ \t]+")

/**
 * Hífen de quebra de linha: letra imediatamente antes do hífen, hífen, quebra de linha, letra
 * minúscula imediatamente depois. Ver [TextCleaner.removeHyphenation] para a justificativa do
 * critério (ambíguo por natureza — decisão documentada ali).
 */
private val LINE_BREAK_HYPHEN = Regex("(?<=\\p{L})-\\n(?=\\p{Ll})")

/**
 * Limpa e normaliza texto extraído de PDF (via [PdfTextExtractor]) antes do chunking (T5, ver
 * `specs/ingestao-pdf/plan.md`): desfaz hifenização de quebra de linha, remove headers/footers
 * repetidos entre páginas e normaliza espaçamento horizontal — preservando linhas em branco, que
 * marcam limite de parágrafo e são usadas pelo `Chunker` (T5) para não cortar um parágrafo ao meio.
 */
@Component
class TextCleaner {
    /**
     * Aplica as três regras, nessa ordem, a um lote de páginas (mesma granularidade de
     * [PdfTextExtractor.extractRange]/[PdfTextExtractor.extractPage]): primeiro remove
     * headers/footers — só isso depende de comparar várias páginas entre si —, depois desfaz
     * hifenização por página, depois normaliza espaços. Hifenização que atravessa uma fronteira de
     * página (última linha de uma página, primeira da próxima) não é tratada aqui — exigiria juntar
     * texto de páginas diferentes, o que conflita com a atribuição de página por chunk (metadado
     * usado nas citações); limitação conhecida, não coberta por esta task.
     */
    fun clean(pageTexts: Map<Int, String>): Map<Int, String> =
        removeRepeatedHeaderFooterLines(pageTexts)
            .mapValues { (_, text) -> normalizeSpaces(removeHyphenation(text)) }

    /**
     * Desfaz hifenização de quebra de linha (ex.: "exemp-\nlo" -> "exemplo").
     *
     * Critério (ambíguo por natureza, ver `specs/ingestao-pdf/tasks.md` T4): uma linha que termina
     * em hífen logo após uma letra, seguida de uma linha que começa com letra minúscula, é tratada
     * como palavra cortada pela quebra de linha do PDF e é sempre unida (hífen e quebra removidos,
     * sem espaço no lugar). Isso também une o caso de uma palavra composta real quebrada nesse
     * mesmo ponto (ex.: "guarda-\nchuva" vira "guardachuva", perdendo o hífen) — não há como
     * distinguir os dois casos de forma confiável só a partir do texto extraído, sem um dicionário;
     * escolhemos sempre unir porque quebra de linha de palavra é o caso disparadamente mais comum
     * em texto de livro corrido, e o resultado unido continua legível e buscável mesmo nos raros
     * casos em que o hífen genuíno de uma palavra composta é perdido.
     *
     * Quando a linha seguinte começa com maiúscula, dígito ou pontuação, o hífen não é removido:
     * nesse caso é mais provável que seja um traço/travessão real (ex. nome composto com maiúscula,
     * fim de frase seguido de outra coisa), não uma palavra cortada — o texto permanece como está
     * (hífen e quebra de linha preservados; [normalizeSpaces] cuida do resto do espaçamento).
     */
    fun removeHyphenation(text: String): String = text.replace(LINE_BREAK_HYPHEN, "")

    /**
     * Remove linhas de borda (primeira e última linha não-vazia de cada página) que se repetem —
     * após normalizar números, ver `headerFooterSignature` — em mais de
     * [HEADER_FOOTER_REPETITION_RATIO] das páginas recebidas; típico de um header com nome do
     * livro/capítulo e um footer com número de página. Por depender de comparar várias páginas
     * entre si, recebe o texto de um lote inteiro (mesmo formato de
     * [PdfTextExtractor.extractRange]), ao contrário de [removeHyphenation]/[normalizeSpaces], que
     * operam página a página. Com menos de [HEADER_FOOTER_MIN_PAGES] páginas não há o que comparar
     * e o texto volta inalterado.
     *
     * Só a primeira e a última linha não-vazia de cada página são candidatas — headers/footers com
     * mais de uma linha não são cobertos por este critério (limitação conhecida, mantida simples e
     * testável).
     */
    fun removeRepeatedHeaderFooterLines(pageTexts: Map<Int, String>): Map<Int, String> {
        if (pageTexts.size < HEADER_FOOTER_MIN_PAGES) return pageTexts

        val linesByPage = pageTexts.mapValues { (_, text) -> text.lines() }

        val headerSignatures =
            linesByPage.values.mapNotNull { lines ->
                firstNonBlankIndex(lines)?.let { headerFooterSignature(lines[it]) }
            }
        val footerSignatures =
            linesByPage.values.mapNotNull { lines ->
                lastNonBlankIndex(lines)?.let { headerFooterSignature(lines[it]) }
            }

        val repeatedHeaders = repeatedSignatures(headerSignatures, pageTexts.size)
        val repeatedFooters = repeatedSignatures(footerSignatures, pageTexts.size)

        return linesByPage.mapValues { (_, lines) ->
            val remaining = lines.toMutableList()
            removeEdgeLineIfRepeated(remaining, fromStart = true, repeatedHeaders)
            removeEdgeLineIfRepeated(remaining, fromStart = false, repeatedFooters)
            remaining.joinToString("\n")
        }
    }

    /**
     * Normaliza espaçamento horizontal: sequências de espaço/tab viram um único espaço, e espaços
     * no início/fim de cada linha são removidos. Não mexe nas quebras de linha em si — uma linha em
     * branco entre duas linhas de texto (separador de parágrafo) permanece uma linha em branco,
     * preservando o limite de parágrafo para o chunking (T5).
     */
    fun normalizeSpaces(text: String): String =
        text
            .lines()
            .joinToString("\n") { line -> line.trim().replace(HORIZONTAL_WHITESPACE_RUN, " ") }

    private fun firstNonBlankIndex(lines: List<String>): Int? = lines.indexOfFirst { it.isNotBlank() }.takeIf { it >= 0 }

    private fun lastNonBlankIndex(lines: List<String>): Int? = lines.indexOfLast { it.isNotBlank() }.takeIf { it >= 0 }

    private fun headerFooterSignature(line: String): String = line.trim().replace(DIGIT_RUN, "#").replace(EXTRA_WHITESPACE, " ")

    private fun repeatedSignatures(
        signatures: List<String>,
        totalPages: Int,
    ): Set<String> =
        signatures
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .filter { (_, count) -> count.toDouble() / totalPages > HEADER_FOOTER_REPETITION_RATIO }
            .keys

    private fun removeEdgeLineIfRepeated(
        lines: MutableList<String>,
        fromStart: Boolean,
        repeatedSignatures: Set<String>,
    ) {
        val index = if (fromStart) firstNonBlankIndex(lines) else lastNonBlankIndex(lines)
        if (index != null && headerFooterSignature(lines[index]) in repeatedSignatures) {
            lines.removeAt(index)
        }
    }
}
