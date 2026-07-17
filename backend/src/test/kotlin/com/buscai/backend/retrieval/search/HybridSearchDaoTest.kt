package com.buscai.backend.retrieval.search

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID
import kotlin.test.assertTrue

// Teste unitário puro (sem Spring context, sem Testcontainers): cobre só a validação de valores
// não finitos no vetor de embedding (code-reviewer, T3) — o resto do comportamento de
// HybridSearchDao.search já é coberto por HybridSearchDaoIntegrationTest (Testcontainers).
class HybridSearchDaoTest {
    private val dao = HybridSearchDao(Mockito.mock(NamedParameterJdbcTemplate::class.java))

    @Test
    fun `search lanca IllegalArgumentException com mensagem clara quando o vetor da query tem NaN`() {
        val vetorDegenerado = FloatArray(4) { 0f }
        vetorDegenerado[2] = Float.NaN

        val exception =
            assertThrows<IllegalArgumentException> {
                dao.search(
                    queryVector = vetorDegenerado,
                    queryText = "qualquer coisa",
                    eligibleBookVersionIds = listOf(UUID.randomUUID()),
                    vectorCandidates = 10,
                    lexicalCandidates = 10,
                    rrfK = 60,
                )
            }

        assertTrue(exception.message?.contains("índice 2") == true, "mensagem deveria citar o índice: ${exception.message}")
        assertTrue(exception.message?.contains("NaN") == true, "mensagem deveria citar o valor: ${exception.message}")
    }

    @Test
    fun `search lanca IllegalArgumentException quando o vetor da query tem Infinity`() {
        val vetorDegenerado = floatArrayOf(1f, Float.POSITIVE_INFINITY, 0f)

        val exception =
            assertThrows<IllegalArgumentException> {
                dao.search(
                    queryVector = vetorDegenerado,
                    queryText = "qualquer coisa",
                    eligibleBookVersionIds = listOf(UUID.randomUUID()),
                    vectorCandidates = 10,
                    lexicalCandidates = 10,
                    rrfK = 60,
                )
            }

        assertTrue(exception.message?.contains("índice 1") == true, "mensagem deveria citar o índice: ${exception.message}")
    }
}
