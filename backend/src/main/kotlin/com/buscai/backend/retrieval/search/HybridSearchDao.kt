package com.buscai.backend.retrieval.search

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
 */
@Repository
class HybridSearchDao(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {
    /**
     * Roda os dois ramos (vetorial + léxico) restritos a [eligibleBookVersionIds] e devolve o
     * resultado fundido por RRF, ordenado por [HybridSearchRow.rrfScore] desc. Não aplica top-k
     * nem dedup — isso é responsabilidade de camadas acima (`RetrievalService`/`ContextAssembler`,
     * T4/T5). Se [eligibleBookVersionIds] for vazio, devolve lista vazia sem consultar o banco.
     */
    fun search(
        queryVector: FloatArray,
        queryText: String,
        eligibleBookVersionIds: List<UUID>,
        vectorCandidates: Int,
        lexicalCandidates: Int,
        rrfK: Int,
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

        return jdbcTemplate.query(HYBRID_SEARCH_SQL, params, ROW_MAPPER)
    }

    companion object {
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
                    chapter = rs.getString("chapter"),
                    text = rs.getString("text"),
                    tokenCount = rs.getInt("token_count"),
                    cosineSimilarity = rs.getDouble("cosine_similarity"),
                    rrfScore = rs.getDouble("rrf_score"),
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
            -- Fusão RRF: soma 1/(k + rank) por ramo em que o chunk aparece (rank 1-indexado dentro
            -- de cada ramo); um chunk ausente de um ramo contribui 0 daquele ramo (COALESCE), não é
            -- descartado — FULL OUTER JOIN preserva quem apareceu em só um dos dois ramos.
            fused AS (
                SELECT
                    COALESCE(v.chunk_id, l.chunk_id) AS chunk_id,
                    COALESCE(1.0 / (:rrfK + v.rnk), 0.0) + COALESCE(1.0 / (:rrfK + l.rnk), 0.0) AS rrf_score
                FROM vector_rank v
                FULL OUTER JOIN lexical_rank l ON v.chunk_id = l.chunk_id
            )
            -- cosine_similarity vem só do ramo vetorial (LEFT JOIN); um chunk que veio só do ramo
            -- léxico (sem aparecer entre os top-N vetoriais) não tem similaridade vetorial medida
            -- aqui, então cai no COALESCE para 0.0 (ver nota em HybridSearchRow.cosineSimilarity).
            SELECT
                ch.id AS chunk_id,
                ch.book_version_id AS book_version_id,
                ch.page AS page,
                ch.char_offset AS char_offset,
                ch.chapter AS chapter,
                ch.text AS text,
                ch.token_count AS token_count,
                COALESCE(vr.cosine_similarity, 0.0) AS cosine_similarity,
                f.rrf_score AS rrf_score
            FROM fused f
            JOIN chunk ch ON ch.id = f.chunk_id
            LEFT JOIN vector_rank vr ON vr.chunk_id = f.chunk_id
            ORDER BY f.rrf_score DESC
            """.trimIndent()
    }
}
