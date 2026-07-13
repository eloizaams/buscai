package com.buscai.backend.ingestion

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.File
import java.nio.file.Path

/**
 * Gera PDFs de fixture pequenos em memória/disco (via PDFBox) para os testes de
 * `PdfTextExtractor`/`ScannedPdfDetector`, evitando versionar binários no repositório.
 */
object PdfFixtures {
    /**
     * PDF com [pageCount] páginas, cada uma marcada como "texto" ou "em branco" conforme
     * [blankPages] (páginas 1-based). Páginas "em branco" não têm nenhum operador de texto no
     * content stream — o texto extraído delas é vazio, simulando uma página escaneada.
     */
    fun pdf(
        dir: Path,
        pageCount: Int,
        blankPages: Set<Int> = emptySet(),
    ): File {
        val file = dir.resolve("fixture-${System.nanoTime()}.pdf").toFile()
        PDDocument().use { document ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            for (page in 1..pageCount) {
                val pdPage = PDPage()
                document.addPage(pdPage)
                if (page !in blankPages) {
                    PDPageContentStream(document, pdPage).use { stream ->
                        stream.beginText()
                        stream.setFont(font, 12f)
                        stream.newLineAtOffset(50f, 700f)
                        stream.showText("Texto de teste da página $page com conteúdo suficiente.")
                        stream.endText()
                    }
                }
            }
            document.save(file)
        }
        return file
    }

    /**
     * PDF com uma página por elemento de [pageTexts], cada uma renderizada como uma única linha de
     * texto (um único `Tj`/`showText`) — usado pelos testes de `IngestionService` (T7), que
     * precisam controlar exatamente o texto extraído por página (contagem de tokens/parágrafos)
     * sem risco de quebra de linha automática do PDFBox alterando a contagem.
     */
    fun textPdf(
        dir: Path,
        pageTexts: List<String>,
    ): File {
        val file = dir.resolve("fixture-${System.nanoTime()}.pdf").toFile()
        PDDocument().use { document ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            for (text in pageTexts) {
                val pdPage = PDPage()
                document.addPage(pdPage)
                PDPageContentStream(document, pdPage).use { stream ->
                    stream.beginText()
                    stream.setFont(font, 8f)
                    stream.newLineAtOffset(50f, 700f)
                    stream.showText(text)
                    stream.endText()
                }
            }
            document.save(file)
        }
        return file
    }
}
