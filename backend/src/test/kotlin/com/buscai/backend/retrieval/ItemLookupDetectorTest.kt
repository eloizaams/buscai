package com.buscai.backend.retrieval

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Teste unitário puro (sem Spring context, sem banco) de [ItemLookupDetector] — RF1 e CA4 de
 * `specs/busca-exata-item/spec.md`.
 */
class ItemLookupDetectorTest {
    private fun detector(maxExactItemNumbers: Int = 3) = ItemLookupDetector(RetrievalProperties(maxExactItemNumbers = maxExactItemNumbers))

    @Test
    fun `pergunta N e detectado`() {
        assertEquals(listOf(25), detector().detect("pergunta 25"))
    }

    @Test
    fun `questao N e detectado`() {
        assertEquals(listOf(700), detector().detect("questão 700"))
    }

    @Test
    fun `questao sem acento e detectado`() {
        assertEquals(listOf(700), detector().detect("questao 700"))
    }

    @Test
    fun `item N e detectado`() {
        assertEquals(listOf(157), detector().detect("item 157"))
    }

    @Test
    fun `pergunta com abreviacao de numero e detectado`() {
        assertEquals(listOf(25), detector().detect("pergunta nº 25"))
    }

    @Test
    fun `questao com abreviacao n ponto e detectado`() {
        assertEquals(listOf(700), detector().detect("questão n. 700"))
    }

    @Test
    fun `pergunta mista com numero e assunto extrai o numero`() {
        assertEquals(
            listOf(700),
            detector().detect("a pergunta 700 fala da igualdade entre os sexos?"),
        )
    }

    @Test
    fun `ano solto sem marcador nao dispara lookup`() {
        assertEquals(emptyList(), detector().detect("o que aconteceu em 1857?"))
    }

    @Test
    fun `numero solto sem marcador de item nao dispara lookup`() {
        assertEquals(emptyList(), detector().detect("os 10 mandamentos"))
    }

    @Test
    fun `marcador salmo fora do lexico nao dispara lookup`() {
        assertEquals(emptyList(), detector().detect("salmo 23"))
    }

    @Test
    fun `marcador capitulo esta fora de escopo e nao dispara lookup`() {
        assertEquals(emptyList(), detector().detect("capítulo 25"))
    }

    @Test
    fun `pergunta sem nenhum marcador ou numero retorna lista vazia`() {
        assertEquals(emptyList(), detector().detect("o que é o perispírito?"))
    }

    @Test
    fun `multiplos numeros marcados acima do cap sao truncados na ordem de aparicao`() {
        val result =
            detector(maxExactItemNumbers = 3)
                .detect("pergunta 1, pergunta 2, pergunta 3 e pergunta 4")

        assertEquals(listOf(1, 2, 3), result)
    }

    @Test
    fun `multiplos numeros marcados dentro do cap sao todos mantidos`() {
        val result =
            detector(maxExactItemNumbers = 3)
                .detect("pergunta 1 e pergunta 2")

        assertEquals(listOf(1, 2), result)
    }

    @Test
    fun `cap customizado e respeitado`() {
        val result =
            detector(maxExactItemNumbers = 1)
                .detect("pergunta 1, pergunta 2, pergunta 3")

        assertEquals(listOf(1), result)
    }

    @Test
    fun `numero do livro do livro dos espiritos nao interfere no marcador de item`() {
        assertEquals(
            listOf(25),
            detector().detect("qual a pergunta 25 do Livro dos Espíritos?"),
        )
    }

    @Test
    fun `abreviacao n ponto isolada sem marcador pleno nao dispara lookup`() {
        assertEquals(emptyList(), detector().detect("veja a nota n. 3 no rodapé"))
    }

    @Test
    fun `abreviacao n ponto isolada em outro contexto numerado nao dispara lookup`() {
        assertEquals(emptyList(), detector().detect("figura n. 3 do livro"))
    }

    @Test
    fun `abreviacao n ponto isolada em lei nao dispara lookup`() {
        assertEquals(emptyList(), detector().detect("lei n. 8.078"))
    }

    @Test
    fun `abreviacao encadeada apos marcador pleno continua funcionando`() {
        assertEquals(listOf(25), detector().detect("pergunta nº 25"))
        assertEquals(listOf(700), detector().detect("questão n. 700"))
    }

    @Test
    fun `plural perguntas com dois numeros encadeados por e detecta so o primeiro numero`() {
        // Degradação aceita (RF5): "26" não tem marcador próprio adjacente, então não é
        // detectado — enumeração/faixa na pergunta é YAGNI (spec.md, "Fora de escopo"). O
        // restante da pergunta continua coberto pela busca híbrida normal.
        assertEquals(listOf(25), detector().detect("perguntas 25 e 26"))
    }

    @Test
    fun `numero de contagem apos e nao marcado nao dispara falso positivo`() {
        // Regressão do achado do code-reviewer: uma versão anterior desta classe suportava
        // enumeração por "e" e capturava "2" indevidamente como se fosse item.
        assertEquals(listOf(1), detector().detect("tenho pergunta 1 e 2 gatos em casa"))
    }

    @Test
    fun `plural questoes e detectado`() {
        assertEquals(listOf(3), detector().detect("questões 3"))
        assertEquals(listOf(3), detector().detect("questoes 3"))
    }

    @Test
    fun `plural itens e detectado`() {
        assertEquals(listOf(9), detector().detect("itens 9"))
    }

    @Test
    fun `faixa com conector a nao e tratada como enumeracao apenas o primeiro numero e detectado`() {
        assertEquals(listOf(155), detector().detect("as perguntas 155 a 158 falam de quê?"))
    }
}
