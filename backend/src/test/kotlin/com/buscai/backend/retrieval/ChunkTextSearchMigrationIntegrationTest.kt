package com.buscai.backend.retrieval

import com.buscai.backend.catalog.Book
import com.buscai.backend.catalog.BookRepository
import com.buscai.backend.catalog.BookVersion
import com.buscai.backend.catalog.BookVersionRepository
import com.buscai.backend.catalog.BookVersionStatus
import com.buscai.backend.catalog.Chunk
import com.buscai.backend.catalog.ChunkRepository
import com.buscai.backend.catalog.EMBEDDING_DIMENSIONS
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Feature de retrieval (specs/retrieval/tasks.md, T2): migration V2 adiciona chunk.text_search
// (tsvector gerada) + índice GIN, usados pelo ramo léxico da busca híbrida (T3, ainda não
// implementado nesta task). Testcontainers com pgvector/pg16, mesmo padrão de
// catalog.FlywayMigrationIntegrationTest/BookRepositoriesIntegrationTest — H2 do contextLoads não
// roda a migration V2 (tsvector/GIN não é o foco daquele teste, mas o Postgres real é necessário
// de qualquer forma pelo pgvector da migration V1).
@Testcontainers
@ActiveProfiles("testcontainers")
@SpringBootTest
class ChunkTextSearchMigrationIntegrationTest {
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

        private fun testEmbedding(): FloatArray = FloatArray(EMBEDDING_DIMENSIONS) { index -> index / 1000f }
    }

    @Autowired
    lateinit var bookRepository: BookRepository

    @Autowired
    lateinit var bookVersionRepository: BookVersionRepository

    @Autowired
    lateinit var chunkRepository: ChunkRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private fun persistChunkWithText(text: String): UUID {
        val book = bookRepository.save(Book(id = "livro-${UUID.randomUUID()}", title = "Livro de teste"))
        val version =
            bookVersionRepository.save(
                BookVersion(
                    id = UUID.randomUUID(),
                    bookId = book.id,
                    fileHash =
                        UUID
                            .randomUUID()
                            .toString()
                            .replace("-", "")
                            .repeat(2)
                            .take(64),
                    embeddingModel = "voyage-3",
                    embeddingModelVersion = "v1",
                    status = BookVersionStatus.READY,
                ),
            )
        val chunk =
            chunkRepository.save(
                Chunk(
                    id = UUID.randomUUID(),
                    bookVersionId = version.id,
                    page = 1,
                    charOffset = 0,
                    tokenCount = 10,
                    text = text,
                    embedding = testEmbedding(),
                ),
            )
        return chunk.id
    }

    @Test
    fun `text_search encontra a linha por um termo presente no texto do chunk`() {
        val chunkId = persistChunkWithText("O protagonista chama-se Bentinho e mora no Rio de Janeiro.")

        val ids =
            jdbcTemplate.queryForList(
                "select id from chunk where text_search @@ plainto_tsquery('portuguese', ?)",
                UUID::class.java,
                "Bentinho",
            )

        assertTrue(ids.contains(chunkId), "esperava encontrar o chunk $chunkId pelo termo 'Bentinho', encontrou: $ids")
    }

    @Test
    fun `text_search nao encontra a linha por um termo ausente do texto do chunk`() {
        val chunkId = persistChunkWithText("O protagonista chama-se Bentinho e mora no Rio de Janeiro.")

        val ids =
            jdbcTemplate.queryForList(
                "select id from chunk where text_search @@ plainto_tsquery('portuguese', ?)",
                UUID::class.java,
                "elefante",
            )

        assertEquals(false, ids.contains(chunkId))
    }

    @Test
    fun `indice GIN idx_chunk_text_search_gin existe sobre chunk`() {
        val indexDef =
            jdbcTemplate.queryForList(
                "select indexdef from pg_indexes where tablename = 'chunk' and indexname = 'idx_chunk_text_search_gin'",
                String::class.java,
            )

        assertTrue(indexDef.isNotEmpty(), "índice GIN idx_chunk_text_search_gin deveria existir")
        assertTrue(indexDef.first()?.contains("gin", ignoreCase = true) == true)
    }
}
