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

    // --- referenceType (ADR-0013) ---

    @Test
    fun `chunk fills reference with the chapter label current at the first paragraph of each group when referenceType is CHAPTER`() {
        // Parágrafos grandes o bastante para forçar dois grupos (mesma mecânica do teste de
        // fronteira de parágrafo acima) — o segundo capítulo começa dentro do primeiro grupo, então
        // sua referência só aparece no primeiro parágrafo do PRÓXIMO grupo (imprecisão documentada
        // em plan.md: "chunk cruza fronteira de capítulo" já existe hoje de forma equivalente com página).
        val pageTexts =
            mapOf(
                1 to "Capítulo I\n\n${words(1..400)}",
                2 to "Capítulo II\n\n${words(401..800)}",
            )

        val chunks = chunker.chunk(pageTexts, ReferenceType.CHAPTER)

        assertEquals(2, chunks.size)
        assertEquals("Capítulo I", chunks[0].reference)
        assertEquals("Capítulo II", chunks[1].reference)
    }

    @Test
    fun `chunk leaves reference null for every chunk when referenceType is null`() {
        val pageTexts = mapOf(1 to words(1..20))

        val chunks = chunker.chunk(pageTexts)

        assertEquals(1, chunks.size)
        assertEquals(null, chunks[0].reference)
    }

    @Test
    fun `chunk never splits a paragraph at a line that looks like a numbered-item opening when referenceType is not NUMBERED_ITEM`() {
        // Risco de falso-positivo já documentado (CA5 da spec): uma linha "1. algo" no MEIO de um
        // parágrafo de prosa comum (não a primeira linha dele, para o teste realmente exercitar o
        // guard — a primeira linha de um segmento já delimitado por linha em branco nunca gera uma
        // quebra nova, guard ou não) não é necessariamente um item numerado de verdade. A nova
        // fronteira extra de parágrafo (introduzida nesta task) só pode se aplicar quando
        // referenceType == NUMBERED_ITEM (CA3) — para null/CHAPTER, o guard early-return de
        // splitBlankLineSegment precisa manter esse parágrafo inteiro unificado, sem quebrar na
        // linha "1. item...".
        val secondParagraph = "Introdução da lista.\n1. item dentro de uma lista em prosa\ncontinuação"
        val pageText = "Capítulo I\n\n$secondParagraph"
        val pageTexts = mapOf(1 to pageText)

        val nullChunks = chunker.chunk(pageTexts)
        assertEquals(1, nullChunks.size)
        assertEquals(pageText, nullChunks[0].text)
        assertEquals(null, nullChunks[0].reference)

        val chapterChunks = chunker.chunk(pageTexts, ReferenceType.CHAPTER)
        assertEquals(1, chapterChunks.size)
        assertEquals(pageText, chapterChunks[0].text)
        assertEquals("Capítulo I", chapterChunks[0].reference)
    }

    /** "1. w1 w2 ... wN" — abertura de item numerado (ADR-0013) seguida de conteúdo previsível. */
    private fun item(
        number: Int,
        range: IntRange,
    ): String = "$number. ${words(range)}"

    @Test
    fun `chunk with NUMBERED_ITEM never cuts a paragraph to mix two different items, even leaving a chunk below the minimum`() {
        // item 1 (100 tokens) + item 2 (650 tokens) somam 750, que excede MAX_OWN_CONTENT_TOKENS
        // (695) — no estilo CHAPTER/null, o MIN-first cortaria o item 2 para completar o grupo do
        // item 1 até o teto (ver teste equivalente sem referenceType). Para NUMBERED_ITEM isso é
        // proibido: o item 1 fecha sozinho, mesmo abaixo de MIN_CHUNK_TOKENS (300).
        val item1 = item(1, 1..99) // 1 ("1.") + 99 = 100 tokens
        val item2 = item(2, 101..749) // 1 ("2.") + 649 = 650 tokens
        val pageTexts = mapOf(1 to "$item1\n\n$item2")

        val chunks = chunker.chunk(pageTexts, ReferenceType.NUMBERED_ITEM)

        assertEquals(2, chunks.size)

        // Item 1 isolado, abaixo do piso — nunca cortado para "completar" o grupo com o item 2.
        assertEquals(item1, chunks[0].text)
        assertEquals(100, chunks[0].tokenCount)
        assertTrue(
            chunks[0].tokenCount < MIN_CHUNK_TOKENS,
        ) { "item isolado precisa ficar abaixo do piso, não completado às custas do item 2" }
        assertEquals("1", chunks[0].reference)

        // Item 2 íntegro no chunk seguinte, nunca partido para caber no grupo anterior.
        assertTrue(chunks[1].text.endsWith(item2)) { "item 2 precisa estar íntegro, nunca cortado para caber no grupo do item 1" }
        assertEquals("2", chunks[1].reference)
    }

    @Test
    fun `chunk with NUMBERED_ITEM groups short consecutive items into one chunk with an interval reference`() {
        val item1 = item(1, 1..50)
        val item2 = item(2, 51..100)
        val item3 = item(3, 101..150)
        val pageTexts = mapOf(1 to "$item1\n\n$item2\n\n$item3")

        val chunks = chunker.chunk(pageTexts, ReferenceType.NUMBERED_ITEM)

        assertEquals(1, chunks.size)
        assertEquals("$item1\n\n$item2\n\n$item3", chunks[0].text)
        assertEquals("1–3", chunks[0].reference)
    }

    @Test
    fun `chunk with NUMBERED_ITEM never splits an item across chunks when its continuation alone would overflow the accumulated group`() {
        // Reprodução do achado Crítico do code-reviewer na primeira passada de T1: o laço guloso de
        // groupUnits processava cada parágrafo isoladamente, então a abertura do item 157 (curta)
        // cabia no grupo já ocupado pelo item 156, mas a continuação de 157 (sem abertura numerada
        // própria, apenas herdando a reference) sozinha estourava o orçamento acumulado e migrava
        // para o próximo grupo — junto com o item 158, partindo o item 157 ao meio. A correção
        // (coalesceItemContinuations) funde abertura + continuação numa única unidade ANTES do
        // agrupamento, então o laço guloso nunca mais vê "meio item".
        val item156 = item(156, 1..599) // "156. w1..w599" = 1 + 599 = 600 tokens
        val item157Main = item(157, 600..609) // "157. w600..w609" = 1 + 10 = 11 tokens
        val item157Continuation = words(610..1249) // continuação sem abertura numerada = 640 tokens
        val item158 = item(158, 1250..1269) // "158. w1250..w1269" = 1 + 20 = 21 tokens
        val pageTexts =
            mapOf(
                1 to listOf(item156, item157Main, item157Continuation, item158).joinToString("\n\n"),
            )

        val chunks = chunker.chunk(pageTexts, ReferenceType.NUMBERED_ITEM)

        val chunkWithItem157Opening = chunks.single { item157Main in it.text }
        assertTrue(item157Continuation in chunkWithItem157Opening.text) {
            "a continuação do item 157 precisa estar no mesmo chunk da sua abertura — " +
                "nunca partido entre dois chunks"
        }
        // item 156 (600) + abertura de 157 (11) + continuação de 157 (640) somam 1251, acima de
        // MAX_OWN_CONTENT_TOKENS (695) — item 156 precisa ficar isolado no chunk anterior, nunca
        // cortado nem compartilhando grupo com o 157.
        val chunkWithItem156 = chunks.single { item156 in it.text }
        assertTrue(item157Main !in chunkWithItem156.text) {
            "item 156 e a abertura do item 157 não podem compartilhar o mesmo chunk aqui (estouraria o teto)"
        }
    }

    @Test
    fun `chunk with NUMBERED_ITEM splits consecutive items with no blank line between them, each with its own reference and charOffset`() {
        // Reprodução do bug real (baseline com "O Livro dos Espíritos"): o PDF não tem linha em
        // branco entre um item numerado e o próximo — hoje eles se fundiriam num único parágrafo
        // de prosa e o ReferenceAnnotator nunca encontraria a abertura de cada item individualmente.
        // Itens dimensionados (>347 tokens cada) para que nenhum par caiba junto em
        // MAX_OWN_CONTENT_TOKENS (695) — assim cada item também vira seu próprio chunk, o que deixa
        // charOffset de cada um (não só do primeiro) observável diretamente no ChunkDraft.
        val item1 = item(1, 1..692) // 693 tokens
        val item2 = item(2, 693..1384) // 692 tokens
        val item3 = item(3, 1385..2076) // 692 tokens
        val pageText = "$item1\n$item2\n$item3" // só \n simples, igual ao PDF real — sem linha em branco
        val pageTexts = mapOf(1 to pageText)

        val chunks = chunker.chunk(pageTexts, ReferenceType.NUMBERED_ITEM)

        assertEquals(3, chunks.size)

        assertEquals(item1, chunks[0].text) // primeiro chunk, sem overlap herdado
        assertEquals("1", chunks[0].reference)
        assertEquals(0, chunks[0].charOffset)

        assertTrue(chunks[1].text.endsWith(item2)) { "item 2 precisa estar íntegro no fim do chunk 1" }
        assertEquals("2", chunks[1].reference)
        assertEquals(pageText.indexOf(item2), chunks[1].charOffset)

        assertTrue(chunks[2].text.endsWith(item3)) { "item 3 precisa estar íntegro no fim do chunk 2" }
        assertEquals("3", chunks[2].reference)
        assertEquals(pageText.indexOf(item3), chunks[2].charOffset)

        val validationResult = ChunkValidator().validate(chunks, ReferenceType.NUMBERED_ITEM)
        assertEquals(ChunkValidationResult.Valid, validationResult) {
            "itens deste tamanho não deveriam violar o ChunkValidator: $validationResult"
        }
    }

    @Test
    fun `chunk with NUMBERED_ITEM never triggers an overlap-ratio violation with a very short item alone between two long ones`() {
        // Achado do plan.md ("ChunkValidator nunca foi exercitado por itens atômicos curtos"):
        // measureOverlapRatio divide pelo tokenCount TOTAL do chunk anterior (que já inclui o
        // overlap herdado por ELE), não pelo conteúdo próprio — para um grupo de item único muito
        // curto (ex.: uma resposta de uma palavra, comum neste livro em formato pergunta-resposta),
        // isso pode fazer o overlap medido cair bem abaixo de OVERLAP_MIN_RATIO. Itens
        // grandes/pequenos alternados, sem linha em branco entre eles, reproduzem o caso exato.
        val item1 = item(1, 1..692) // 693 tokens — grande, isolado no próprio grupo
        val item2 = item(2, 693..696) // 5 tokens — curto, isolado no próprio grupo
        val item3 = item(3, 697..1388) // 693 tokens — grande de novo
        val item4 = item(4, 1389..1392) // 5 tokens — curto de novo
        val pageText = "$item1\n$item2\n$item3\n$item4"
        val pageTexts = mapOf(1 to pageText)

        val chunks = chunker.chunk(pageTexts, ReferenceType.NUMBERED_ITEM)

        assertEquals(4, chunks.size)
        assertEquals(listOf("1", "2", "3", "4"), chunks.map { it.reference })

        val validationResult = ChunkValidator().validate(chunks, ReferenceType.NUMBERED_ITEM)
        assertEquals(ChunkValidationResult.Valid, validationResult) {
            "checagem de overlap precisa ser pulada para NUMBERED_ITEM (ADR-0013/ADR-0008): $validationResult"
        }
    }
}
