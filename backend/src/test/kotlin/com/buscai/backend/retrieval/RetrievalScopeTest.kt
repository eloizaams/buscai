package com.buscai.backend.retrieval

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Teste unitário puro (sem Spring context, sem banco) da invariante de [RetrievalScope.Books]:
 * "nenhum filtro" se expressa com [RetrievalScope.AllBooks], nunca com um `Books` de conjunto
 * vazio (`specs/retrieval/plan.md`, seção "Contratos entre camadas").
 */
class RetrievalScopeTest {
    @Test
    fun `Books com bookIds vazio lanca IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            RetrievalScope.Books(emptySet())
        }
    }

    @Test
    fun `Books com ao menos um bookId e aceito normalmente`() {
        val scope = RetrievalScope.Books(setOf("dom-casmurro"))

        assertEquals(setOf("dom-casmurro"), scope.bookIds)
    }
}
