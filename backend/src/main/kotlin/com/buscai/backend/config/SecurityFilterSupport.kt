package com.buscai.backend.config

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType

/**
 * Corpo de erro comum a [ApiKeyFilter]/[RateLimitFilter]: JSON simples, sem nenhum detalhe de
 * implementação (stack trace, chave configurada, IP calculado etc — CA9/constitution.md seção 1).
 */
internal fun HttpServletResponse.writeSecurityError(
    status: Int,
    message: String,
) {
    this.status = status
    this.contentType = MediaType.APPLICATION_JSON_VALUE
    this.characterEncoding = "UTF-8"
    this.writer.write("""{"error":"$message"}""")
}
