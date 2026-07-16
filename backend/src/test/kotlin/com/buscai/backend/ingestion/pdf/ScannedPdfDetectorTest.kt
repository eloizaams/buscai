package com.buscai.backend.ingestion.pdf

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScannedPdfDetectorTest {
    private val detector = ScannedPdfDetector()

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
}
