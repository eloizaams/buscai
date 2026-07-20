package com.buscai.backend.catalog

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Teste unitário de [BookService.listAvailable] (`specs/cliente-web/tasks.md`, T1), sem subir o
 * contexto Spring — [BookRepository]/[BookVersionRepository] mockados (Mockito), mesmo padrão
 * descrito no `CLAUDE.md` para não depender de Postgres/Testcontainers num teste puramente de
 * lógica de combinação/filtro.
 */
class BookServiceTest {
    private val bookRepository: BookRepository = mock(BookRepository::class.java)
    private val bookVersionRepository: BookVersionRepository = mock(BookVersionRepository::class.java)
    private val bookService = BookService(bookRepository, bookVersionRepository)

    private fun book(
        id: String,
        title: String,
        activeVersionId: UUID?,
    ): Book = Book(id = id, title = title, activeVersionId = activeVersionId)

    private fun version(
        id: UUID,
        bookId: String,
        status: BookVersionStatus,
    ): BookVersion =
        BookVersion(
            id = id,
            bookId = bookId,
            fileHash = "hash-$id".padEnd(64, '0').take(64),
            embeddingModel = "voyage-3",
            embeddingModelVersion = "v1",
            status = status,
        )

    @Test
    fun `livro sem versao ativa fica de fora`() {
        val semVersaoAtiva = book(id = "livro-sem-versao", title = "Sem versão", activeVersionId = null)
        Mockito.`when`(bookRepository.findAll()).thenReturn(listOf(semVersaoAtiva))
        Mockito.`when`(bookVersionRepository.findAllById(emptySet())).thenReturn(emptyList())

        val result = bookService.listAvailable()

        assertEquals(emptyList(), result)
    }

    @Test
    fun `versao ativa ingesting ou failed fica de fora`() {
        val ingestingVersionId = UUID.randomUUID()
        val failedVersionId = UUID.randomUUID()
        val ingestingBook = book(id = "livro-ingesting", title = "Em ingestão", activeVersionId = ingestingVersionId)
        val failedBook = book(id = "livro-failed", title = "Falhou", activeVersionId = failedVersionId)
        Mockito.`when`(bookRepository.findAll()).thenReturn(listOf(ingestingBook, failedBook))
        Mockito
            .`when`(bookVersionRepository.findAllById(setOf(ingestingVersionId, failedVersionId)))
            .thenReturn(
                listOf(
                    version(ingestingVersionId, ingestingBook.id, BookVersionStatus.INGESTING),
                    version(failedVersionId, failedBook.id, BookVersionStatus.FAILED),
                ),
            )

        val result = bookService.listAvailable()

        assertEquals(emptyList(), result)
    }

    @Test
    fun `so livros com versao ativa ready aparecem, ordenados por titulo`() {
        val readyVersionIdA = UUID.randomUUID()
        val readyVersionIdB = UUID.randomUUID()
        val ingestingVersionId = UUID.randomUUID()
        val bookZebra = book(id = "livro-zebra", title = "Zebra", activeVersionId = readyVersionIdA)
        val bookAlfa = book(id = "livro-alfa", title = "Alfa", activeVersionId = readyVersionIdB)
        val bookIngesting = book(id = "livro-ingesting", title = "Em ingestão", activeVersionId = ingestingVersionId)
        Mockito.`when`(bookRepository.findAll()).thenReturn(listOf(bookZebra, bookAlfa, bookIngesting))
        Mockito
            .`when`(bookVersionRepository.findAllById(setOf(readyVersionIdA, readyVersionIdB, ingestingVersionId)))
            .thenReturn(
                listOf(
                    version(readyVersionIdA, bookZebra.id, BookVersionStatus.READY),
                    version(readyVersionIdB, bookAlfa.id, BookVersionStatus.READY),
                    version(ingestingVersionId, bookIngesting.id, BookVersionStatus.INGESTING),
                ),
            )

        val result = bookService.listAvailable()

        assertEquals(listOf(bookAlfa, bookZebra), result)
    }
}
