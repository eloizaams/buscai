package com.buscai.backend.ingestion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TextCleanerTest {
    private val cleaner = TextCleaner()

    // --- removeHyphenation isolado ---

    @Test
    fun `removeHyphenation joins a word broken by line wrap`() {
        val input = "Este é um exemp-\nlo de texto."

        assertEquals("Este é um exemplo de texto.", cleaner.removeHyphenation(input))
    }

    @Test
    fun `removeHyphenation keeps the hyphen when the next line starts with an uppercase letter`() {
        // Mais provável ser um traço/nome composto real do que uma palavra cortada por quebra de
        // linha — critério documentado em TextCleaner.removeHyphenation.
        val input = "Ela nasceu no Rio-\nGrande do Sul."

        assertEquals(input, cleaner.removeHyphenation(input))
    }

    @Test
    fun `removeHyphenation joins a genuine compound word split at the line break, documenting the ambiguous choice`() {
        // Caso ambíguo por natureza: "guarda-chuva" é uma palavra composta real, mas quebrada
        // exatamente no fim da linha ela é indistinguível de uma hifenização de quebra de linha.
        // TextCleaner sempre une nesse caso (perdendo o hífen) — comportamento documentado e
        // testado aqui, não só em comentário.
        val input = "Ele pegou o guarda-\nchuva antes de sair."

        assertEquals("Ele pegou o guardachuva antes de sair.", cleaner.removeHyphenation(input))
    }

    @Test
    fun `removeHyphenation does not touch a hyphen that is not at a line break`() {
        val input = "café-com-leite é bom."

        assertEquals(input, cleaner.removeHyphenation(input))
    }

    // --- removeRepeatedHeaderFooterLines isolado ---

    @Test
    fun `removeRepeatedHeaderFooterLines strips a header and a page-numbered footer repeated across pages`() {
        val pageTexts =
            mapOf(
                1 to
                    """
                    BUSCAI - MANUAL DO USUÁRIO
                    Capítulo 1: Introdução
                    Corpo da página um.
                    Página 1
                    """.trimIndent(),
                2 to
                    """
                    BUSCAI - MANUAL DO USUÁRIO
                    Corpo da página dois.
                    Página 2
                    """.trimIndent(),
                3 to
                    """
                    BUSCAI - MANUAL DO USUÁRIO
                    Corpo da página três.
                    Página 3
                    """.trimIndent(),
            )

        val cleaned = cleaner.removeRepeatedHeaderFooterLines(pageTexts)

        assertEquals("Capítulo 1: Introdução\nCorpo da página um.", cleaned.getValue(1))
        assertEquals("Corpo da página dois.", cleaned.getValue(2))
        assertEquals("Corpo da página três.", cleaned.getValue(3))
    }

    @Test
    fun `removeRepeatedHeaderFooterLines keeps edge lines that do not repeat across pages`() {
        val pageTexts =
            mapOf(
                1 to "Título único da página um\nConteúdo qualquer.",
                2 to "Outro título totalmente diferente\nMais conteúdo.",
            )

        val cleaned = cleaner.removeRepeatedHeaderFooterLines(pageTexts)

        assertEquals(pageTexts, cleaned)
    }

    @Test
    fun `removeRepeatedHeaderFooterLines does nothing with a single page since there is nothing to compare`() {
        val pageTexts = mapOf(1 to "BUSCAI - MANUAL DO USUÁRIO\nConteúdo.\nPágina 1")

        val cleaned = cleaner.removeRepeatedHeaderFooterLines(pageTexts)

        assertEquals(pageTexts, cleaned)
    }

    // --- normalizeSpaces isolado ---

    @Test
    fun `normalizeSpaces collapses horizontal whitespace, trims line edges and preserves paragraph breaks`() {
        val input = "  Primeiro   parágrafo\tcom tabs.  \n\nSegundo   parágrafo.   "

        val expected = "Primeiro parágrafo com tabs.\n\nSegundo parágrafo."

        assertEquals(expected, cleaner.normalizeSpaces(input))
    }

    // --- clean(): caminho feliz combinando as três regras ---

    @Test
    fun `clean applies header-footer removal, dehyphenation and space normalization together`() {
        val pageTexts =
            mapOf(
                1 to
                    """
                    BUSCAI - MANUAL DO USUÁRIO
                    Capítulo 1: Introdução

                    Este é um exemp-
                    lo de texto     com   espaços   irregulares.

                    Um novo parágrafo começa aqui.
                    Página 1
                    """.trimIndent(),
                2 to
                    """
                    BUSCAI - MANUAL DO USUÁRIO
                    Continuação do capítulo.

                    Mais um exemp-
                    lo de linha quebrada.
                    Página 2
                    """.trimIndent(),
            )

        val cleaned = cleaner.clean(pageTexts)

        val expectedPage1 =
            """
            Capítulo 1: Introdução

            Este é um exemplo de texto com espaços irregulares.

            Um novo parágrafo começa aqui.
            """.trimIndent()
        val expectedPage2 =
            """
            Continuação do capítulo.

            Mais um exemplo de linha quebrada.
            """.trimIndent()

        assertEquals(expectedPage1, cleaned.getValue(1))
        assertEquals(expectedPage2, cleaned.getValue(2))
    }
}
