package com.buscai.backend.ingestion.pdf

import org.springframework.stereotype.Component

/** Ver ADR-0008: limiar de caracteres úteis abaixo do qual uma página é considerada "sem texto". */
private const val MIN_USEFUL_CHARS_PER_PAGE = 20

/** Ver ADR-0008: fração de páginas "sem texto" acima da qual o livro é sinalizado como escaneado. */
private const val SCANNED_PAGE_RATIO_THRESHOLD = 0.9

/**
 * Aplica a regra mensurável de `docs/adr/0008-identidade-e-versionamento-de-livros-ingeridos.md`
 * para decidir se um PDF deve ser tratado como "sem camada de texto" (só imagem escaneada, sem
 * OCR aplicado): uma página é "sem texto" quando tem menos de [MIN_USEFUL_CHARS_PER_PAGE]
 * caracteres úteis (texto extraído, após remover espaços); o livro é sinalizado quando mais de
 * [SCANNED_PAGE_RATIO_THRESHOLD] (90%) das páginas se enquadram nesse caso — o limiar, em vez de
 * 100%, tolera capas/páginas em branco dentro de um livro majoritariamente textual.
 */
@Component
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

    private fun countUsefulChars(pageText: String): Int = pageText.count { !it.isWhitespace() }
}
