package com.buscai.backend.ingestion.chunking

/**
 * Cabeçalho de capítulo (ADR-0013, "Detecção de referência"): casa a primeira linha de um
 * parágrafo do tipo `"Capítulo XII"`/`"CAPÍTULO 12"` (numeral romano ou arábico), case-insensitive.
 */
private val CHAPTER_HEADER_REGEX = Regex("^CAP[ÍI]TULO\\s+([IVXLCDM]+|\\d+)", RegexOption.IGNORE_CASE)

/** Abertura de item numerado (ADR-0013): `"157. Que é a morte?"` — o número no início do parágrafo. */
private val NUMBERED_ITEM_OPENING_REGEX = Regex("^\\s*(\\d+)\\.\\s")

/**
 * Anota cada [ParagraphUnit] de [units] (na ordem em que aparecem — página a página, parágrafo a
 * parágrafo) com a referência estrutural corrente, conforme [type] (ADR-0013, "Detecção de
 * referência"). Função pura: sem estado além do que é local ao laço, testável com uma lista
 * sintética de parágrafos.
 *
 * - [ReferenceType.CHAPTER]: mantém como estado o último cabeçalho de capítulo visto
 *   ([CHAPTER_HEADER_REGEX], casado contra a primeira linha do parágrafo) — o próprio texto do
 *   cabeçalho (ex. `"Capítulo XII"`) vira a referência daquele parágrafo e de todo parágrafo
 *   seguinte, até o próximo cabeçalho.
 * - [ReferenceType.NUMBERED_ITEM]: mantém como estado o último número de abertura de item visto
 *   ([NUMBERED_ITEM_OPENING_REGEX]) — o número capturado (sem o ponto) vira a referência daquele
 *   parágrafo e de todo parágrafo seguinte que não abrir com um novo número (nota/continuação do
 *   mesmo item), até o próximo parágrafo que abrir com número.
 *
 * Em ambos os casos, parágrafos antes do primeiro marcador ficam com `reference = null`.
 */
internal object ReferenceAnnotator {
    fun annotate(
        units: List<ParagraphUnit>,
        type: ReferenceType,
    ): List<ParagraphUnit> {
        var current: String? = null
        return units.map { unit ->
            current = nextReference(unit.text, type, current)
            unit.copy(reference = current)
        }
    }

    private fun nextReference(
        text: String,
        type: ReferenceType,
        current: String?,
    ): String? =
        when (type) {
            ReferenceType.CHAPTER -> {
                val firstLine = text.substringBefore('\n').trim()
                if (CHAPTER_HEADER_REGEX.containsMatchIn(firstLine)) firstLine else current
            }
            ReferenceType.NUMBERED_ITEM -> {
                val match = NUMBERED_ITEM_OPENING_REGEX.find(text)
                if (match != null) match.groupValues[1] else current
            }
        }
}
