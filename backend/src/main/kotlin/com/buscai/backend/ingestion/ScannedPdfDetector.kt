package com.buscai.backend.ingestion

import java.io.File

/** Ver ADR-0008: limiar de caracteres úteis abaixo do qual uma página é considerada "sem texto". */
private const val MIN_USEFUL_CHARS_PER_PAGE = 20

/** Ver ADR-0008: fração de páginas "sem texto" acima da qual o livro é sinalizado como escaneado. */
private const val SCANNED_PAGE_RATIO_THRESHOLD = 0.9

/** Tamanho do lote usado internamente ao varrer o PDF inteiro (ver [isScanned]), para nunca manter mais de um lote de páginas em memória por vez (CA2). */
private const val DETECTION_BATCH_SIZE = 20

/**
 * Aplica a regra mensurável de `docs/adr/0008-identidade-e-versionamento-de-livros-ingeridos.md`
 * para decidir se um PDF deve ser tratado como "sem camada de texto" (só imagem escaneada, sem
 * OCR aplicado): uma página é "sem texto" quando tem menos de [MIN_USEFUL_CHARS_PER_PAGE]
 * caracteres úteis (texto extraído, após remover espaços); o livro é sinalizado quando mais de
 * [SCANNED_PAGE_RATIO_THRESHOLD] (90%) das páginas se enquadram nesse caso — o limiar, em vez de
 * 100%, tolera capas/páginas em branco dentro de um livro majoritariamente textual.
 */
class ScannedPdfDetector {
    /** Decide se uma única página é "sem texto" pelo critério do ADR-0008. */
    fun isPageWithoutText(pageText: String): Boolean = countUsefulChars(pageText) < MIN_USEFUL_CHARS_PER_PAGE

    /**
     * Decide se o livro deve ser sinalizado como "sem camada de texto" a partir de contagens já
     * apuradas — usado pelo processamento em lotes da ingestão (T7), que pode somar
     * [pagesWithoutText] e [totalPages] incrementalmente, sem nunca guardar o texto de todas as
     * páginas do livro em memória de uma vez.
     */
    fun isScanned(
        pagesWithoutText: Int,
        totalPages: Int,
    ): Boolean {
        if (totalPages <= 0) return false
        return pagesWithoutText.toDouble() / totalPages > SCANNED_PAGE_RATIO_THRESHOLD
    }

    /**
     * Roda a detecção sobre o PDF inteiro, delegando a extração ao [extractor] em lotes de
     * [DETECTION_BATCH_SIZE] páginas — nunca abre todas as páginas do documento em memória de uma
     * vez (CA2), mesmo fora do pipeline completo de ingestão em lotes.
     */
    fun isScanned(
        file: File,
        extractor: PdfTextExtractor,
    ): Boolean {
        val totalPages = extractor.pageCount(file)
        if (totalPages == 0) return false
        var pagesWithoutText = 0
        var start = 1
        while (start <= totalPages) {
            val end = minOf(start + DETECTION_BATCH_SIZE - 1, totalPages)
            extractor.extractRange(file, start, end).values.forEach { pageText ->
                if (isPageWithoutText(pageText)) pagesWithoutText++
            }
            start = end + 1
        }
        return isScanned(pagesWithoutText, totalPages)
    }

    private fun countUsefulChars(pageText: String): Int = pageText.count { !it.isWhitespace() }
}
