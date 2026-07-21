package com.buscai.backend.ingestion.cli

import com.buscai.backend.ingestion.IngestionOutcome
import com.buscai.backend.ingestion.chunking.ReferenceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/**
 * Testes unitários de [IngestArgsParser] e [IngestionOutcomeFormatter] (T10) — cobrem a lógica de
 * parsing/formatação isoladamente de [IngestCommand], sem subir o Spring context (nenhum
 * `CommandLineRunner` é instanciado aqui).
 */
class IngestCommandTest {
    @TempDir
    lateinit var tempDir: Path

    private fun existingPdf(): Path {
        val file = tempDir.resolve("livro.pdf")
        Files.writeString(file, "conteúdo qualquer")
        return file
    }

    // --- IngestArgsParser: caminho feliz ---

    @Test
    fun `parse aceita book-id e file obrigatorios, sem reindex e sem title`() {
        val file = existingPdf()

        val result = IngestArgsParser.parse(arrayOf("--book-id=dom-casmurro", "--file=$file"))

        assertTrue(result is IngestArgsResult.Parsed)
        val args = (result as IngestArgsResult.Parsed).args
        assertEquals("dom-casmurro", args.bookId)
        assertEquals(file.toFile(), args.file)
        assertEquals("dom-casmurro", args.title) // sem --title, usa o próprio book-id
        assertEquals(false, args.reindex)
    }

    @Test
    fun `parse aceita --reindex e --title explicitos`() {
        val file = existingPdf()

        val result =
            IngestArgsParser.parse(
                arrayOf("--book-id=dom-casmurro", "--file=$file", "--title=Dom Casmurro", "--reindex"),
            )

        assertTrue(result is IngestArgsResult.Parsed)
        val args = (result as IngestArgsResult.Parsed).args
        assertEquals("Dom Casmurro", args.title)
        assertEquals(true, args.reindex)
    }

    // --- IngestArgsParser: --reference-style (ADR-0013) ---

    @Test
    fun `parse sem --reference-style deixa referenceType nulo (comportamento atual)`() {
        val file = existingPdf()

        val result = IngestArgsParser.parse(arrayOf("--book-id=dom-casmurro", "--file=$file"))

        assertTrue(result is IngestArgsResult.Parsed)
        assertNull((result as IngestArgsResult.Parsed).args.referenceType)
    }

    @Test
    fun `parse aceita --reference-style=chapter`() {
        val file = existingPdf()

        val result =
            IngestArgsParser.parse(arrayOf("--book-id=dom-casmurro", "--file=$file", "--reference-style=chapter"))

        assertTrue(result is IngestArgsResult.Parsed)
        assertEquals(ReferenceType.CHAPTER, (result as IngestArgsResult.Parsed).args.referenceType)
    }

    @Test
    fun `parse aceita --reference-style=numbered-item`() {
        val file = existingPdf()

        val result =
            IngestArgsParser.parse(
                arrayOf("--book-id=dom-casmurro", "--file=$file", "--reference-style=numbered-item"),
            )

        assertTrue(result is IngestArgsResult.Parsed)
        assertEquals(ReferenceType.NUMBERED_ITEM, (result as IngestArgsResult.Parsed).args.referenceType)
    }

    @Test
    fun `parse rejeita valor invalido de --reference-style, sem chegar a IngestionService`() {
        val file = existingPdf()

        val result =
            IngestArgsParser.parse(
                arrayOf("--book-id=dom-casmurro", "--file=$file", "--reference-style=capitulo"),
            )

        assertTrue(result is IngestArgsResult.Error)
        assertTrue((result as IngestArgsResult.Error).message.contains("reference-style"))
    }

    // --- IngestArgsParser: erros de parsing (CA7) ---

    @Test
    fun `parse rejeita quando --book-id esta ausente`() {
        val file = existingPdf()

        val result = IngestArgsParser.parse(arrayOf("--file=$file"))

        assertTrue(result is IngestArgsResult.Error)
        assertTrue((result as IngestArgsResult.Error).message.contains("book-id"))
    }

    @Test
    fun `parse rejeita quando --file esta ausente`() {
        val result = IngestArgsParser.parse(arrayOf("--book-id=dom-casmurro"))

        assertTrue(result is IngestArgsResult.Error)
        assertTrue((result as IngestArgsResult.Error).message.contains("--file"))
    }

    @Test
    fun `parse rejeita quando o arquivo apontado por --file nao existe`() {
        val inexistente = tempDir.resolve("nao-existe.pdf")

        val result = IngestArgsParser.parse(arrayOf("--book-id=dom-casmurro", "--file=$inexistente"))

        assertTrue(result is IngestArgsResult.Error)
        assertTrue((result as IngestArgsResult.Error).message.contains("não encontrado"))
    }

    @Test
    fun `parse rejeita argumento nao reconhecido`() {
        val file = existingPdf()

        val result = IngestArgsParser.parse(arrayOf("--book-id=dom-casmurro", "--file=$file", "--foo"))

        assertTrue(result is IngestArgsResult.Error)
    }

    // --- IngestionOutcomeFormatter: cada variante de IngestionOutcome ---

    @Test
    fun `format traduz Skipped citando bookId e versao existente`() {
        val versionId = UUID.randomUUID()
        val outcome = IngestionOutcome.Skipped(bookId = "dom-casmurro", existingVersionId = versionId)

        val message = IngestionOutcomeFormatter.format(outcome, Duration.ZERO)

        assertTrue(message.contains("dom-casmurro"))
        assertTrue(message.contains(versionId.toString()))
        assertTrue(message.contains("já ingerido"))
    }

    @Test
    fun `format traduz ReindexRequired explicando que reindex e necessario`() {
        val versionId = UUID.randomUUID()
        val outcome = IngestionOutcome.ReindexRequired(bookId = "dom-casmurro", existingVersionId = versionId)

        val message = IngestionOutcomeFormatter.format(outcome, Duration.ZERO)

        assertTrue(message.contains("dom-casmurro"))
        assertTrue(message.contains("--reindex"))
    }

    @Test
    fun `format traduz Completed com paginas, chunks e tempo total`() {
        val versionId = UUID.randomUUID()
        val outcome =
            IngestionOutcome.Completed(bookId = "dom-casmurro", versionId = versionId, pageCount = 250, chunkCount = 900)

        val message = IngestionOutcomeFormatter.format(outcome, Duration.ofSeconds(12))

        assertTrue(message.contains("dom-casmurro"))
        assertTrue(message.contains("250"))
        assertTrue(message.contains("900"))
        assertTrue(message.contains("12"))
    }

    @Test
    fun `format traduz Failed citando o motivo especifico`() {
        val versionId = UUID.randomUUID()
        val outcome = IngestionOutcome.Failed(bookId = "dom-casmurro", versionId = versionId, reason = "chunk vazio na página 3")

        val message = IngestionOutcomeFormatter.format(outcome, Duration.ZERO)

        assertTrue(message.contains("dom-casmurro"))
        assertTrue(message.contains("chunk vazio na página 3"))
    }
}
