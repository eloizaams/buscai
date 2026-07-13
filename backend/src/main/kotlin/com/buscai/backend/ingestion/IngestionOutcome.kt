package com.buscai.backend.ingestion

import java.util.UUID

/**
 * Resultado de uma chamada a [IngestionService.ingest] (ver `plan.md`), com dados suficientes para
 * a CLI (`IngestCommand`, T10) formatar uma mensagem clara ao operador (CA3/CA4/CA6/CA7,
 * `specs/ingestao-pdf/spec.md`).
 *
 * [Skipped] e [ReindexRequired] são a idempotência do ADR-0008 (T8) — declarados aqui para o
 * `sealed class` ficar completo desde já, mas só [Completed]/[Failed] são produzidos pelo caminho
 * feliz de livro novo implementado em T7.
 */
sealed class IngestionOutcome {
    /**
     * A chave de gatilho `(bookId, fileHash, embeddingModel, embeddingModelVersion)` já existe
     * `READY` — nada foi reprocessado (ADR-0008).
     */
    data class Skipped(
        val bookId: String,
        val existingVersionId: UUID,
    ) : IngestionOutcome()

    /**
     * `bookId` já existe mas `fileHash`/`embeddingModelVersion` mudaram e `--reindex` não foi
     * passado — nenhuma chamada ao [EmbeddingClient] foi feita (ADR-0008).
     */
    data class ReindexRequired(
        val bookId: String,
        val existingVersionId: UUID,
    ) : IngestionOutcome()

    /** Ingestão concluída com sucesso: [versionId] está `READY` e é (ou passou a ser) a versão ativa do livro. */
    data class Completed(
        val bookId: String,
        val versionId: UUID,
        val pageCount: Int,
        val chunkCount: Int,
    ) : IngestionOutcome()

    /**
     * Falha em qualquer ponto do pipeline (chunks estruturalmente inválidos, erro ao gerar
     * embeddings, etc.) — [versionId] (quando existe) ficou `FAILED`; a versão anterior do livro,
     * se houver, permanece intacta e servível (CA5/CA7).
     */
    data class Failed(
        val bookId: String,
        val versionId: UUID?,
        val reason: String,
    ) : IngestionOutcome()
}
