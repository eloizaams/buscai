package com.buscai.backend.ingestion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PdfTextExtractorTest {
    private val extractor = PdfTextExtractor()

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `pageCount reflects the number of pages in the document`() {
        val file = PdfFixtures.pdf(tempDir, pageCount = 5)

        assertEquals(5, extractor.pageCount(file))
    }

    @Test
    fun `extractPage returns only the text of the requested page`() {
        val file = PdfFixtures.pdf(tempDir, pageCount = 3)

        val page2Text = extractor.extractPage(file, 2)

        assertTrue(page2Text.contains("página 2"))
        assertTrue(!page2Text.contains("página 1"))
        assertTrue(!page2Text.contains("página 3"))
    }

    @Test
    fun `extractRange returns text keyed by page number for the whole batch`() {
        val file = PdfFixtures.pdf(tempDir, pageCount = 4)

        val batch = extractor.extractRange(file, 2, 4)

        assertEquals(setOf(2, 3, 4), batch.keys)
        assertTrue(batch.getValue(2).contains("página 2"))
        assertTrue(batch.getValue(3).contains("página 3"))
        assertTrue(batch.getValue(4).contains("página 4"))
    }

    @Test
    fun `extractRange rejects a range beyond the total number of pages`() {
        val file = PdfFixtures.pdf(tempDir, pageCount = 2)

        assertThrows(IllegalArgumentException::class.java) {
            extractor.extractRange(file, 1, 3)
        }
    }

    @Test
    fun `extractRange rejects an invalid page ordering`() {
        val file = PdfFixtures.pdf(tempDir, pageCount = 2)

        assertThrows(IllegalArgumentException::class.java) {
            extractor.extractRange(file, 2, 1)
        }
    }
}
