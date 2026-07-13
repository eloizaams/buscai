package com.buscai.backend.ingestion.chunking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChunkerTest {
    private val chunker = Chunker()

    /** Gera "w1 w2 ... wN" (palavras curtas e previsíveis, uma "token" cada pela definição de [countTokens]). */
    private fun words(range: IntRange): String = range.joinToString(" ") { "w$it" }

    // --- casos vazio / curto demais ---

    @Test
    fun `chunk empty input produces no chunks`() {
        assertEquals(emptyList<ChunkDraft>(), chunker.chunk(emptyMap()))
    }

    @Test
    fun `chunk pages with only blank text produce no chunks`() {
        val pageTexts = mapOf(1 to "   \n\n  \n", 2 to "")

        assertEquals(emptyList<ChunkDraft>(), chunker.chunk(pageTexts))
    }

    @Test
    fun `chunk text shorter than the minimum still produces a single undersized chunk instead of dropping content`() {
        // Decisão documentada em Chunker: nunca descartar conteúdo real só para atingir o mínimo de
        // 300 tokens — cabe ao ChunkValidator (T5) rejeitar esse caso, não ao Chunker.
        val shortParagraph = words(1..50) // 50 tokens, bem abaixo de MIN_CHUNK_TOKENS (300)
        val pageTexts = mapOf(1 to shortParagraph)

        val chunks = chunker.chunk(pageTexts)

        assertEquals(1, chunks.size)
        assertEquals(50, chunks[0].tokenCount)
        assertEquals(shortParagraph, chunks[0].text)
        assertEquals(1, chunks[0].page)
        assertEquals(0, chunks[0].charOffset)
    }

    // --- fronteiras de parágrafo ---

    @Test
    fun `chunk never splits a paragraph across two chunks when a paragraph boundary is available`() {
        // Dois parágrafos de 400 tokens cada somam 800, que excede MAX_OWN_CONTENT_TOKENS (695) —
        // o segundo parágrafo inteiro precisa ficar de fora do primeiro chunk.
        val paragraphA = words(1..400)
        val paragraphB = words(401..800)
        val pageTexts = mapOf(1 to "$paragraphA\n\n$paragraphB")

        val chunks = chunker.chunk(pageTexts)

        assertEquals(2, chunks.size)
        // Primeiro chunk: só o parágrafo A, sem overlap (é o primeiro chunk) e sem nenhum token de B.
        assertEquals(paragraphA, chunks[0].text)
        assertEquals(400, chunks[0].tokenCount)
        assertTrue("w401" !in chunks[0].text) { "chunk 0 não deveria conter nenhum token do parágrafo B" }

        // Segundo chunk: overlap do fim de A (ceil(400 * 0.15) = 60 tokens: w341..w400) + parágrafo B inteiro, intacto.
        assertTrue(chunks[1].text.endsWith(paragraphB)) { "parágrafo B precisa estar íntegro no fim do chunk 1" }
        assertTrue(chunks[1].text.startsWith(words(341..400))) { "overlap esperado são os últimos 60 tokens do parágrafo A" }
        assertEquals(1, chunks[1].page)
    }

    @Test
    fun `chunk keeps small paragraphs from different pages together in the same chunk when they fit the token budget`() {
        val pageTexts =
            mapOf(
                1 to words(1..20),
                2 to words(21..40),
            )

        val chunks = chunker.chunk(pageTexts)

        assertEquals(1, chunks.size)
        assertEquals("${words(1..20)}\n\n${words(21..40)}", chunks[0].text)
        // page/charOffset refletem a origem do conteúdo PRÓPRIO do chunk (primeiro parágrafo incluído).
        assertEquals(1, chunks[0].page)
        assertEquals(0, chunks[0].charOffset)
    }

    // --- parágrafo único maior que o teto (corte inevitável) ---

    @Test
    fun `chunk splits a single paragraph larger than the own-content cap at word boundaries, never mid-word`() {
        val hugeParagraph = words(1..1000) // 1000 tokens > MAX_OWN_CONTENT_TOKENS (695): corte inevitável
        val pageTexts = mapOf(1 to hugeParagraph)

        val chunks = chunker.chunk(pageTexts)

        assertEquals(2, chunks.size)

        // Primeiro pedaço: exatamente os primeiros 695 tokens, sem overlap (primeiro chunk).
        assertEquals(695, chunks[0].tokenCount)
        assertEquals(words(1..695), chunks[0].text)
        assertEquals(0, chunks[0].charOffset)

        // Segundo pedaço: overlap do fim do primeiro pedaço (ceil(695 * 0.15) = 105 tokens) + o resto do parágrafo.
        assertEquals(410, chunks[1].tokenCount) // 105 (overlap) + 305 (w696..w1000)
        assertTrue(chunks[1].text.endsWith(words(696..1000))) { "resto do parágrafo precisa estar íntegro no fim" }
        assertTrue(chunks[1].text.startsWith("w591 w592")) { "overlap esperado começa nos últimos 105 tokens do primeiro pedaço" }
        assertTrue("w695\n\nw696" in chunks[1].text) { "overlap e conteúdo novo são unidos por um separador, não grudados" }
        assertEquals(hugeParagraph.indexOf("w696"), chunks[1].charOffset)
    }

    // --- MIN-first (T5b): grupo não pode fechar abaixo do mínimo se sobrar parágrafo para completá-lo ---

    @Test
    fun `chunk grows the group past the minimum by cutting the following paragraph instead of closing early`() {
        // Parágrafo curto (100 tokens, bem abaixo de MIN_CHUNK_TOKENS) seguido de um parágrafo que,
        // somado ao curto, ultrapassaria MAX_OWN_CONTENT_TOKENS (695) — mas que sozinho cabe dentro
        // do teto (650 <= 695), então splitOversizedParagraph não o corta de antemão.
        val shortParagraph = words(1..100)
        val nextParagraph = words(101..750) // 650 tokens
        val pageTexts = mapOf(1 to "$shortParagraph\n\n$nextParagraph")

        val chunks = chunker.chunk(pageTexts)

        assertEquals(2, chunks.size)

        // Primeiro chunk: parágrafo curto inteiro + o pedaço de nextParagraph necessário para
        // completar até MAX_OWN_CONTENT_TOKENS (695) — nunca fica abaixo de MIN_CHUNK_TOKENS.
        assertEquals(695, chunks[0].tokenCount)
        assertTrue(
            chunks[0].tokenCount >= MIN_CHUNK_TOKENS,
        ) { "primeiro grupo não pode fechar abaixo do mínimo havendo texto para completá-lo" }
        assertTrue(chunks[0].text.startsWith(shortParagraph)) { "parágrafo curto precisa estar íntegro no início do chunk 0" }
        assertEquals(words(1..100) + "\n\n" + words(101..695), chunks[0].text)

        // Segundo chunk (último grupo, sobra 55 tokens de nextParagraph): overlap do fim do chunk 0
        // (ceil(695 * 0.15) = 105 tokens) + o restante do parágrafo cortado (w696..w750), intacto,
        // como um parágrafo próprio (não é uma quebra "herdada" do parágrafo original — este é um
        // ParagraphUnit novo criado pelo corte, o texto original não tinha quebra entre w695/w696).
        assertEquals(55, countTokens(words(696..750)))
        assertEquals(160, chunks[1].tokenCount) // 105 (overlap) + 55 (w696..w750)
        assertTrue(chunks[1].text.endsWith(words(696..750))) { "resto do parágrafo cortado precisa estar íntegro no fim do último chunk" }
    }

    @Test
    fun `chunk does not cut a paragraph unnecessarily once the group already reached the minimum`() {
        // Parágrafo já acima do mínimo (400 tokens) seguido de um parágrafo grande que não cabe
        // inteiro (400) — como o grupo já atingiu MIN_CHUNK_TOKENS com o primeiro parágrafo, ele
        // fecha ali, sem cortar o segundo parágrafo (mesmo caso do teste de fronteira de parágrafo
        // acima, reafirmado aqui sob a ótica do MIN-first).
        val paragraphA = words(1..400)
        val paragraphB = words(401..800)
        val pageTexts = mapOf(1 to "$paragraphA\n\n$paragraphB")

        val chunks = chunker.chunk(pageTexts)

        assertEquals(2, chunks.size)
        assertEquals(paragraphA, chunks[0].text)
        assertEquals(400, chunks[0].tokenCount)
    }

    // --- overlap medido entre vizinhos, em texto com múltiplos chunks ---

    @Test
    fun `chunk keeps the measured token overlap between every pair of neighboring chunks within 10 to 20 percent`() {
        // Dez parágrafos de 100 tokens cada (1000 tokens no total) força múltiplos chunks.
        val paragraphs = (0 until 10).map { i -> words((i * 100 + 1)..(i * 100 + 100)) }
        val pageTexts = mapOf(1 to paragraphs.joinToString("\n\n"))

        val chunks = chunker.chunk(pageTexts)

        assertTrue(chunks.size >= 2) { "fixture precisa gerar mais de um chunk para o teste fazer sentido" }
        for (i in 0 until chunks.size - 1) {
            val previousTokens = tokenize(chunks[i].text)
            val nextTokens = tokenize(chunks[i + 1].text)
            val maxPossible = minOf(previousTokens.size, nextTokens.size)
            var overlap = 0
            for (n in maxPossible downTo 1) {
                if (previousTokens.takeLast(n) == nextTokens.take(n)) {
                    overlap = n
                    break
                }
            }
            val ratio = overlap.toDouble() / chunks[i].tokenCount
            assertTrue(ratio in OVERLAP_MIN_RATIO..OVERLAP_MAX_RATIO) {
                "overlap entre chunk $i e chunk ${i + 1} foi $ratio, fora de $OVERLAP_MIN_RATIO..$OVERLAP_MAX_RATIO"
            }
        }
    }
}
