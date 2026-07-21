package com.buscai.backend.retrieval.search

import com.buscai.backend.catalog.Book
import com.buscai.backend.catalog.BookRepository
import com.buscai.backend.catalog.BookVersion
import com.buscai.backend.catalog.BookVersionRepository
import com.buscai.backend.catalog.BookVersionStatus
import com.buscai.backend.catalog.Chunk
import com.buscai.backend.catalog.ChunkRepository
import com.buscai.backend.catalog.EMBEDDING_DIMENSIONS
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Feature de retrieval (specs/retrieval/tasks.md, T3). Postgres real via Testcontainers, mesmo
// padrão de ChunkTextSearchMigrationIntegrationTest/BookRepositoriesIntegrationTest — a query
// híbrida usa pgvector (<=>, índice HNSW) e o tsvector/GIN de V2, nenhum dos dois suportado pelo
// H2 do contextLoads.
//
// Vetores de fixture: 1024 dimensões (EMBEDDING_DIMENSIONS), sempre um "one-hot" (uma posição em
// 1.0, resto 0.0) — vetores unitários ortogonais entre si por construção, então a cosine
// similarity esperada é sempre um número exato e fácil de verificar manualmente: 1.0 quando os
// vetores são o mesmo one-hot (mesma posição), 0.0 quando as posições são diferentes (dot product
// = 0 entre vetores ortogonais). Isso evita ter que calcular cosine similarity de vetores
// "realistas" à mão só para escrever a asserção (c) do teste.
@Testcontainers
@ActiveProfiles("testcontainers")
@SpringBootTest
class HybridSearchDaoIntegrationTest {
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

        private fun oneHotEmbedding(hotIndex: Int): FloatArray =
            FloatArray(EMBEDDING_DIMENSIONS) { index -> if (index == hotIndex) 1f else 0f }
    }

    @Autowired
    lateinit var bookRepository: BookRepository

    @Autowired
    lateinit var bookVersionRepository: BookVersionRepository

    @Autowired
    lateinit var chunkRepository: ChunkRepository

    @Autowired
    lateinit var hybridSearchDao: HybridSearchDao

    private fun persistReadyVersion(bookId: String): BookVersion {
        bookRepository.save(Book(id = bookId, title = "Livro $bookId"))
        return bookVersionRepository.save(
            BookVersion(
                id = UUID.randomUUID(),
                bookId = bookId,
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
    }

    private fun persistChunk(
        bookVersionId: UUID,
        embedding: FloatArray,
        text: String,
        page: Int = 1,
        charOffset: Int = 0,
        reference: String? = null,
        referenceType: ReferenceType? = null,
    ): Chunk =
        chunkRepository.save(
            Chunk(
                id = UUID.randomUUID(),
                bookVersionId = bookVersionId,
                page = page,
                charOffset = charOffset,
                tokenCount = 10,
                text = text,
                embedding = embedding,
                reference = reference,
                referenceType = referenceType,
            ),
        )

    @Test
    fun `chunk com match lexico exato aparece mesmo com embedding distante da query (CA3)`() {
        val version = persistReadyVersion("livro-a-${UUID.randomUUID()}")
        val queryVector = oneHotEmbedding(0)
        // Mais próximo do vetor da query: ocupa o único lugar do ramo vetorial (vectorCandidates = 1).
        val chunkPertoDoVetor = persistChunk(version.id, oneHotEmbedding(0), "Texto neutro sem termo especial.")
        // Embedding ortogonal (distante) do vetor da query, mas contém o termo buscado literalmente.
        val chunkComTermo =
            persistChunk(
                version.id,
                oneHotEmbedding(2),
                "O protagonista chama-se Bentinho e mora no Rio de Janeiro.",
                charOffset = 42,
            )

        val result =
            hybridSearchDao.search(
                queryVector = queryVector,
                queryText = "Bentinho",
                eligibleBookVersionIds = listOf(version.id),
                vectorCandidates = 1,
                lexicalCandidates = 10,
                rrfK = 60,
            )

        val ids = result.map { it.chunkId }
        assertTrue(ids.contains(chunkComTermo.id), "esperava encontrar o chunk com o termo léxico: $ids")
        assertTrue(ids.contains(chunkPertoDoVetor.id), "esperava encontrar o chunk mais próximo no ramo vetorial: $ids")

        val rowComTermo = result.first { it.chunkId == chunkComTermo.id }
        // Não apareceu no ramo vetorial (vectorCandidates = 1 já ocupado por outro chunk) — cosineSimilarity cai para 0.0.
        assertEquals(0.0, rowComTermo.cosineSimilarity, 1e-6)
        // charOffset persistido no chunk (T5, ContextAssembler) vem intacto na projeção da query nativa.
        assertEquals(42, rowComTermo.charOffset)
        // Só veio do ramo léxico — matchedLexicalBranch distingue esse 0.0 de "não disponível" de
        // um 0.0 genuíno de irrelevância (RetrievalService, CA7).
        assertTrue(rowComTermo.matchedLexicalBranch, "chunk com match léxico deveria ter matchedLexicalBranch = true")

        val rowPertoDoVetor = result.first { it.chunkId == chunkPertoDoVetor.id }
        // Só veio do ramo vetorial (não contém o termo léxico buscado) — matchedLexicalBranch = false.
        assertFalse(rowPertoDoVetor.matchedLexicalBranch, "chunk só do ramo vetorial não deveria ter matchedLexicalBranch = true")
    }

    @Test
    fun `bookVersionId fora do conjunto elegivel nunca aparece no resultado`() {
        val versionElegivel = persistReadyVersion("livro-elegivel-${UUID.randomUUID()}")
        val versionForaDoEscopo = persistReadyVersion("livro-fora-${UUID.randomUUID()}")
        val queryVector = oneHotEmbedding(0)

        persistChunk(versionElegivel.id, oneHotEmbedding(0), "Chunk do livro elegível.")
        val chunkForaDoEscopo =
            persistChunk(versionForaDoEscopo.id, oneHotEmbedding(0), "Chunk do livro fora do escopo, embedding idêntico à query.")

        val result =
            hybridSearchDao.search(
                queryVector = queryVector,
                queryText = "chunk",
                eligibleBookVersionIds = listOf(versionElegivel.id),
                vectorCandidates = 10,
                lexicalCandidates = 10,
                rrfK = 60,
            )

        assertFalse(
            result.any { it.chunkId == chunkForaDoEscopo.id },
            "chunk de bookVersionId fora do escopo não deveria aparecer: ${result.map { it.chunkId }}",
        )
        assertTrue(result.all { it.bookVersionId == versionElegivel.id })
    }

    @Test
    fun `search devolve lista vazia sem consultar o banco quando nao ha versoes elegiveis`() {
        val result =
            hybridSearchDao.search(
                queryVector = oneHotEmbedding(0),
                queryText = "qualquer coisa",
                eligibleBookVersionIds = emptyList(),
                vectorCandidates = 10,
                lexicalCandidates = 10,
                rrfK = 60,
            )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `cosineSimilarity bate com o calculo manual para vetores one-hot (CA da task)`() {
        val version = persistReadyVersion("livro-cosine-${UUID.randomUUID()}")
        val queryVector = oneHotEmbedding(0)
        val chunkIdentico = persistChunk(version.id, oneHotEmbedding(0), "Chunk com embedding idêntico à query.")
        val chunkOrtogonal = persistChunk(version.id, oneHotEmbedding(1), "Chunk com embedding ortogonal à query.")

        val result =
            hybridSearchDao.search(
                queryVector = queryVector,
                // termo ausente de ambos os textos: só o ramo vetorial contribui candidatos aqui.
                queryText = "termo-inexistente-nos-textos",
                eligibleBookVersionIds = listOf(version.id),
                vectorCandidates = 2,
                lexicalCandidates = 2,
                rrfK = 60,
            )

        val rowIdentico = result.first { it.chunkId == chunkIdentico.id }
        val rowOrtogonal = result.first { it.chunkId == chunkOrtogonal.id }
        // Vetores one-hot na mesma posição: cosine similarity = 1.0 (dot product = norma = 1).
        assertEquals(1.0, rowIdentico.cosineSimilarity, 1e-4)
        // Vetores one-hot em posições diferentes: ortogonais, dot product = 0 -> cosine similarity = 0.0.
        assertEquals(0.0, rowOrtogonal.cosineSimilarity, 1e-4)
        // Ordenação da fusão: o candidato de maior rrfScore vem primeiro.
        assertTrue(rowIdentico.rrfScore >= rowOrtogonal.rrfScore)
        // Nenhum dos dois casou o termo léxico (queryText inexistente nos textos) — o 0.0 de
        // rowOrtogonal aqui é cosine similarity genuína, não o placeholder de "não disponível".
        assertFalse(rowIdentico.matchedLexicalBranch)
        assertFalse(rowOrtogonal.matchedLexicalBranch)
    }

    @Test
    fun `chunk persistido com reference e referenceType (NUMBERED_ITEM ou CHAPTER) volta intacto na HybridSearchRow`() {
        val version = persistReadyVersion("livro-referencia-${UUID.randomUUID()}")
        val queryVector = oneHotEmbedding(0)
        val chunkItemNumerado =
            persistChunk(
                version.id,
                oneHotEmbedding(0),
                "Que se atribui à alma humana desígnio depois da morte.",
                reference = "157",
                referenceType = ReferenceType.NUMBERED_ITEM,
            )
        val chunkCapitulo =
            persistChunk(
                version.id,
                oneHotEmbedding(1),
                "O momento da morte e a separação entre alma e corpo.",
                reference = "Capítulo XII",
                referenceType = ReferenceType.CHAPTER,
            )
        // Chunk sem referência estruturada — confirma que reference/referenceType nulos continuam
        // vindo nulos do ROW_MAPPER (comportamento pré-existente, sem regressão).
        val chunkSemReferencia = persistChunk(version.id, oneHotEmbedding(2), "Trecho de um livro sem --reference-style.")

        val result =
            hybridSearchDao.search(
                queryVector = queryVector,
                queryText = "termo-inexistente-nos-textos",
                eligibleBookVersionIds = listOf(version.id),
                vectorCandidates = 3,
                lexicalCandidates = 3,
                rrfK = 60,
            )

        val rowItemNumerado = result.first { it.chunkId == chunkItemNumerado.id }
        assertEquals("157", rowItemNumerado.reference)
        assertEquals(ReferenceType.NUMBERED_ITEM, rowItemNumerado.referenceType)

        val rowCapitulo = result.first { it.chunkId == chunkCapitulo.id }
        assertEquals("Capítulo XII", rowCapitulo.reference)
        assertEquals(ReferenceType.CHAPTER, rowCapitulo.referenceType)

        val rowSemReferencia = result.first { it.chunkId == chunkSemReferencia.id }
        assertEquals(null, rowSemReferencia.reference)
        assertEquals(null, rowSemReferencia.referenceType)
    }
}
