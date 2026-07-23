package com.buscai.backend.retrieval.context

import com.buscai.backend.retrieval.search.HybridSearchRow
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Teste unitário puro (sem Spring context, sem banco) de [ContextAssembler] —
 * `specs/retrieval/tasks.md`, T5/T7.
 */
class ContextAssemblerTest {
    private val assembler = ContextAssembler()

    /** Mesmo default de `RetrievalProperties.neighborDedupMinOverlapChars` (T7). */
    private val defaultNeighborDedupMinOverlapChars = 75

    private fun row(
        chunkId: UUID = UUID.randomUUID(),
        bookVersionId: UUID,
        page: Int = 1,
        charOffset: Int,
        text: String,
        tokenCount: Int,
        rrfScore: Double,
    ): HybridSearchRow =
        HybridSearchRow(
            chunkId = chunkId,
            bookVersionId = bookVersionId,
            page = page,
            charOffset = charOffset,
            reference = null,
            referenceType = null,
            text = text,
            tokenCount = tokenCount,
            cosineSimilarity = 0.0,
            rrfScore = rrfScore,
            matchedLexicalBranch = false,
            matchedExactBranch = false,
        )

    @Test
    fun `dois candidatos com janelas sobrepostas na mesma versao e pagina mantem so o de maior score (CA4)`() {
        val versionId = UUID.randomUUID()
        // Janela [0, 100) e janela [10, 110) — sobreposição de 90 caracteres, acima do limiar de dedup.
        val menorScore =
            row(
                bookVersionId = versionId,
                charOffset = 0,
                text = "a".repeat(100),
                tokenCount = 10,
                rrfScore = 0.01,
            )
        val maiorScore =
            row(
                bookVersionId = versionId,
                charOffset = 10,
                text = "b".repeat(100),
                tokenCount = 10,
                rrfScore = 0.02,
            )

        val result =
            assembler.assemble(
                listOf(menorScore, maiorScore),
                tokenBudget = 10_000,
                neighborDedupMinOverlapChars = defaultNeighborDedupMinOverlapChars,
            )

        assertEquals(listOf(maiorScore), result)
    }

    @Test
    fun `candidatos sem sobreposicao significativa ou de versoes paginas diferentes sao mantidos ambos`() {
        val versionId = UUID.randomUUID()
        // Janela [0, 10) e janela [90, 190) — sem sobreposição.
        val primeiro =
            row(bookVersionId = versionId, charOffset = 0, text = "a".repeat(10), tokenCount = 10, rrfScore = 0.02)
        val segundo =
            row(bookVersionId = versionId, charOffset = 90, text = "b".repeat(100), tokenCount = 10, rrfScore = 0.01)

        val result =
            assembler.assemble(
                listOf(segundo, primeiro),
                tokenBudget = 10_000,
                neighborDedupMinOverlapChars = defaultNeighborDedupMinOverlapChars,
            )

        assertEquals(listOf(primeiro, segundo), result)
    }

    @Test
    fun `lista cujo somatorio de tokenCount ultrapassa o orcamento e cortada preservando ordem por relevancia (CA5)`() {
        val candidatos =
            listOf(
                row(bookVersionId = UUID.randomUUID(), charOffset = 0, text = "x", tokenCount = 1000, rrfScore = 0.03),
                row(bookVersionId = UUID.randomUUID(), charOffset = 0, text = "x", tokenCount = 1000, rrfScore = 0.02),
                row(bookVersionId = UUID.randomUUID(), charOffset = 0, text = "x", tokenCount = 1000, rrfScore = 0.01),
                row(bookVersionId = UUID.randomUUID(), charOffset = 0, text = "x", tokenCount = 1000, rrfScore = 0.005),
            )

        val result = assembler.assemble(candidatos, tokenBudget = 3000, neighborDedupMinOverlapChars = defaultNeighborDedupMinOverlapChars)

        assertEquals(3, result.size, "esperava cortar o 4º candidato, que estouraria o orçamento de 3000 tokens")
        assertEquals(candidatos.take(3), result, "ordem por relevância (rrfScore desc) deve ser preservada")
        assertTrue(result.sumOf { it.tokenCount } <= 3000)
    }

    @Test
    fun `lista vazia devolve lista vazia sem erro`() {
        val result = assembler.assemble(emptyList(), tokenBudget = 3000, neighborDedupMinOverlapChars = defaultNeighborDedupMinOverlapChars)

        assertEquals(emptyList(), result)
    }

    @Test
    fun `vizinho direto simulando saida real do Chunker e deduplicado, mas chunk nao vizinho na mesma pagina e mantido`() {
        val versionId = UUID.randomUUID()

        // Simula a saída real do Chunker (itens 4-5 do KDoc de Chunker.chunk, e o KDoc de
        // ContextAssembler.overlapsSignificantly): "text" de um chunk que não é o primeiro do
        // grupo é [prefixo de overlap herdado do vizinho ANTERIOR] + "\n\n" + [conteúdo próprio];
        // "charOffset" sempre aponta para onde o conteúdo próprio começa, ignorando esse prefixo.
        //
        // chunkA: 150 chars de prefixo herdado do SEU antecessor (não modelado aqui) + 850 chars
        // de conteúdo próprio. charOffset=0 marca o início do conteúdo próprio (não do prefixo).
        val chunkA =
            row(
                bookVersionId = versionId,
                charOffset = 0,
                text = "P".repeat(150) + "\n\n" + "A".repeat(850),
                tokenCount = 100,
                rrfScore = 0.03,
            )
        // chunkBVizinhoDireto: conteúdo próprio começa exatamente onde o de A termina (charOffset
        // 850 = 0 + 850, item 5) e herda como prefixo os últimos 130 chars do PRÓPRIO de A — a
        // duplicação literal de texto que o CA4 deve detectar.
        val chunkBVizinhoDireto =
            row(
                bookVersionId = versionId,
                charOffset = 850,
                text = "A".repeat(130) + "\n\n" + "B".repeat(850),
                tokenCount = 100,
                rrfScore = 0.02,
            )
        // chunkCNaoVizinho: mesma página/versão, mas bem além da janela de A ([0, 1002) — 850 de
        // conteúdo próprio + 152 de overshoot do prefixo herdado por A, ver KDoc de
        // overlapsSignificantly) — não deveria ser afetado pelo dedup.
        val chunkCNaoVizinho =
            row(
                bookVersionId = versionId,
                charOffset = 3000,
                text = "C".repeat(850),
                tokenCount = 100,
                rrfScore = 0.01,
            )

        val result =
            assembler.assemble(
                listOf(chunkA, chunkBVizinhoDireto, chunkCNaoVizinho),
                tokenBudget = 10_000,
                neighborDedupMinOverlapChars = defaultNeighborDedupMinOverlapChars,
            )

        assertEquals(
            listOf(chunkA, chunkCNaoVizinho),
            result,
            "vizinho direto (B, que duplica o rabo de A) deveria ser descartado; " +
                "não vizinho (C) deveria ser mantido",
        )
    }

    @Test
    fun `candidatos de bookVersionId diferentes nunca sao deduplicados mesmo com charOffsets numericamente sobrepostos`() {
        val candidato1 =
            row(bookVersionId = UUID.randomUUID(), charOffset = 0, text = "a".repeat(200), tokenCount = 10, rrfScore = 0.02)
        val candidato2OutraVersao =
            row(bookVersionId = UUID.randomUUID(), charOffset = 50, text = "b".repeat(200), tokenCount = 10, rrfScore = 0.01)

        val result =
            assembler.assemble(
                listOf(candidato1, candidato2OutraVersao),
                tokenBudget = 10_000,
                neighborDedupMinOverlapChars = defaultNeighborDedupMinOverlapChars,
            )

        assertEquals(
            listOf(candidato1, candidato2OutraVersao),
            result,
            "candidatos de bookVersionId diferentes nunca deveriam ser comparados, mesmo com janelas numericamente sobrepostas",
        )
    }

    @Test
    fun `candidatos da mesma versao mas paginas diferentes nunca sao deduplicados mesmo com charOffsets numericamente sobrepostos`() {
        val versionId = UUID.randomUUID()
        val candidato1 =
            row(bookVersionId = versionId, page = 1, charOffset = 0, text = "a".repeat(200), tokenCount = 10, rrfScore = 0.02)
        val candidato2OutraPagina =
            row(bookVersionId = versionId, page = 2, charOffset = 50, text = "b".repeat(200), tokenCount = 10, rrfScore = 0.01)

        val result =
            assembler.assemble(
                listOf(candidato1, candidato2OutraPagina),
                tokenBudget = 10_000,
                neighborDedupMinOverlapChars = defaultNeighborDedupMinOverlapChars,
            )

        assertEquals(
            listOf(candidato1, candidato2OutraPagina),
            result,
            "candidatos de páginas diferentes da mesma versão nunca deveriam ser comparados, mesmo com janelas numericamente sobrepostas",
        )
    }
}
