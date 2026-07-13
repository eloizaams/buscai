package com.buscai.backend.ingestion

import org.springframework.stereotype.Component

/**
 * Resultado da verificação estrutural de uma lista de chunks vizinhos (ADR-0008, seção
 * "Verificação estrutural de chunk" — proxy para CA8 de `specs/ingestao-pdf/spec.md` até existir
 * um golden set avaliado pelo `rag-evaluator`). [Invalid.violations] traz uma mensagem por
 * violação encontrada, cada uma identificando o índice do chunk problemático dentro da lista
 * recebida, para o operador saber que trecho do livro investigar (CA7).
 */
sealed class ChunkValidationResult {
    data object Valid : ChunkValidationResult()

    data class Invalid(
        val violations: List<String>,
    ) : ChunkValidationResult()
}

/**
 * Valida chunks (tipicamente produzidos por [Chunker]) contra os limites estruturais do ADR-0008:
 * nenhum chunk vazio, contagem de tokens de cada um entre [MIN_CHUNK_TOKENS] e [MAX_CHUNK_TOKENS],
 * e overlap real com o chunk vizinho seguinte entre [OVERLAP_MIN_RATIO] e [OVERLAP_MAX_RATIO].
 * Nunca lança exceção — devolve [ChunkValidationResult.Invalid] com uma mensagem por violação, para
 * a ingestão (T7) decidir falhar com uma mensagem clara (CA7) em vez de indexar silenciosamente um
 * chunk fora do padrão.
 *
 * O overlap **não** é lido de nenhum campo interno do [Chunker] — é medido diretamente comparando
 * o texto de dois chunks vizinhos (o maior sufixo de tokens do chunk anterior que também é um
 * prefixo de tokens do chunk seguinte), para que a validação sirva como uma checagem independente
 * de como o overlap foi de fato construído.
 */
@Component
class ChunkValidator {
    fun validate(chunks: List<ChunkDraft>): ChunkValidationResult {
        val violations = mutableListOf<String>()

        chunks.forEachIndexed { index, chunk ->
            violations += validateChunk(index, chunk)
        }

        for (index in 0 until chunks.size - 1) {
            val current = chunks[index]
            val next = chunks[index + 1]
            validateOverlap(index, current, next)?.let { violations += it }
        }

        return if (violations.isEmpty()) ChunkValidationResult.Valid else ChunkValidationResult.Invalid(violations)
    }

    private fun validateChunk(
        index: Int,
        chunk: ChunkDraft,
    ): List<String> {
        if (chunk.text.isBlank()) return listOf("chunk $index está vazio")
        if (chunk.tokenCount !in MIN_CHUNK_TOKENS..MAX_CHUNK_TOKENS) {
            return listOf(
                "chunk $index tem ${chunk.tokenCount} tokens, fora da faixa " +
                    "$MIN_CHUNK_TOKENS-$MAX_CHUNK_TOKENS",
            )
        }
        return emptyList()
    }

    private fun validateOverlap(
        index: Int,
        current: ChunkDraft,
        next: ChunkDraft,
    ): String? {
        // Chunks vazios/sem tokens já foram reportados por validateChunk; medir overlap contra
        // eles não agrega informação e uma divisão por tokenCount=0 seria indefinida.
        if (current.tokenCount <= 0 || next.tokenCount <= 0) return null

        val ratio = measureOverlapRatio(current, next)
        if (ratio < OVERLAP_MIN_RATIO || ratio > OVERLAP_MAX_RATIO) {
            val minPercent = (OVERLAP_MIN_RATIO * 100).toInt()
            val maxPercent = (OVERLAP_MAX_RATIO * 100).toInt()
            val actualPercent = "%.1f".format(ratio * 100)
            return "overlap entre chunk $index e chunk ${index + 1} é $actualPercent%, " +
                "fora da faixa $minPercent-$maxPercent%"
        }
        return null
    }

    /**
     * Overlap real entre dois chunks vizinhos: maior N tal que os últimos N tokens de [previous]
     * são idênticos, em ordem, aos primeiros N tokens de [next]. Ratio = N / tokenCount(previous)
     * — fração do chunk anterior que se repete no início do próximo.
     */
    private fun measureOverlapRatio(
        previous: ChunkDraft,
        next: ChunkDraft,
    ): Double {
        val previousTokens = tokenize(previous.text)
        val nextTokens = tokenize(next.text)
        val maxPossibleOverlap = minOf(previousTokens.size, nextTokens.size)

        var overlapTokenCount = 0
        for (candidate in maxPossibleOverlap downTo 1) {
            if (previousTokens.takeLast(candidate) == nextTokens.take(candidate)) {
                overlapTokenCount = candidate
                break
            }
        }
        return overlapTokenCount.toDouble() / previous.tokenCount
    }
}
