package com.buscai.backend.retrieval.context

import com.buscai.backend.retrieval.search.HybridSearchRow
import org.springframework.stereotype.Component

/**
 * Dono de duas responsabilidades pós-fusão RRF (`specs/retrieval/plan.md`, seção "Contratos entre
 * camadas" — nota também em ADR-0004): dedup de chunks vizinhos redundantes e orçamento de tokens
 * do contexto final. Opera sobre a projeção crua [HybridSearchRow] (antes da conversão para
 * `RetrievedChunk`, feita por `RetrievalService`).
 */
@Component
class ContextAssembler {
    /**
     * 1. Ordena [rows] por [HybridSearchRow.rrfScore] desc.
     * 2. Dedup de vizinhos (CA4): dois candidatos da mesma [HybridSearchRow.bookVersionId] e mesma
     *    [HybridSearchRow.page] cujas janelas `[charOffset, charOffset + text.length)` se
     *    sobrepõem em pelo menos [neighborDedupMinOverlapChars] caracteres são tratados como
     *    redundantes — mantém só o de maior `rrfScore` (o primeiro a ser aceito, dada a ordenação
     *    do passo 1), descarta o outro. `charOffset` é relativo à página (KDoc de
     *    [HybridSearchRow]), então candidatos de páginas diferentes nunca são comparados.
     * 3. Orçamento de tokens (CA5): acumula [HybridSearchRow.tokenCount] na ordem resultante do
     *    passo 2 até [tokenBudget]; um candidato cujo `tokenCount` estouraria o orçamento não
     *    entra, e nenhum candidato depois dele é considerado (preserva a ordem por relevância).
     *
     * [neighborDedupMinOverlapChars] vem de `RetrievalProperties.neighborDedupMinOverlapChars`
     * (`buscai.retrieval.neighbor-dedup-min-overlap-chars`) — default sugerido em
     * `specs/retrieval/plan.md`, seção "Config nova": "metade do overlap mínimo do chunking
     * (ADR-0002)". O overlap do chunking (`OVERLAP_MIN_RATIO`, `ingestion.chunking.Chunker`) é
     * definido em fração de tokens (10% de
     * [com.buscai.backend.ingestion.chunking.MIN_CHUNK_TOKENS] = 300, ou seja, ~30 tokens), não em
     * caracteres; convertendo com uma estimativa grosseira de ~5 caracteres por token (palavra +
     * espaço, PT-BR/EN), isso dá ~150 caracteres de overlap mínimo esperado entre chunks vizinhos —
     * a metade disso (75, default de `RetrievalProperties`) é o limiar de dedup usado por padrão.
     * É uma aproximação deliberadamente conservadora (a calibrar quando o golden set tiver
     * perguntas reais, T9), não um número medido do pipeline real de chunking.
     *
     * Lista vazia devolve lista vazia.
     */
    fun assemble(
        rows: List<HybridSearchRow>,
        tokenBudget: Int,
        neighborDedupMinOverlapChars: Int,
    ): List<HybridSearchRow> {
        if (rows.isEmpty()) return emptyList()

        val deduped = dedupNeighbors(rows.sortedByDescending { it.rrfScore }, neighborDedupMinOverlapChars)
        return applyTokenBudget(deduped, tokenBudget)
    }

    private fun dedupNeighbors(
        candidatesByScoreDesc: List<HybridSearchRow>,
        neighborDedupMinOverlapChars: Int,
    ): List<HybridSearchRow> {
        val kept = mutableListOf<HybridSearchRow>()
        for (candidate in candidatesByScoreDesc) {
            val isRedundant =
                kept.any { alreadyKept ->
                    overlapsSignificantly(alreadyKept, candidate, neighborDedupMinOverlapChars)
                }
            if (!isRedundant) kept += candidate
        }
        return kept
    }

    /**
     * Trade-off deliberado, apontado pelo `code-reviewer` da T5 (revisado, não corrigido — a
     * aproximação é o que faz o dedup funcionar, ver abaixo): a janela usada aqui é
     * `[charOffset, charOffset + text.length)`, ou seja, `text` **completo**, incluindo o prefixo
     * de overlap herdado do chunk anterior (`Chunker`, itens 4-5 do KDoc de
     * `com.buscai.backend.ingestion.chunking.Chunker.chunk`) — não só o span do conteúdo PRÓPRIO
     * do chunk (que seria `[charOffset, charOffset + ownText.length)`, mas `ownText`/seu tamanho
     * não é persistido, só o `text` final já com o prefixo).
     *
     * Por que isso é intencional, e não um bug a corrigir com uma janela "exata":
     * - As janelas de conteúdo PRÓPRIO de chunks da mesma `bookVersionId`/`page` são **disjuntas
     *   por construção** (o `Chunker` particiona os parágrafos sequencialmente sem repetir
     *   nenhum entre chunks — a única duplicação de texto entre vizinhos é o prefixo de overlap,
     *   item 4). Com a janela "exata" (só o conteúdo próprio), o dedup nunca dispararia entre
     *   vizinhos reais — anularia o CA4 por construção, não por acidente de dado.
     * - Com `text.length` completo, o fim da janela de A avança sobre o início de B em
     *   aproximadamente o tamanho do prefixo de overlap que B herdou de A (item 4: ~10-20% do
     *   conteúdo próprio de A, tipicamente bem acima de
     *   [NEIGHBOR_DEDUP_MIN_OVERLAP_CHARS]) — exatamente os vizinhos diretos que de fato
     *   duplicam texto (o rabo de A está literalmente prefixado em B.text) cruzam o limiar e são
     *   deduplicados, que é a intenção do CA4/`plan.md` ("mantém só o de maior `rrfScore`").
     * - Chunks da mesma página que NÃO são vizinhos diretos nunca cruzam o limiar por acidente: o
     *   "overshoot" da janela (da ordem de um overlap de chunking, ~150 chars pela estimativa de
     *   [NEIGHBOR_DEDUP_MIN_OVERLAP_CHARS]) é desprezível perto do conteúdo próprio de um chunk
     *   inteiro (centenas a milhares de caracteres) que separa dois chunks não vizinhos.
     * - A magnitude do overshoot usa o prefixo de overlap do candidato de **maior** `rrfScore`
     *   (`a`, já aceito) em vez do prefixo herdado por `b` (o que seria mais "correto",
     *   mas exigiria uma coluna nova + migration + re-ingestão para persistir o tamanho do
     *   conteúdo próprio separado do `text` final) — mesma ordem de grandeza, aproximação
     *   aceitável a validar contra o `rag-evaluator`/golden set quando houver perguntas reais.
     */
    private fun overlapsSignificantly(
        a: HybridSearchRow,
        b: HybridSearchRow,
        neighborDedupMinOverlapChars: Int,
    ): Boolean {
        if (a.bookVersionId != b.bookVersionId || a.page != b.page) return false

        val aEnd = a.charOffset + a.text.length
        val bEnd = b.charOffset + b.text.length
        val overlapChars = minOf(aEnd, bEnd) - maxOf(a.charOffset, b.charOffset)
        return overlapChars >= neighborDedupMinOverlapChars
    }

    private fun applyTokenBudget(
        candidatesByScoreDesc: List<HybridSearchRow>,
        tokenBudget: Int,
    ): List<HybridSearchRow> {
        val result = mutableListOf<HybridSearchRow>()
        var tokensUsed = 0
        for (candidate in candidatesByScoreDesc) {
            if (tokensUsed + candidate.tokenCount > tokenBudget) break
            result += candidate
            tokensUsed += candidate.tokenCount
        }
        return result
    }
}
