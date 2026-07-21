package com.buscai.backend.catalog.web

import com.buscai.backend.catalog.BookService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `GET /books` (`specs/cliente-web/plan.md`, "Contratos entre camadas"): catálogo de livros com
 * ingestão completa (versão ativa `READY`), usado pelo cliente web para popular o seletor de
 * escopo de busca. Passa pelos filtros existentes ([com.buscai.backend.config.ApiKeyFilter]/
 * [com.buscai.backend.config.RateLimitFilter]) como qualquer outra rota — nenhuma exceção
 * registrada em [com.buscai.backend.config.ApiSecurityPaths].
 *
 * Depende só de [BookService] (camada de serviço, CLAUDE.md — "sem acesso direto a repositório
 * fora da camada de serviço"), mesmo padrão de `ChatController`/`ConversationController`: este
 * controller nunca injeta `BookRepository`/`BookVersionRepository` diretamente.
 *
 * Sem header `X-Device-Id` — o catálogo não é um recurso por device.
 */
@RestController
class BookController(
    private val bookService: BookService,
) {
    @GetMapping("/books")
    fun listBooks(): List<BookResponse> =
        bookService.listAvailable().map { book ->
            BookResponse(id = book.id, title = book.title)
        }
}
