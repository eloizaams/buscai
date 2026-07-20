package com.buscai.backend.catalog.web

import com.buscai.backend.catalog.Book
import com.buscai.backend.catalog.BookRepository
import com.buscai.backend.catalog.BookVersion
import com.buscai.backend.catalog.BookVersionRepository
import com.buscai.backend.catalog.BookVersionStatus
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import kotlin.test.assertEquals

private const val VALID_API_KEY = "book-controller-test-key"

/**
 * Teste HTTP de `GET /books` (`specs/cliente-web/tasks.md`, T1), subindo o contexto Spring inteiro
 * (filtros de `ApiKeyFilter`/`RateLimitFilter` incluídos) contra um Postgres real via
 * Testcontainers — mesmo padrão de `ConversationControllerTest`/`ChatControllerTest`. Fixtures são
 * inseridas diretamente via `BookRepository`/`BookVersionRepository`, sem passar por
 * `IngestionService`.
 */
@Testcontainers
@ActiveProfiles("testcontainers")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["buscai.api-key=$VALID_API_KEY"])
class BookControllerTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16"))

        @DynamicPropertySource
        @JvmStatic
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var bookRepository: BookRepository

    @Autowired
    lateinit var bookVersionRepository: BookVersionRepository

    private fun persistBook(
        title: String,
        versionStatus: BookVersionStatus?,
    ): Book {
        val suffix = UUID.randomUUID()
        val book = bookRepository.save(Book(id = "livro-$suffix", title = title))
        if (versionStatus == null) return book

        val version =
            bookVersionRepository.save(
                BookVersion(
                    id = UUID.randomUUID(),
                    bookId = book.id,
                    fileHash =
                        suffix
                            .toString()
                            .replace("-", "")
                            .repeat(2)
                            .take(64),
                    embeddingModel = "voyage-3",
                    embeddingModelVersion = "v1",
                    status = versionStatus,
                ),
            )
        book.activeVersionId = version.id
        return bookRepository.save(book)
    }

    @Test
    fun `requisicao sem X-Api-Key valida devolve 401`() {
        val result = mockMvc.perform(get("/books")).andReturn()

        assertEquals(401, result.response.status)
    }

    @Test
    fun `requisicao com X-Api-Key valida devolve so livros com versao ativa ready, no formato esperado`() {
        val readyBook = persistBook("Livro Pronto ${UUID.randomUUID()}", BookVersionStatus.READY)
        val ingestingBook = persistBook("Livro Em Ingestão ${UUID.randomUUID()}", BookVersionStatus.INGESTING)
        val semVersaoBook = persistBook("Livro Sem Versão ${UUID.randomUUID()}", versionStatus = null)

        mockMvc
            .perform(get("/books").header("X-Api-Key", VALID_API_KEY))
            .andExpect(jsonPath("$[?(@.id == '${readyBook.id}')].title").value(readyBook.title))
            .andExpect(jsonPath("$[?(@.id == '${ingestingBook.id}')]").isEmpty)
            .andExpect(jsonPath("$[?(@.id == '${semVersaoBook.id}')]").isEmpty)
    }
}
