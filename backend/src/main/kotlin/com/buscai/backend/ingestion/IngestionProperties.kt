package com.buscai.backend.ingestion

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Binding de `buscai.ingestion.*` (`application.yml`). [chunkEmbeddingBatchSize] é o tamanho do
 * lote de chunks processado por vez pelo [IngestionService] — embedding em batch (fora de
 * transação) + persistência (transação curta), ver `specs/ingestao-pdf/plan.md`, seção
 * "Processamento incremental". Default espelha `application.yml`; testes sobrescrevem para um
 * valor pequeno para forçar múltiplos lotes mesmo com poucos chunks.
 */
@ConfigurationProperties(prefix = "buscai.ingestion")
data class IngestionProperties(
    val chunkEmbeddingBatchSize: Int = 40,
)
