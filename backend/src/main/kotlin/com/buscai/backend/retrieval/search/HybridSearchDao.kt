package com.buscai.backend.retrieval.search

import com.buscai.backend.ingestion.chunking.ReferenceType
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Busca híbrida (vetorial + léxica) sobre `chunk`, fundida por Reciprocal Rank Fusion (RRF) numa
 * única query SQL nativa (ADR-0003, `specs/retrieval/plan.md`, seção "Contratos entre camadas").
 *
 * A query fica inline como constante desta classe, não em `db/queries/hybrid_search.sql`: é usada
 * por um único método, então manter query e código juntos evita indireção sem ganho real de
 * legibilidade (ADR-0009 — organização por feature vertical).
 *
 * **Binding do vetor da query**: `Chunk.embedding` (`catalog`) usa `hibernate-vector`
 * (`@JdbcTypeCode(SqlTypes.VECTOR)`) só para o mapeamento JPA transparente de entidade — não se
 * aplica aqui, onde a query é nativa via JDBC puro (`NamedParameterJdbcTemplate`), sem passar por
 * uma entidade gerenciada. A forma usada (validada em `HybridSearchDaoIntegrationTest`) é formatar
 * o `FloatArray` como o literal de texto que o tipo `vector` do pgvector aceita
 * (`"[0.1,0.2,...]"`) e fazer cast explícito `CAST(:queryVector AS vector)` na query — o mesmo
 * padrão de `'[...]'::vector` documentado pelo pgvector para clientes JDBC crus. `Float.toString`
 * no Kotlin/JVM já usa ponto decimal e não depende de locale, então nenhuma formatação adicional é
 * necessária.
 *
 * **Validação de valores não finitos**: o pgvector rejeita `NaN`/`Infinity`/`-Infinity` no literal
 * de `vector` (`ERROR: NaN not allowed in vector`), e `Float.toString()` produz exatamente esses
 * literais para esses valores — sem validar antes, um vetor degenerado (bug upstream no
 * `EmbeddingClient`/Voyage) propagaria uma `DataAccessException` crua do JDBC, sem contexto do que
 * causou. `toPgVectorLiteral()` valida que todo valor é finito antes de montar a string e lança
 * `IllegalArgumentException` com o índice do valor problemático, sinalizando um bug upstream (não
 * uma condição operacional esperada) em vez de deixar a exceção do JDBC escapar sem contexto.
 *
 * **Ramo exato (`exact_rank`, `busca-exata-item`/T4)**: terceira CTE, opcional por chamada — dado
 * um [search] com `exactItemNumbers` não vazio, contribui os chunks `NUMBERED_ITEM` cujo
 * `[item_start, item_end]` cobre algum dos números pedidos (range-contains), fundidos aos ramos
 * vetorial/léxico por **boost aditivo fixo** (`EXACT_MATCH_SCORE`), não por peso RRF — ver
 * `specs/busca-exata-item/plan.md`, seção "Contratos entre camadas", item 4, para o racional
 * completo (o `ContextAssembler` reordena por `rrfScore`, então só um boost dominante sobrevive a
 * todas as camadas subsequentes).
 */
@Repository
class HybridSearchDao(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {
    /**
     * Roda os três ramos (vetorial + léxico + exato) restritos a [eligibleBookVersionIds] e
     * devolve o resultado fundido, ordenado por [HybridSearchRow.rrfScore] desc. Não aplica top-k
     * nem dedup — isso é responsabilidade de camadas acima (`RetrievalService`/`ContextAssembler`,
     * T4/T5). Se [eligibleBookVersionIds] for vazio, devolve lista vazia sem consultar o banco.
     *
     * [exactItemNumbers] (`busca-exata-item`/T4): números de item detectados na pergunta
     * (`ItemLookupDetector`, já limitado a `RetrievalProperties.maxExactItemNumbers`) — lista vazia
     * (o caso comum, quando a pergunta não tem marcador de item) faz a CTE `exact_rank` não
     * contribuir nenhum chunk, sem alterar o comportamento/resultado dos ramos vetorial e léxico
     * (comportamento idêntico ao pré-T4, coberto explicitamente em teste). [exactMatchLimit] é o
     * teto de segurança da CTE (`LIMIT`, `plan.md`) — T5, ao fiar `RetrievalService`, passa
     * `RetrievalProperties.topK` aqui.
     */
    fun search(
        queryVector: FloatArray,
        queryText: String,
        eligibleBookVersionIds: List<UUID>,
        vectorCandidates: Int,
        lexicalCandidates: Int,
        rrfK: Int,
        exactItemNumbers: List<Int>,
        exactMatchLimit: Int,
    ): List<HybridSearchRow> {
        if (eligibleBookVersionIds.isEmpty()) return emptyList()

        val params =
            MapSqlParameterSource()
                .addValue("queryVector", queryVector.toPgVectorLiteral())
                .addValue("queryText", queryText)
                .addValue("eligibleBookVersionIds", eligibleBookVersionIds)
                .addValue("vectorCandidates", vectorCandidates)
                .addValue("lexicalCandidates", lexicalCandidates)
                .addValue("rrfK", rrfK)
                .addValue("exactItemNumbers", exactItemNumbers.toPgIntArrayLiteral())
                .addValue("exactMatchLimit", exactMatchLimit)
                .addValue("exactMatchScore", EXACT_MATCH_SCORE)

        return jdbcTemplate.query(HYBRID_SEARCH_SQL, params, ROW_MAPPER)
    }

    companion object {
        /**
         * Boost aditivo fixo do ramo exato — duas ordens de grandeza acima do teto teórico do RRF
         * (`2 / (rrfK + 1) ≈ 0.033` para `rrfK = 60`), para garantir que um chunk vindo do ramo
         * exato ordene à frente dos híbridos em toda camada subsequente que reordene por
         * `rrfScore` (`plan.md`, item 4).
         */
        private const val EXACT_MATCH_SCORE = 1.0

        /**
         * Literal de array `int[]` do Postgres (`"{1,2,3}"`, `"{}"` para lista vazia) — usado para
         * bindar uma `List<Int>` como um único parâmetro escalar (`CAST(:exactItemNumbers AS
         * int[])`) em vez de deixar o `NamedParameterJdbcTemplate` expandir a lista em múltiplos
         * placeholders `?, ?, ?` (comportamento padrão do Spring JDBC para valores `List`, pensado
         * para cláusulas `IN`, que quebraria `unnest(...)` — a função espera um único argumento
         * array). `unnest('{}'::int[])` devolve zero linhas, então a lista vazia não exige nenhum
         * caso especial na CTE `exact_rank` (coberto em teste de integração).
         */
        private fun List<Int>.toPgIntArrayLiteral(): String = joinToString(prefix = "{", postfix = "}")

        private fun FloatArray.toPgVectorLiteral(): String =
            withIndex().joinToString(prefix = "[", postfix = "]") { (index, value) ->
                require(value.isFinite()) {
                    "vetor de embedding inválido para busca híbrida: valor não finito ($value) no " +
                        "índice $index — indica um vetor degenerado vindo do EmbeddingClient, não uma " +
                        "condição operacional esperada (pgvector rejeita NaN/Infinity em literais de vector)"
                }
                value.toString()
            }

        private val ROW_MAPPER =
            RowMapper { rs, _ ->
                HybridSearchRow(
                    chunkId = rs.getObject("chunk_id", UUID::class.java),
                    bookVersionId = rs.getObject("book_version_id", UUID::class.java),
                    page = rs.getInt("page"),
                    charOffset = rs.getInt("char_offset"),
                    reference = rs.getString("reference"),
                    referenceType = rs.getString("reference_type")?.let { ReferenceType.valueOf(it) },
                    text = rs.getString("text"),
                    tokenCount = rs.getInt("token_count"),
                    cosineSimilarity = rs.getDouble("cosine_similarity"),
                    rrfScore = rs.getDouble("rrf_score"),
                    matchedLexicalBranch = rs.getBoolean("matched_lexical_branch"),
                    matchedExactBranch = rs.getBoolean("matched_exact_branch"),
                )
            }

        // language=SQL
        private val HYBRID_SEARCH_SQL =
            """
            WITH vector_rank AS (
                SELECT
                    c.id AS chunk_id,
                    1 - (c.embedding <=> CAST(:queryVector AS vector)) AS cosine_similarity,
                    ROW_NUMBER() OVER (ORDER BY c.embedding <=> CAST(:queryVector AS vector)) AS rnk
                FROM chunk c
                WHERE c.book_version_id IN (:eligibleBookVersionIds)
                ORDER BY c.embedding <=> CAST(:queryVector AS vector)
                LIMIT :vectorCandidates
            ),
            lexical_rank AS (
                SELECT
                    c.id AS chunk_id,
                    ROW_NUMBER() OVER (
                        ORDER BY ts_rank(c.text_search, plainto_tsquery('portuguese', :queryText)) DESC
                    ) AS rnk
                FROM chunk c
                WHERE c.book_version_id IN (:eligibleBookVersionIds)
                  AND c.text_search @@ plainto_tsquery('portuguese', :queryText)
                ORDER BY ts_rank(c.text_search, plainto_tsquery('portuguese', :queryText)) DESC
                LIMIT :lexicalCandidates
            ),
            -- Ramo exato (busca-exata-item/T4): chunks NUMBERED_ITEM cujo [item_start, item_end]
            -- cobre algum número de :exactItemNumbers (range-contains, um número único é o caso
            -- item_start = item_end = N). Guard reference_type = 'NUMBERED_ITEM' é obrigatório
            -- (RF4/risco R9, plan.md): blinda contra colisão futura com livros CHAPTER que também
            -- tenham item_start/item_end preenchidos por engano. :exactItemNumbers chega como
            -- literal de array Postgres (ver HybridSearchDao.toPgIntArrayLiteral) — lista vazia vira
            -- '{}', e unnest('{}'::int[]) não produz nenhuma linha, então esta CTE simplesmente não
            -- contribui chunk nenhum (sem caso especial, comportamento idêntico ao pré-T4).
            -- ORDER BY antes do LIMIT (mesmo padrão de vector_rank/lexical_rank, code-reviewer da
            -- T4): sem ordenação, o LIMIT de segurança devolveria um subconjunto não-determinístico
            -- quando o número de matches exceder exactMatchLimit — conflita com RF4/plan.md ("o
            -- LIMIT é teto de segurança, não ranking por obra"). book_version_id, item_start casa
            -- com o índice parcial idx_chunk_item_range (V5), sem custo extra de ordenação.
            exact_rank AS (
                SELECT c.id AS chunk_id
                FROM chunk c
                WHERE c.book_version_id IN (:eligibleBookVersionIds)
                  AND c.reference_type = 'NUMBERED_ITEM'
                  AND EXISTS (
                      SELECT 1 FROM unnest(CAST(:exactItemNumbers AS int[])) AS n
                      WHERE c.item_start <= n AND c.item_end >= n
                  )
                ORDER BY c.book_version_id, c.item_start
                LIMIT :exactMatchLimit
            ),
            -- Fusão 3-way: soma 1/(k + rank) por ramo híbrido em que o chunk aparece (rank
            -- 1-indexado dentro de cada ramo); um chunk ausente de um ramo contribui 0 daquele ramo
            -- (COALESCE), não é descartado — FULL OUTER JOIN preserva quem apareceu em só um dos
            -- ramos. O ramo exato NÃO soma por RRF: contribui um boost aditivo fixo
            -- (:exactMatchScore, ver HybridSearchDao.EXACT_MATCH_SCORE) por cima da soma RRF dos
            -- outros dois ramos — garante que um chunk do ramo exato ordene à frente mesmo depois
            -- de o ContextAssembler reordenar por rrfScore (plan.md, item 4). O segundo FULL OUTER
            -- JOIN casa exact_rank pela identidade já unificada de vector_rank/lexical_rank
            -- (COALESCE(v.chunk_id, l.chunk_id)) — padrão usual de N-way full outer join por chave
            -- coalescida; dedup de identidade é grátis (mesmo chunk_id fundido não importa de
            -- quantos ramos veio).
            -- matched_lexical_branch: true quando o chunk apareceu na CTE lexical_rank (match léxico
            -- exato dentro de lexicalCandidates), independente de também ter aparecido no ramo
            -- vetorial — sinal usado por RetrievalService para não descartar por CA7 um chunk que
            -- só tem cosine_similarity != 0 medida (ver nota em HybridSearchRow.matchedLexicalBranch).
            -- matched_exact_branch: true quando o chunk apareceu na CTE exact_rank — mesma classe de
            -- sinal (ver nota em HybridSearchRow.matchedExactBranch, risco crítico R1 do plan.md).
            fused AS (
                SELECT
                    COALESCE(v.chunk_id, l.chunk_id, e.chunk_id) AS chunk_id,
                    COALESCE(1.0 / (:rrfK + v.rnk), 0.0) + COALESCE(1.0 / (:rrfK + l.rnk), 0.0)
                        + CASE WHEN e.chunk_id IS NOT NULL THEN :exactMatchScore ELSE 0.0 END AS rrf_score,
                    l.chunk_id IS NOT NULL AS matched_lexical_branch,
                    e.chunk_id IS NOT NULL AS matched_exact_branch
                FROM vector_rank v
                FULL OUTER JOIN lexical_rank l ON v.chunk_id = l.chunk_id
                FULL OUTER JOIN exact_rank e ON e.chunk_id = COALESCE(v.chunk_id, l.chunk_id)
            )
            -- cosine_similarity vem só do ramo vetorial (LEFT JOIN); um chunk que veio só do ramo
            -- léxico ou exato (sem aparecer entre os top-N vetoriais) não tem similaridade vetorial
            -- medida aqui, então cai no COALESCE para 0.0 (ver nota em HybridSearchRow.cosineSimilarity).
            SELECT
                ch.id AS chunk_id,
                ch.book_version_id AS book_version_id,
                ch.page AS page,
                ch.char_offset AS char_offset,
                ch.reference AS reference,
                ch.reference_type AS reference_type,
                ch.text AS text,
                ch.token_count AS token_count,
                COALESCE(vr.cosine_similarity, 0.0) AS cosine_similarity,
                f.rrf_score AS rrf_score,
                f.matched_lexical_branch AS matched_lexical_branch,
                f.matched_exact_branch AS matched_exact_branch
            FROM fused f
            JOIN chunk ch ON ch.id = f.chunk_id
            LEFT JOIN vector_rank vr ON vr.chunk_id = f.chunk_id
            ORDER BY f.rrf_score DESC
            """.trimIndent()
    }
}
