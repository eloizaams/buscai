package com.buscai.backend.ingestion

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.springframework.stereotype.Component
import java.io.File

/**
 * Extrai texto de um PDF via Apache PDFBox 3.x, sempre restrito a uma página específica ou a uma
 * faixa de páginas — nunca o documento inteiro de uma vez (CA2, `specs/ingestao-pdf/spec.md`).
 * É essa granularidade que permite ao pipeline de ingestão (T7) processar livros grandes em lotes
 * de páginas sem manter o texto extraído do livro inteiro em memória.
 *
 * Páginas são 1-based (mesma convenção do PDFBox), inclusive nas duas pontas de uma faixa.
 */
@Component
class PdfTextExtractor {
    /** Número total de páginas do PDF, sem extrair texto de nenhuma delas. */
    fun pageCount(file: File): Int {
        Loader.loadPDF(file).use { document ->
            return document.numberOfPages
        }
    }

    /** Extrai o texto de uma única página. */
    fun extractPage(
        file: File,
        page: Int,
    ): String = extractRange(file, page, page).getValue(page)

    /**
     * Extrai o texto de uma faixa de páginas (lote). Abre o documento uma única vez para a faixa
     * inteira, mas só materializa em memória o texto das páginas pedidas — usado pelo
     * processamento em lotes da ingestão (`IngestionService`, T7).
     */
    fun extractRange(
        file: File,
        startPage: Int,
        endPage: Int,
    ): Map<Int, String> {
        require(startPage >= 1) { "startPage ($startPage) deve ser >= 1" }
        require(endPage >= startPage) { "endPage ($endPage) deve ser >= startPage ($startPage)" }
        Loader.loadPDF(file).use { document ->
            val totalPages = document.numberOfPages
            require(endPage <= totalPages) {
                "endPage ($endPage) maior que o total de páginas do documento ($totalPages)"
            }
            val pageTexts = LinkedHashMap<Int, String>()
            for (page in startPage..endPage) {
                val stripper = PDFTextStripper()
                stripper.startPage = page
                stripper.endPage = page
                pageTexts[page] = stripper.getText(document)
            }
            return pageTexts
        }
    }
}
