package com.buscai.backend.ingestion.chunking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChunkValidatorTest {
    private val validator = ChunkValidator()

    /** Gera "w1 w2 ... wN" (uma "token" por palavra, mesma definição usada por [tokenize]/[countTokens]). */
    private fun words(range: IntRange): String = range.joinToString(" ") { "w$it" }

    private fun draft(
        text: String,
        page: Int = 1,
        charOffset: Int = 0,
    ) = ChunkDraft(page = page, charOffset = charOffset, tokenCount = countTokens(text), text = text)

    @Test
    fun `validate accepts two neighboring chunks with size and overlap within the documented bounds`() {
        val previousText = words(1..700) // 700 tokens, dentro de 300-800
        // overlap = últimos 100 tokens do anterior (w601..w700) = 100 / 700 ~= 14,3%, dentro de 10-20%.
        val nextText = "${words(601..700)}\n\n${words(701..1000)}" // 100 (overlap) + 300 (novo) = 400 tokens

        val result = validator.validate(listOf(draft(previousText), draft(nextText)))

        assertEquals(ChunkValidationResult.Valid, result)
    }

    @Test
    fun `validate rejects a blank chunk`() {
        val blankChunk = ChunkDraft(page = 1, charOffset = 0, tokenCount = 0, text = "   ")

        val result = validator.validate(listOf(blankChunk))

        assertTrue(result is ChunkValidationResult.Invalid)
        val violations = (result as ChunkValidationResult.Invalid).violations
        assertTrue(violations.any { it.contains("vazio") }) { "violações eram: $violations" }
    }

    @Test
    fun `validate rejects a chunk below the minimum token count`() {
        val tooShort = draft(words(1..100)) // 100 tokens, abaixo de MIN_CHUNK_TOKENS (300)

        val result = validator.validate(listOf(tooShort))

        assertTrue(result is ChunkValidationResult.Invalid)
        val violations = (result as ChunkValidationResult.Invalid).violations
        assertTrue(violations.any { it.contains("100") && it.contains("300") }) { "violações eram: $violations" }
    }

    @Test
    fun `validate rejects a chunk above the maximum token count`() {
        val tooLong = draft(words(1..900)) // 900 tokens, acima de MAX_CHUNK_TOKENS (800)

        val result = validator.validate(listOf(tooLong))

        assertTrue(result is ChunkValidationResult.Invalid)
        val violations = (result as ChunkValidationResult.Invalid).violations
        assertTrue(violations.any { it.contains("900") && it.contains("800") }) { "violações eram: $violations" }
    }

    @Test
    fun `validate rejects a pair of neighboring chunks whose measured overlap is below the minimum ratio`() {
        val previousText = words(1..700)
        // overlap = só os últimos 10 tokens (w691..700) = 10 / 700 ~= 1,4%, abaixo de 10%.
        val nextText = "${words(691..700)}\n\n${words(701..1000)}"

        val result = validator.validate(listOf(draft(previousText), draft(nextText)))

        assertTrue(result is ChunkValidationResult.Invalid)
        val violations = (result as ChunkValidationResult.Invalid).violations
        assertTrue(violations.any { it.contains("overlap") }) { "violações eram: $violations" }
    }

    @Test
    fun `validate rejects a pair of neighboring chunks whose measured overlap is above the maximum ratio`() {
        val previousText = words(1..700)
        // overlap = últimos 200 tokens (w501..700) = 200 / 700 ~= 28,6%, acima de 20%.
        val nextText = "${words(501..700)}\n\n${words(701..1000)}"

        val result = validator.validate(listOf(draft(previousText), draft(nextText)))

        assertTrue(result is ChunkValidationResult.Invalid)
        val violations = (result as ChunkValidationResult.Invalid).violations
        assertTrue(violations.any { it.contains("overlap") }) { "violações eram: $violations" }
    }

    @Test
    fun `validate accepts a single chunk list without attempting to measure overlap`() {
        val onlyChunk = draft(words(1..400))

        val result = validator.validate(listOf(onlyChunk))

        assertEquals(ChunkValidationResult.Valid, result)
    }

    @Test
    fun `validate accepts an empty list of chunks`() {
        assertEquals(ChunkValidationResult.Valid, validator.validate(emptyList()))
    }
}
