package com.buscai.backend.generation.cli

import com.buscai.backend.generation.GenerationAnswer
import com.buscai.backend.generation.GenerationService
import com.buscai.backend.generation.claude.ClaudeClientException
import com.buscai.backend.retrieval.RetrievalScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Testes unitários de [GenerationDebugArgsParser] e [GenerationDebugOutputFormatter] (T7) — cobrem
 * a lógica de parsing/formatação isoladamente de [GenerationDebugCommand], sem subir o Spring
 * context (nenhum `CommandLineRunner` nem [GenerationService] real é instanciado aqui) — mesmo
 * padrão de `RetrievalDebugCommandTest` (`retrieval.cli`, T7 do retrieval).
 */
class GenerationDebugCommandTest {
    // --- GenerationDebugArgsParser: caminho feliz ---

    @Test
    fun `parse aceita apenas --query e resolve escopo AllBooks e conversationId nulo quando ausentes`() {
        val result = GenerationDebugArgsParser.parse(arrayOf("--query=qual o nome do protagonista"))

        assertTrue(result is GenerationDebugArgsResult.Parsed)
        val args = (result as GenerationDebugArgsResult.Parsed).args
        assertEquals("qual o nome do protagonista", args.query)
        assertEquals(RetrievalScope.AllBooks, args.scope)
        assertEquals(null, args.conversationId)
    }

    @Test
    fun `parse aceita --books com lista separada por virgula e ignora espacos`() {
        val result =
            GenerationDebugArgsParser.parse(
                arrayOf("--query=pergunta", "--books=dom-casmurro, memorias-postumas ,outro"),
            )

        assertTrue(result is GenerationDebugArgsResult.Parsed)
        val args = (result as GenerationDebugArgsResult.Parsed).args
        assertEquals(RetrievalScope.Books(setOf("dom-casmurro", "memorias-postumas", "outro")), args.scope)
    }

    @Test
    fun `parse aceita --conversation-id valido`() {
        val conversationId = UUID.randomUUID()

        val result =
            GenerationDebugArgsParser.parse(
                arrayOf("--query=e no capitulo seguinte?", "--conversation-id=$conversationId"),
            )

        assertTrue(result is GenerationDebugArgsResult.Parsed)
        val args = (result as GenerationDebugArgsResult.Parsed).args
        assertEquals(conversationId, args.conversationId)
    }

    // --- GenerationDebugArgsParser: erros de parsing ---

    @Test
    fun `parse rejeita quando --query esta ausente`() {
        val result = GenerationDebugArgsParser.parse(arrayOf("--books=dom-casmurro"))

        assertTrue(result is GenerationDebugArgsResult.Error)
        assertTrue((result as GenerationDebugArgsResult.Error).message.contains("--query"))
    }

    @Test
    fun `parse rejeita quando --query esta em branco`() {
        val result = GenerationDebugArgsParser.parse(arrayOf("--query="))

        assertTrue(result is GenerationDebugArgsResult.Error)
    }

    @Test
    fun `parse rejeita --books em branco`() {
        val result = GenerationDebugArgsParser.parse(arrayOf("--query=pergunta", "--books= , ,"))

        assertTrue(result is GenerationDebugArgsResult.Error)
        assertTrue((result as GenerationDebugArgsResult.Error).message.contains("--books"))
    }

    @Test
    fun `parse rejeita --conversation-id que nao e um UUID valido`() {
        val result = GenerationDebugArgsParser.parse(arrayOf("--query=pergunta", "--conversation-id=nao-e-um-uuid"))

        assertTrue(result is GenerationDebugArgsResult.Error)
        assertTrue((result as GenerationDebugArgsResult.Error).message.contains("--conversation-id"))
    }

    @Test
    fun `parse rejeita argumento nao reconhecido`() {
        val result = GenerationDebugArgsParser.parse(arrayOf("--query=pergunta", "--foo"))

        assertTrue(result is GenerationDebugArgsResult.Error)
    }

    // --- GenerationDebugOutputFormatter: cada variante de resultado ---

    @Test
    fun `formatConversationResolved indica conversa nova`() {
        val conversationId = UUID.randomUUID()

        val message = GenerationDebugOutputFormatter.formatConversationResolved(conversationId, isNew = true)

        assertTrue(message.contains("nova"))
        assertTrue(message.contains(conversationId.toString()))
    }

    @Test
    fun `formatConversationResolved indica conversa existente`() {
        val conversationId = UUID.randomUUID()

        val message = GenerationDebugOutputFormatter.formatConversationResolved(conversationId, isNew = false)

        assertTrue(message.contains(conversationId.toString()))
        assertTrue(!message.contains("nova"))
    }

    @Test
    fun `formatDone de uma resposta gerada normalmente (sucesso) inclui o conversationId`() {
        val answer = GenerationAnswer(conversationId = UUID.randomUUID(), text = "Resposta completa com citação.")

        val message = GenerationDebugOutputFormatter.formatDone(answer)

        assertTrue(message.contains(answer.conversationId.toString()))
    }

    @Test
    fun `formatDone da mensagem fixa de NoRelevantContext produz a mesma saida de conclusao`() {
        val answer =
            GenerationAnswer(
                conversationId = UUID.randomUUID(),
                text = GenerationService.NO_RELEVANT_CONTEXT_MESSAGE,
            )

        val message = GenerationDebugOutputFormatter.formatDone(answer)

        assertTrue(message.contains(answer.conversationId.toString()))
        assertTrue(message.contains("fim"))
    }

    @Test
    fun `formatError traduz uma excecao numa mensagem clara sem expor detalhe de infraestrutura`() {
        val error = ClaudeClientException("falha simulada de rede")

        val message = GenerationDebugOutputFormatter.formatError(error)

        assertTrue(message.contains("Erro"))
        assertTrue(message.contains("falha simulada de rede"))
    }
}
