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
        itemStart: Int? = null,
        itemEnd: Int? = null,
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
                itemStart = itemStart,
                itemEnd = itemEnd,
            ),
        )

    // Este teste também cobre o caso "lista vazia de números ⇒ comportamento idêntico ao atual"
    // da T4 (busca-exata-item): já chama hybridSearchDao.search(..., exactItemNumbers = emptyList())
    // e as asserções de score/matchedLexicalBranch abaixo são exatamente as do comportamento
    // pré-T4 — nenhuma delas mudou ao introduzir a CTE exact_rank, confirmando que a lista vazia
    // não contamina a fusão dos outros dois ramos.
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
                exactItemNumbers = emptyList(),
                exactMatchLimit = 10,
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
                exactItemNumbers = emptyList(),
                exactMatchLimit = 10,
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
                exactItemNumbers = emptyList(),
                exactMatchLimit = 10,
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
                exactItemNumbers = emptyList(),
                exactMatchLimit = 10,
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
                exactItemNumbers = emptyList(),
                exactMatchLimit = 10,
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

    // A partir daqui: ramo exato (busca-exata-item/T4, CTE exact_rank). Nos testes que isolam a
    // CTE (item único, faixa, guard), vectorCandidates/lexicalCandidates = 0 — LIMIT 0 nas outras
    // duas CTEs garante que nenhum chunk apareça por vetorial/léxico, então qualquer chunk no
    // resultado só pode ter vindo do exact_rank (evita "sorte" de um chunk único ocupar o único
    // slot do ramo vetorial por LIMIT 1, como no restante da suíte).

    @Test
    fun `chunk com item unico casa o numero exato buscado (T4, item unico)`() {
        val version = persistReadyVersion("livro-item-unico-${UUID.randomUUID()}")
        val chunk =
            persistChunk(
                version.id,
                oneHotEmbedding(0),
                "Se um dia a alma humana atingir a perfeição.",
                reference = "25",
                referenceType = ReferenceType.NUMBERED_ITEM,
                itemStart = 25,
                itemEnd = 25,
            )

        val result =
            hybridSearchDao.search(
                queryVector = oneHotEmbedding(0),
                queryText = "termo-inexistente-nos-textos",
                eligibleBookVersionIds = listOf(version.id),
                vectorCandidates = 0,
                lexicalCandidates = 0,
                rrfK = 60,
                exactItemNumbers = listOf(25),
                exactMatchLimit = 10,
            )

        assertEquals(1, result.size, "esperava só o chunk do ramo exato, sem vetorial/léxico: ${result.map { it.chunkId }}")
        val row = result.first()
        assertEquals(chunk.id, row.chunkId)
        assertTrue(row.matchedExactBranch, "chunk cujo item_start = item_end = 25 deveria casar exactItemNumbers = [25]")
    }

    @Test
    fun `chunk com faixa de itens casa numero contido na faixa (T4, faixa)`() {
        val version = persistReadyVersion("livro-faixa-${UUID.randomUUID()}")
        val chunk =
            persistChunk(
                version.id,
                oneHotEmbedding(0),
                "Perguntas e respostas de um trecho catequético em faixa.",
                reference = "160–164",
                referenceType = ReferenceType.NUMBERED_ITEM,
                itemStart = 160,
                itemEnd = 164,
            )

        val result =
            hybridSearchDao.search(
                queryVector = oneHotEmbedding(0),
                queryText = "termo-inexistente-nos-textos",
                eligibleBookVersionIds = listOf(version.id),
                vectorCandidates = 0,
                lexicalCandidates = 0,
                rrfK = 60,
                // 162 está contido em [160, 164], mas não é nem o início nem o fim da faixa —
                // exercita o range-contains de fato, não só igualdade de extremos.
                exactItemNumbers = listOf(162),
                exactMatchLimit = 10,
            )

        assertEquals(1, result.size)
        val row = result.first()
        assertEquals(chunk.id, row.chunkId)
        assertTrue(row.matchedExactBranch, "162 está contido em [160, 164]: deveria casar pelo ramo exato")
    }

    @Test
    fun `numero de item inexistente nao adiciona nenhum chunk pelo ramo exato, sem quebrar a query (T4)`() {
        val version = persistReadyVersion("livro-item-inexistente-${UUID.randomUUID()}")
        val queryVector = oneHotEmbedding(0)
        val chunkEncontradoPeloVetor =
            persistChunk(
                version.id,
                oneHotEmbedding(0),
                "Texto qualquer, encontrado normalmente pelo ramo vetorial.",
                reference = "25",
                referenceType = ReferenceType.NUMBERED_ITEM,
                itemStart = 25,
                itemEnd = 25,
            )

        val result =
            hybridSearchDao.search(
                queryVector = queryVector,
                queryText = "termo-inexistente-nos-textos",
                eligibleBookVersionIds = listOf(version.id),
                vectorCandidates = 10,
                lexicalCandidates = 10,
                rrfK = 60,
                // 99999 não é coberto por nenhum chunk persistido (item_start/item_end = 25).
                exactItemNumbers = listOf(99999),
                exactMatchLimit = 10,
            )

        assertTrue(
            result.any { it.chunkId == chunkEncontradoPeloVetor.id },
            "a query não deveria quebrar; o chunk continua aparecendo normalmente pelo ramo vetorial",
        )
        assertTrue(
            result.none { it.matchedExactBranch },
            "nenhum chunk deveria ter matchedExactBranch = true para um número de item que não existe: " +
                result.map { it.chunkId to it.matchedExactBranch },
        )
    }

    @Test
    fun `guard reference_type impede chunk CHAPTER de casar pelo ramo exato mesmo com item_start-item_end preenchidos (T4, RF4-R9)`() {
        val version = persistReadyVersion("livro-guard-chapter-${UUID.randomUUID()}")
        // Cenário defensivo/hipotético (spec T4): na prática só NUMBERED_ITEM tem item_start/item_end
        // preenchidos (T1/T2), mas o guard SQL precisa proteger mesmo que isso ocorra por engano.
        persistChunk(
            version.id,
            oneHotEmbedding(0),
            "Texto de um capítulo, não de um item numerado.",
            reference = "160–164",
            referenceType = ReferenceType.CHAPTER,
            itemStart = 160,
            itemEnd = 164,
        )

        val result =
            hybridSearchDao.search(
                queryVector = oneHotEmbedding(0),
                queryText = "termo-inexistente-nos-textos",
                eligibleBookVersionIds = listOf(version.id),
                vectorCandidates = 0,
                lexicalCandidates = 0,
                rrfK = 60,
                exactItemNumbers = listOf(162),
                exactMatchLimit = 10,
            )

        assertTrue(
            result.isEmpty(),
            "guard reference_type = 'NUMBERED_ITEM' deveria impedir o chunk CHAPTER de entrar via " +
                "exact_rank mesmo com item_start/item_end preenchidos: ${result.map { it.chunkId }}",
        )
    }

    @Test
    fun `chunk exact-only ordena a frente de chunk hibrido vetorial mais lexico (T4, boost aditivo)`() {
        val version = persistReadyVersion("livro-boost-${UUID.randomUUID()}")
        val queryVector = oneHotEmbedding(0)
        // Híbrido: embedding idêntico à query (topo do ramo vetorial) e contém o termo léxico
        // buscado — casa nos dois ramos, RRF puro (sem boost).
        val chunkHibrido =
            persistChunk(
                version.id,
                oneHotEmbedding(0),
                "O protagonista chama-se Bentinho e mora no Rio de Janeiro.",
            )
        // Exact-only: embedding ortogonal (fora do vectorCandidates = 1, que já está ocupado pelo
        // híbrido) e sem o termo léxico "Bentinho" no texto — só pode aparecer pelo ramo exato.
        val chunkExato =
            persistChunk(
                version.id,
                oneHotEmbedding(5),
                "Que se atribui à alma humana desígnio depois da morte.",
                reference = "25",
                referenceType = ReferenceType.NUMBERED_ITEM,
                itemStart = 25,
                itemEnd = 25,
            )

        val result =
            hybridSearchDao.search(
                queryVector = queryVector,
                queryText = "Bentinho",
                eligibleBookVersionIds = listOf(version.id),
                vectorCandidates = 1,
                lexicalCandidates = 10,
                rrfK = 60,
                exactItemNumbers = listOf(25),
                exactMatchLimit = 10,
            )

        val rowHibrido = result.first { it.chunkId == chunkHibrido.id }
        val rowExato = result.first { it.chunkId == chunkExato.id }
        assertFalse(rowHibrido.matchedExactBranch)
        assertTrue(rowExato.matchedExactBranch)
        assertTrue(
            rowExato.rrfScore > rowHibrido.rrfScore,
            "boost aditivo do ramo exato (EXACT_MATCH_SCORE = 1.0) deveria dominar a soma RRF dos " +
                "ramos vetorial+léxico do chunk híbrido: rowExato.rrfScore=${rowExato.rrfScore}, " +
                "rowHibrido.rrfScore=${rowHibrido.rrfScore}",
        )
        // A ordenação da própria query (ORDER BY f.rrf_score DESC) já reflete o boost.
        assertEquals(chunkExato.id, result.first().chunkId, "chunk exato deveria vir primeiro no resultado ordenado")
    }

    @Test
    fun `LIMIT do ramo exato trunca de forma deterministica pelo indice book_version_id-item_start (T4, code-reviewer)`() {
        val version = persistReadyVersion("livro-truncamento-exato-${UUID.randomUUID()}")
        // 5 chunks NUMBERED_ITEM de item único, todos casando o mesmo conjunto de exactItemNumbers
        // — mais candidatos qualificados do que exactMatchLimit (força o LIMIT a truncar de fato).
        // Inseridos fora de ordem de item_start (14, 12, 10, 13, 11) para não deixar a ordem física
        // de inserção coincidir por acaso com a ordem esperada (book_version_id, item_start).
        listOf(14, 12, 10, 13, 11).forEach { itemNumber ->
            persistChunk(
                version.id,
                oneHotEmbedding(itemNumber),
                "Item numerado $itemNumber.",
                reference = itemNumber.toString(),
                referenceType = ReferenceType.NUMBERED_ITEM,
                itemStart = itemNumber,
                itemEnd = itemNumber,
            )
        }

        fun search() =
            hybridSearchDao.search(
                queryVector = oneHotEmbedding(0),
                queryText = "termo-inexistente-nos-textos",
                eligibleBookVersionIds = listOf(version.id),
                // Isola o ramo exato: só ele pode contribuir chunk nenhum aqui.
                vectorCandidates = 0,
                lexicalCandidates = 0,
                rrfK = 60,
                exactItemNumbers = listOf(10, 11, 12, 13, 14),
                exactMatchLimit = 3,
            )

        val firstCall = search()
        val secondCall = search()

        assertEquals(3, firstCall.size, "exactMatchLimit = 3 deveria truncar os 5 candidatos qualificados")
        assertEquals(
            firstCall.map { it.chunkId }.toSet(),
            secondCall.map { it.chunkId }.toSet(),
            "o subconjunto truncado pelo LIMIT deveria ser determinístico entre chamadas repetidas " +
                "(mesma query, mesmos parâmetros) — sem ORDER BY antes do LIMIT isso não é garantido",
        )
        assertEquals(
            setOf("10", "11", "12"),
            firstCall.map { it.reference }.toSet(),
            "ORDER BY c.book_version_id, c.item_start antes do LIMIT deveria manter os 3 menores " +
                "item_start (10, 11, 12), não um subconjunto arbitrário: ${firstCall.map { it.reference }}",
        )
        assertTrue(firstCall.all { it.matchedExactBranch })
    }
}
