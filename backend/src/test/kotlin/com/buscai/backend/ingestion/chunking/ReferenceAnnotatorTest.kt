package com.buscai.backend.ingestion.chunking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReferenceAnnotatorTest {
    private fun unit(text: String) = ParagraphUnit(page = 1, charOffset = 0, text = text, tokenCount = countTokens(text))

    // --- CHAPTER ---

    @Test
    fun `annotate CHAPTER keeps paragraphs before the first header as null`() {
        val units =
            listOf(
                unit("Prefácio do autor."),
                unit("Capítulo I"),
                unit("Texto do primeiro capítulo."),
            )

        val annotated = ReferenceAnnotator.annotate(units, ReferenceType.CHAPTER)

        assertEquals(null, annotated[0].reference)
        assertEquals("Capítulo I", annotated[1].reference)
        assertEquals("Capítulo I", annotated[2].reference)
    }

    @Test
    fun `annotate CHAPTER updates the current reference at every new header, case-insensitive and with roman numerals`() {
        val units =
            listOf(
                unit("CAPÍTULO 1"),
                unit("Primeiro parágrafo."),
                unit("capitulo XII"),
                unit("Segundo parágrafo."),
            )

        val annotated = ReferenceAnnotator.annotate(units, ReferenceType.CHAPTER)

        assertEquals("CAPÍTULO 1", annotated[0].reference)
        assertEquals("CAPÍTULO 1", annotated[1].reference)
        assertEquals("capitulo XII", annotated[2].reference)
        assertEquals("capitulo XII", annotated[3].reference)
    }

    // --- NUMBERED_ITEM ---

    @Test
    fun `annotate NUMBERED_ITEM keeps paragraphs before the first numbered opening as null`() {
        val units =
            listOf(
                unit("Introdução da obra, sem número."),
                unit("157. Que é a morte?"),
                unit("— É a destruição do envoltório material."),
            )

        val annotated = ReferenceAnnotator.annotate(units, ReferenceType.NUMBERED_ITEM)

        assertEquals(null, annotated[0].reference)
        assertEquals("157", annotated[1].reference)
        assertEquals("157", annotated[2].reference)
    }

    @Test
    fun `annotate NUMBERED_ITEM carries the current item number through continuation paragraphs until the next opening`() {
        val units =
            listOf(
                unit("157. Que é a morte?"),
                unit("Nota de continuação sem número novo."),
                unit("158. O que acontece no momento da morte?"),
            )

        val annotated = ReferenceAnnotator.annotate(units, ReferenceType.NUMBERED_ITEM)

        assertEquals("157", annotated[0].reference)
        assertEquals("157", annotated[1].reference)
        assertEquals("158", annotated[2].reference)
    }
}
