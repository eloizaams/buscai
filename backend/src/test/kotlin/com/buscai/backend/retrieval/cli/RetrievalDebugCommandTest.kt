package com.buscai.backend.retrieval.cli

import com.buscai.backend.retrieval.RetrievalResult
import com.buscai.backend.retrieval.RetrievalScope
import com.buscai.backend.retrieval.RetrievedChunk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Testes unitários de [RetrievalDebugArgsParser] e [RetrievalResultFormatter] (T7) — cobrem a
 * lógica de parsing/formatação isoladamente de [RetrievalDebugCommand], sem subir o Spring context
 * (nenhum `CommandLineRunner` é instanciado aqui) — mesmo padrão de `IngestCommandTest`
 * (`ingestion.cli`, T10 de `specs/ingestao-pdf/tasks.md`).
 */
class RetrievalDebugCommandTest {
    // --- RetrievalDebugArgsParser: caminho feliz ---

    @Test
    fun `parse aceita apenas --query e resolve escopo AllBooks quando --books esta ausente`() {
        val result = RetrievalDebugArgsParser.parse(arrayOf("--query=qual o nome do protagonista"))

        assertTrue(result is RetrievalDebugArgsResult.Parsed)
        val args = (result as RetrievalDebugArgsResult.Parsed).args
        assertEquals("qual o nome do protagonista", args.query)
        assertEquals(RetrievalScope.AllBooks, args.scope)
    }

    @Test
    fun `parse aceita --books com um unico bookId`() {
        val result = RetrievalDebugArgsParser.parse(arrayOf("--query=pergunta", "--books=dom-casmurro"))

        assertTrue(result is RetrievalDebugArgsResult.Parsed)
        val args = (result as RetrievalDebugArgsResult.Parsed).args
        assertEquals(RetrievalScope.Books(setOf("dom-casmurro")), args.scope)
    }

    @Test
    fun `parse aceita --books com lista separada por virgula e ignora espacos`() {
        val result =
            RetrievalDebugArgsParser.parse(
                arrayOf("--query=pergunta", "--books=dom-casmurro, memorias-postumas ,outro"),
            )

        assertTrue(result is RetrievalDebugArgsResult.Parsed)
        val args = (result as RetrievalDebugArgsResult.Parsed).args
        assertEquals(RetrievalScope.Books(setOf("dom-casmurro", "memorias-postumas", "outro")), args.scope)
    }

    // --- RetrievalDebugArgsParser: erros de parsing ---

    @Test
    fun `parse rejeita quando --query esta ausente`() {
        val result = RetrievalDebugArgsParser.parse(arrayOf("--books=dom-casmurro"))

        assertTrue(result is RetrievalDebugArgsResult.Error)
        assertTrue((result as RetrievalDebugArgsResult.Error).message.contains("--query"))
    }

    @Test
    fun `parse rejeita quando --query esta em branco`() {
        val result = RetrievalDebugArgsParser.parse(arrayOf("--query="))

        assertTrue(result is RetrievalDebugArgsResult.Error)
    }

    @Test
    fun `parse rejeita --books em branco`() {
        val result = RetrievalDebugArgsParser.parse(arrayOf("--query=pergunta", "--books= , ,"))

        assertTrue(result is RetrievalDebugArgsResult.Error)
        assertTrue((result as RetrievalDebugArgsResult.Error).message.contains("--books"))
    }

    @Test
    fun `parse rejeita argumento nao reconhecido`() {
        val result = RetrievalDebugArgsParser.parse(arrayOf("--query=pergunta", "--foo"))

        assertTrue(result is RetrievalDebugArgsResult.Error)
    }

    // --- RetrievalResultFormatter: cada variante de RetrievalResult ---

    @Test
    fun `format traduz NoRelevantContext numa mensagem clara`() {
        val message = RetrievalResultFormatter.format(RetrievalResult.NoRelevantContext)

        assertTrue(message.contains("Nenhum contexto relevante"))
    }

    @Test
    fun `format traduz Found com chunks vazios como sem contexto relevante`() {
        val message = RetrievalResultFormatter.format(RetrievalResult.Found(emptyList()))

        assertTrue(message.contains("Nenhum contexto relevante"))
    }

    @Test
    fun `format traduz Found imprimindo bookId, pagina, capitulo, score e trecho de cada chunk`() {
        val chunk =
            RetrievedChunk(
                chunkId = UUID.randomUUID(),
                bookId = "dom-casmurro",
                bookTitle = "Dom Casmurro",
                page = 42,
                chapter = "Capítulo IX",
                text = "Bentinho e Capitu se conhecem quando crianças.",
                score = 0.8765,
            )

        val message = RetrievalResultFormatter.format(RetrievalResult.Found(listOf(chunk)))

        assertTrue(message.contains("dom-casmurro"))
        assertTrue(message.contains("42"))
        assertTrue(message.contains("Capítulo IX"))
        assertTrue(message.contains("0.8765"))
        assertTrue(message.contains("Bentinho e Capitu"))
    }

    @Test
    fun `format trunca trecho de texto muito longo`() {
        val textoLongo = "a".repeat(500)
        val chunk =
            RetrievedChunk(
                chunkId = UUID.randomUUID(),
                bookId = "livro-longo",
                bookTitle = "Livro Longo",
                page = 1,
                chapter = null,
                text = textoLongo,
                score = 0.5,
            )

        val message = RetrievalResultFormatter.format(RetrievalResult.Found(listOf(chunk)))

        assertTrue(message.length < textoLongo.length, "esperava o trecho truncado, mensagem: $message")
        assertTrue(message.contains("..."))
    }

    @Test
    fun `format imprime cada chunk de Found com multiplos candidatos em linhas separadas`() {
        val chunk1 =
            RetrievedChunk(
                chunkId = UUID.randomUUID(),
                bookId = "livro-1",
                bookTitle = "Livro 1",
                page = 1,
                chapter = null,
                text = "trecho 1",
                score = 0.9,
            )
        val chunk2 =
            RetrievedChunk(
                chunkId = UUID.randomUUID(),
                bookId = "livro-2",
                bookTitle = "Livro 2",
                page = 2,
                chapter = null,
                text = "trecho 2",
                score = 0.1,
            )

        val message = RetrievalResultFormatter.format(RetrievalResult.Found(listOf(chunk1, chunk2)))

        assertEquals(2, message.lines().size)
        assertTrue(message.contains("livro-1"))
        assertTrue(message.contains("livro-2"))
    }
}
