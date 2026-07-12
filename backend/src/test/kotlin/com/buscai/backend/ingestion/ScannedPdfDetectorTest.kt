package com.buscai.backend.ingestion

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ScannedPdfDetectorTest {
    private val detector = ScannedPdfDetector()
    private val extractor = PdfTextExtractor()

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `isPageWithoutText flags pages with less than 20 useful characters`() {
        assertTrue(detector.isPageWithoutText(""))
        assertTrue(detector.isPageWithoutText("   \n\n  "))
        assertTrue(detector.isPageWithoutText("curto")) // 5 caracteres úteis
        assertFalse(detector.isPageWithoutText("Este texto tem claramente mais de vinte caracteres úteis."))
    }

    @Test
    fun `isPageWithoutText boundary is exactly 20 useful characters`() {
        val exactlyTwenty = "a".repeat(20)
        val nineteen = "a".repeat(19)

        assertFalse(detector.isPageWithoutText(exactlyTwenty))
        assertTrue(detector.isPageWithoutText(nineteen))
    }

    @Test
    fun `isScanned by counts flags a book above the ninety percent threshold`() {
        assertTrue(detector.isScanned(pagesWithoutText = 91, totalPages = 100))
        assertFalse(detector.isScanned(pagesWithoutText = 90, totalPages = 100))
        assertFalse(detector.isScanned(pagesWithoutText = 0, totalPages = 0))
    }

    @Test
    fun `a real PDF with extractable text on every page is not flagged as scanned`() {
        val file = PdfFixtures.pdf(tempDir, pageCount = 10)

        assertFalse(detector.isScanned(file, extractor))
    }

    @Test
    fun `a simulated scanned PDF with blank pages is flagged as scanned`() {
        val file = PdfFixtures.pdf(tempDir, pageCount = 10, blankPages = (1..10).toSet())

        assertTrue(detector.isScanned(file, extractor))
    }

    @Test
    fun `a mostly textual PDF with a blank cover page is not flagged as scanned`() {
        // 20 páginas, só a capa (página 1) em branco — 1 de 20 = 5%, bem abaixo do limiar de 90%.
        val file = PdfFixtures.pdf(tempDir, pageCount = 20, blankPages = setOf(1))

        assertFalse(detector.isScanned(file, extractor))
    }
}
