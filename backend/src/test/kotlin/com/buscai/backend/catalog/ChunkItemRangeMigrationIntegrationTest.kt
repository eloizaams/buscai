package com.buscai.backend.catalog

import com.buscai.backend.ingestion.chunking.ReferenceType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Feature busca-exata-item (specs/busca-exata-item/tasks.md, T2): migration V5 adiciona
// chunk.item_start/item_end + backfill do dado legado a partir de `reference` (colunas usadas pelo
// ramo exato da busca híbrida, T4, ainda não implementado nesta task).
//
// O backfill roda uma única vez, automaticamente, na subida do contexto Spring (Flyway aplica V5
// antes de qualquer teste rodar) — não há como inserir uma linha "pré-migration" no meio do teste.
// Por isso este teste insere linhas de chunk já com reference/reference_type povoados (via
// chunkRepository, que já persiste item_start/item_end nulos, já que os drafts não passam por
// Chunker aqui) e então re-executa manualmente, via jdbcTemplate, a mesma lógica de parse/backfill
// da migration V5 sobre essas linhas — validando a lógica de parse isoladamente do timing do boot.
// Mesmo padrão de Testcontainers de ChunkTextSearchMigrationIntegrationTest.kt (V2).
@Testcontainers
@ActiveProfiles("testcontainers")
@SpringBootTest
class ChunkItemRangeMigrationIntegrationTest {
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

        /** Mesma lógica de parse/backfill de V5__chunk_item_range.sql, reexecutada manualmente pelo teste. */
        private val BACKFILL_SINGLE_VALUE = """
            UPDATE chunk
            SET item_start = reference::INT,
                item_end = reference::INT
            WHERE reference_type = 'NUMBERED_ITEM'
              AND reference ~ '^\d{1,9}${'$'}'
        """

        private val BACKFILL_RANGE = """
            UPDATE chunk
            SET item_start = split_part(reference, '–', 1)::INT,
                item_end = split_part(reference, '–', 2)::INT
            WHERE reference_type = 'NUMBERED_ITEM'
              AND reference ~ '^\d{1,9}–\d{1,9}${'$'}'
              AND split_part(reference, '–', 1)::INT <= split_part(reference, '–', 2)::INT
        """
    }

    @Autowired
    lateinit var bookRepository: BookRepository

    @Autowired
    lateinit var bookVersionRepository: BookVersionRepository

    @Autowired
    lateinit var chunkRepository: ChunkRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private fun persistChunk(
        reference: String?,
        referenceType: ReferenceType?,
    ): UUID {
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
                    text = "texto de teste",
                    embedding = testEmbedding(),
                    reference = reference,
                    referenceType = referenceType,
                ),
            )
        return chunk.id
    }

    private fun runBackfill() {
        jdbcTemplate.update(BACKFILL_SINGLE_VALUE)
        jdbcTemplate.update(BACKFILL_RANGE)
    }

    private fun itemRangeOf(chunkId: UUID): Pair<Int?, Int?> {
        val row =
            jdbcTemplate.queryForMap(
                "select item_start, item_end from chunk where id = ?",
                chunkId,
            )
        return (row["item_start"] as Int?) to (row["item_end"] as Int?)
    }

    @Test
    fun `backfill preenche item_start e item_end iguais para valor unico`() {
        val chunkId = persistChunk(reference = "25", referenceType = ReferenceType.NUMBERED_ITEM)

        runBackfill()

        assertEquals(25 to 25, itemRangeOf(chunkId))
    }

    @Test
    fun `backfill preenche item_start e item_end a partir de uma faixa com en-dash`() {
        val chunkId = persistChunk(reference = "160–164", referenceType = ReferenceType.NUMBERED_ITEM)

        runBackfill()

        assertEquals(160 to 164, itemRangeOf(chunkId))
    }

    @Test
    fun `backfill deixa item_start e item_end nulos quando a faixa tem inicio maior que fim`() {
        val chunkId = persistChunk(reference = "222–5", referenceType = ReferenceType.NUMBERED_ITEM)

        runBackfill()

        val (itemStart, itemEnd) = itemRangeOf(chunkId)
        assertNull(itemStart)
        assertNull(itemEnd)
    }

    @Test
    fun `backfill deixa item_start e item_end nulos quando reference nao eh parseavel`() {
        val chunkId = persistChunk(reference = "apêndice", referenceType = ReferenceType.NUMBERED_ITEM)

        runBackfill()

        val (itemStart, itemEnd) = itemRangeOf(chunkId)
        assertNull(itemStart)
        assertNull(itemEnd)
    }

    @Test
    fun `backfill deixa item_start e item_end nulos quando reference tem mais digitos do que cabe em INT`() {
        // reference = "99999999999" (11 dígitos) bate no padrão "só dígitos" mas estoura o cast
        // ::INT (INT vai até 2147483647, 10 dígitos) — sem o teto \d{1,9} no regex, o UPDATE
        // lançaria erro DENTRO da própria avaliação do WHERE e abortaria a migration inteira
        // (achado crítico do code-reviewer). Com o teto, a linha simplesmente não bate no regex.
        val chunkId = persistChunk(reference = "99999999999", referenceType = ReferenceType.NUMBERED_ITEM)

        runBackfill()

        val (itemStart, itemEnd) = itemRangeOf(chunkId)
        assertNull(itemStart)
        assertNull(itemEnd)
    }

    @Test
    fun `backfill nao mexe em chunks com reference_type CHAPTER`() {
        val chunkId = persistChunk(reference = "Capítulo 1", referenceType = ReferenceType.CHAPTER)

        runBackfill()

        val (itemStart, itemEnd) = itemRangeOf(chunkId)
        assertNull(itemStart)
        assertNull(itemEnd)
    }

    @Test
    fun `indice parcial idx_chunk_item_range existe sobre chunk`() {
        val indexDef =
            jdbcTemplate.queryForList(
                "select indexdef from pg_indexes where tablename = 'chunk' and indexname = 'idx_chunk_item_range'",
                String::class.java,
            )

        assertTrue(indexDef.isNotEmpty(), "índice idx_chunk_item_range deveria existir")
        assertTrue(indexDef.first()?.contains("item_start is not null", ignoreCase = true) == true)
    }

    @Test
    fun `CHECK chk_chunk_item_range rejeita item_start maior que item_end`() {
        val chunkId = persistChunk(reference = "25", referenceType = ReferenceType.NUMBERED_ITEM)

        assertFailsWith<DataIntegrityViolationException> {
            jdbcTemplate.update(
                "update chunk set item_start = 10, item_end = 5 where id = ?",
                chunkId,
            )
        }
    }
}
