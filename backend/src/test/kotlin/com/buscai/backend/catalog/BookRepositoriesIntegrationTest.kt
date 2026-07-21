package com.buscai.backend.catalog

import com.buscai.backend.ingestion.chunking.ReferenceType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Mesmo padrão de FlywayMigrationIntegrationTest: Postgres real via Testcontainers, já que a
// migration V1 usa pgvector/HNSW (não suportado pelo H2 do contextLoads).
@Testcontainers
@ActiveProfiles("testcontainers")
@SpringBootTest
class BookRepositoriesIntegrationTest {
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

    @Test
    fun `salva e recupera um Book`() {
        val book = Book(id = "dom-casmurro", title = "Dom Casmurro")
        bookRepository.save(book)

        val found = bookRepository.findById("dom-casmurro").orElseThrow()
        assertEquals("Dom Casmurro", found.title)
        assertNull(found.activeVersionId)
        assertNotNull(found.createdAt)
        assertNotNull(found.updatedAt)
    }

    @Test
    fun `salva e recupera uma BookVersion`() {
        val book = bookRepository.save(Book(id = "memorias-postumas", title = "Memórias Póstumas"))
        val versionId = UUID.randomUUID()
        val version =
            BookVersion(
                id = versionId,
                bookId = book.id,
                fileHash = "a".repeat(64),
                embeddingModel = "voyage-3",
                embeddingModelVersion = "v1",
                status = BookVersionStatus.INGESTING,
            )
        bookVersionRepository.save(version)

        val found = bookVersionRepository.findById(versionId).orElseThrow()
        assertEquals(book.id, found.bookId)
        assertEquals("a".repeat(64), found.fileHash)
        assertEquals("voyage-3", found.embeddingModel)
        assertEquals("v1", found.embeddingModelVersion)
        assertEquals(BookVersionStatus.INGESTING, found.status)
        assertNull(found.pageCount)
        assertNull(found.chunkCount)
        assertNull(found.completedAt)
        assertNotNull(found.startedAt)

        found.status = BookVersionStatus.READY
        found.pageCount = 250
        found.chunkCount = 900
        found.completedAt = Instant.now()
        bookVersionRepository.save(found)

        val updated = bookVersionRepository.findById(versionId).orElseThrow()
        assertEquals(BookVersionStatus.READY, updated.status)
        assertEquals(250, updated.pageCount)
        assertEquals(900, updated.chunkCount)
        assertNotNull(updated.completedAt)

        // Book.activeVersionId só é setado depois que a versão existe (swap atômico, ADR-0008).
        book.activeVersionId = versionId
        bookRepository.save(book)
        val bookWithActiveVersion = bookRepository.findById(book.id).orElseThrow()
        assertEquals(versionId, bookWithActiveVersion.activeVersionId)
    }

    @Test
    fun `salva e recupera um Chunk com embedding de 1024 dimensoes`() {
        val book = bookRepository.save(Book(id = "iracema", title = "Iracema"))
        val version =
            bookVersionRepository.save(
                BookVersion(
                    id = UUID.randomUUID(),
                    bookId = book.id,
                    fileHash = "b".repeat(64),
                    embeddingModel = "voyage-3",
                    embeddingModelVersion = "v1",
                    status = BookVersionStatus.INGESTING,
                ),
            )

        val embedding = testEmbedding()
        val chunkId = UUID.randomUUID()
        val chunk =
            Chunk(
                id = chunkId,
                bookVersionId = version.id,
                page = 12,
                charOffset = 340,
                tokenCount = 512,
                text = "Trecho de teste do livro.",
                embedding = embedding,
                reference = "Capítulo 1",
                referenceType = ReferenceType.CHAPTER,
            )
        chunkRepository.save(chunk)

        val found = chunkRepository.findById(chunkId).orElseThrow()
        assertEquals(version.id, found.bookVersionId)
        assertEquals(12, found.page)
        assertEquals(340, found.charOffset)
        assertEquals(512, found.tokenCount)
        assertEquals("Trecho de teste do livro.", found.text)
        assertEquals("Capítulo 1", found.reference)
        assertEquals(ReferenceType.CHAPTER, found.referenceType)
        assertEquals(EMBEDDING_DIMENSIONS, found.embedding.size)
        assertTrue(embedding.contentEquals(found.embedding), "embedding recuperado deveria ser igual ao gravado")
        assertNotNull(found.createdAt)
    }

    @Test
    fun `salva e recupera um Chunk sem reference nem referenceType (campos opcionais)`() {
        val book = bookRepository.save(Book(id = "helena", title = "Helena"))
        val version =
            bookVersionRepository.save(
                BookVersion(
                    id = UUID.randomUUID(),
                    bookId = book.id,
                    fileHash = "c".repeat(64),
                    embeddingModel = "voyage-3",
                    embeddingModelVersion = "v1",
                    status = BookVersionStatus.INGESTING,
                ),
            )

        val chunk =
            chunkRepository.save(
                Chunk(
                    id = UUID.randomUUID(),
                    bookVersionId = version.id,
                    page = 1,
                    charOffset = 0,
                    tokenCount = 300,
                    text = "Início do livro.",
                    embedding = testEmbedding(),
                ),
            )

        val found = chunkRepository.findById(chunk.id).orElseThrow()
        assertNull(found.reference)
        assertNull(found.referenceType)
    }

    @Test
    fun `finder do ADR-0008 encontra a BookVersion quando a chave de gatilho bate exatamente`() {
        val book = bookRepository.save(Book(id = "quincas-borba", title = "Quincas Borba"))
        val version =
            bookVersionRepository.save(
                BookVersion(
                    id = UUID.randomUUID(),
                    bookId = book.id,
                    fileHash = "d".repeat(64),
                    embeddingModel = "voyage-3",
                    embeddingModelVersion = "v1",
                    status = BookVersionStatus.READY,
                ),
            )

        val found =
            bookVersionRepository.findByBookIdAndFileHashAndEmbeddingModelAndEmbeddingModelVersion(
                bookId = book.id,
                fileHash = "d".repeat(64),
                embeddingModel = "voyage-3",
                embeddingModelVersion = "v1",
            )

        assertNotNull(found)
        assertEquals(version.id, found.id)
    }

    @Test
    fun `finder do ADR-0008 nao encontra nada quando o bookId nao existe`() {
        val found =
            bookVersionRepository.findByBookIdAndFileHashAndEmbeddingModelAndEmbeddingModelVersion(
                bookId = "livro-inexistente-${UUID.randomUUID()}",
                fileHash = "e".repeat(64),
                embeddingModel = "voyage-3",
                embeddingModelVersion = "v1",
            )

        assertNull(found)
    }

    @Test
    fun `finder do ADR-0008 nao encontra quando o fileHash mudou (reindex necessario)`() {
        val book = bookRepository.save(Book(id = "grande-sertao", title = "Grande Sertão Veredas"))
        bookVersionRepository.save(
            BookVersion(
                id = UUID.randomUUID(),
                bookId = book.id,
                fileHash = "f".repeat(64),
                embeddingModel = "voyage-3",
                embeddingModelVersion = "v1",
                status = BookVersionStatus.READY,
            ),
        )

        val found =
            bookVersionRepository.findByBookIdAndFileHashAndEmbeddingModelAndEmbeddingModelVersion(
                bookId = book.id,
                // hash diferente do que foi persistido para essa versão.
                fileHash = "0".repeat(64),
                embeddingModel = "voyage-3",
                embeddingModelVersion = "v1",
            )

        assertNull(found)
    }

    @Test
    fun `finder do ADR-0008 nao encontra quando a versao do modelo de embedding mudou`() {
        val book = bookRepository.save(Book(id = "capitu", title = "Capitu"))
        bookVersionRepository.save(
            BookVersion(
                id = UUID.randomUUID(),
                bookId = book.id,
                fileHash = "1".repeat(64),
                embeddingModel = "voyage-3",
                embeddingModelVersion = "v1",
                status = BookVersionStatus.READY,
            ),
        )

        val found =
            bookVersionRepository.findByBookIdAndFileHashAndEmbeddingModelAndEmbeddingModelVersion(
                bookId = book.id,
                fileHash = "1".repeat(64),
                embeddingModel = "voyage-3",
                // versão do modelo diferente da que foi persistida.
                embeddingModelVersion = "v2",
            )

        assertNull(found)
    }
}
