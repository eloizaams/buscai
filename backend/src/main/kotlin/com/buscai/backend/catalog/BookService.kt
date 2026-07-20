package com.buscai.backend.catalog

import org.springframework.stereotype.Service

/**
 * Camada de serviço do catálogo (`specs/cliente-web/plan.md`, seção "Módulo e localização") — não
 * existia antes desta feature; `BookRepository`/`BookVersionRepository` só eram consumidos direto
 * por `IngestionService` e `RetrievalService`, cada um com sua própria lógica de filtragem.
 *
 * Deliberadamente não reaproveita `RetrievalService.resolveEligibleVersions`: aquele filtro inclui
 * uma checagem de compatibilidade de `embeddingModel`/`embeddingModelVersion` (relevante só para
 * decidir o que entra numa busca vetorial), que não faz sentido para "listar livros para o usuário
 * escolher escopo" — ver `plan.md`, seção "Por que um `BookService` novo".
 */
@Service
class BookService(
    private val bookRepository: BookRepository,
    private val bookVersionRepository: BookVersionRepository,
) {
    /** Livros cuja versão ativa terminou de indexar ([BookVersionStatus.READY]). Ordenado por título. */
    fun listAvailable(): List<Book> {
        val activeVersionIdToBook =
            bookRepository
                .findAll()
                .mapNotNull { book -> book.activeVersionId?.let { it to book } }
                .toMap()
        val readyVersionIds =
            bookVersionRepository
                .findAllById(activeVersionIdToBook.keys)
                .filter { it.status == BookVersionStatus.READY }
                .map { it.id }
                .toSet()
        return activeVersionIdToBook
            .filterKeys { it in readyVersionIds }
            .values
            .sortedBy { it.title }
    }
}
