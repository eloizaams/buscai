package com.buscai.backend.ingestion

import com.buscai.backend.book.Book
import com.buscai.backend.book.BookRepository
import com.buscai.backend.book.BookVersion
import com.buscai.backend.book.BookVersionRepository
import com.buscai.backend.book.BookVersionStatus
import com.buscai.backend.book.Chunk
import com.buscai.backend.book.ChunkRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Tamanho do lote de páginas usado para extrair+limpar o texto do PDF (T3/T4) — nunca abre o
 * documento inteiro de uma vez em termos de grafo de objetos do PDFBox (CA2,
 * `specs/ingestao-pdf/spec.md`), mesmo critério de lote já usado por [ScannedPdfDetector]. O texto
 * já limpo de cada lote é acumulado em memória (poucos MB por livro, ver `plan.md`) para o
 * [Chunker] rodar sobre o livro inteiro de uma vez (T5b).
 *
 * `internal` (não `private`) só para o teste de aceite de volume (T11,
 * `IngestionServiceVolumeTest`) poder comparar o pico de páginas extraídas por chamada contra este
 * valor sem duplicá-lo — `internal` continua invisível fora do módulo `backend`, mesma visibilidade
 * já usada por [countTokens]/[tokenize] em `Chunker.kt` para o mesmo propósito.
 */
internal const val PAGE_EXTRACTION_BATCH_SIZE = 20

/** PDF sinalizado por [ScannedPdfDetector] como sem camada de texto extraível (CA3, `spec.md`). */
private class ScannedPdfException(
    message: String,
) : RuntimeException(message)

/**
 * Orquestra o pipeline de ingestão de um PDF (T3→T6, ver `specs/ingestao-pdf/plan.md`): único
 * ponto que grava `Book`/`BookVersion`/`Chunk` (CLAUDE.md — camada de serviço concentra o acesso a
 * repositório, nenhuma outra classe grava essas entidades).
 *
 * T7 implementa o caminho feliz de um livro novo: idempotência (skip/reindex-necessário, ADR-0008)
 * é T8; reindexação com swap atômico de um `Book` já existente é T9. `book_version.book_id` tem
 * uma FK `NOT NULL` para `book.id` (migration V1), então o `Book` é criado (sem versão ativa) já
 * no início do pipeline se ainda não existir; só quando esta versão chega a `READY` é que
 * `Book.activeVersionId` é apontado para ela — e, se já havia uma versão ativa anterior
 * (reindexação, T9), o swap atômico troca o ponteiro para a nova versão e remove a versão anterior
 * e seus chunks na mesma transação curta (ver [completeVersion]).
 *
 * Desenho central (evita esgotar o pool de conexões em livros grandes, ver `plan.md`, seção
 * "Processamento incremental"): nenhuma transação de banco fica aberta durante a chamada de rede
 * ao [EmbeddingClient] — cada lote de chunks é embeddado fora de transação e persistido numa
 * transação curta própria, via [TransactionTemplate] (evita a armadilha de self-invocation de
 * `@Transactional` dentro da mesma classe).
 */
@Service
class IngestionService(
    private val bookRepository: BookRepository,
    private val bookVersionRepository: BookVersionRepository,
    private val chunkRepository: ChunkRepository,
    private val pdfTextExtractor: PdfTextExtractor,
    private val scannedPdfDetector: ScannedPdfDetector,
    private val textCleaner: TextCleaner,
    private val chunker: Chunker,
    private val chunkValidator: ChunkValidator,
    private val embeddingClient: EmbeddingClient,
    private val voyageProperties: VoyageProperties,
    private val ingestionProperties: IngestionProperties,
    transactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    private companion object {
        val logger = LoggerFactory.getLogger(IngestionService::class.java)
    }

    /**
     * Ingere [file] como uma nova versão do livro [bookId]. [title] só é usado para criar o
     * `Book` quando ele ainda não existe (primeira ingestão, ver KDoc da classe).
     *
     * Idempotência e bloqueio de reindexação implícita (ADR-0008, T8): antes de qualquer escrita
     * no banco, calcula [fileHash] e compara com a versão ATIVA do livro (não com qualquer
     * `BookVersion` que bata a chave de gatilho — uma versão órfã/antiga não deveria disparar
     * skip). Se `(fileHash, embeddingModel, embeddingModelVersion)` da versão ativa bate com os
     * valores atuais, devolve [IngestionOutcome.Skipped] sem reprocessar. Se não bate e [reindex]
     * é `false`, devolve [IngestionOutcome.ReindexRequired] sem reprocessar (protege contra
     * reprocessamento acidental caro, já que embeddings são API paga). Se [reindex] é `true`,
     * segue para o pipeline normal (T7) criando uma nova `BookVersion`; só quando ela chega a
     * `READY` é que o swap atômico (T9, ver [completeVersion]) troca `Book.activeVersionId` para a
     * nova versão e remove a versão anterior e seus chunks — se a nova ingestão falhar em qualquer
     * ponto do pipeline, a versão ativa anterior nunca é tocada (CA5/CA7, `spec.md`). Um `Book` sem
     * versão ativa (nenhuma ingestão anterior chegou a `READY`) não é "já ingerido" — segue direto
     * para o pipeline normal.
     */
    fun ingest(
        bookId: String,
        title: String,
        file: File,
        reindex: Boolean = false,
    ): IngestionOutcome {
        val fileHash =
            try {
                sha256Hex(file)
            } catch (ex: Exception) {
                return failReadingPdf(bookId, ex)
            }

        val activeVersionId = bookRepository.findById(bookId).orElse(null)?.activeVersionId
        if (activeVersionId != null) {
            val activeVersion = bookVersionRepository.findById(activeVersionId).orElseThrow()
            val sameTriggerKey =
                activeVersion.fileHash == fileHash &&
                    activeVersion.embeddingModel == voyageProperties.model &&
                    activeVersion.embeddingModelVersion == voyageProperties.modelVersion
            if (sameTriggerKey) {
                return IngestionOutcome.Skipped(bookId, activeVersionId)
            }
            if (!reindex) {
                return IngestionOutcome.ReindexRequired(bookId, activeVersionId)
            }
        }

        val pageCount =
            try {
                pdfTextExtractor.pageCount(file)
            } catch (ex: Exception) {
                // Falha ANTES de existir uma BookVersion (ainda não sabemos nem o número de
                // páginas) — não há versionId para marcar FAILED, daí IngestionOutcome.Failed com
                // versionId nulo (CA7, spec.md). Cobre PDF corrompido/inexistente/protegido por
                // senha/não-PDF, entre outros erros de I/O ou parsing do PDFBox. Mesmo tratamento
                // (ver [failReadingPdf]) usado para a falha de leitura do arquivo em [sha256Hex],
                // logo acima — ambas ocorrem antes de existir uma BookVersion.
                return failReadingPdf(bookId, ex)
            }

        val versionId = startVersion(bookId, title, fileHash, pageCount)

        val drafts =
            try {
                extractCleanAndChunk(file, pageCount)
            } catch (ex: ScannedPdfException) {
                val reason = ex.message ?: "PDF sem camada de texto extraível"
                failVersion(versionId, reason)
                return IngestionOutcome.Failed(bookId, versionId, reason)
            } catch (ex: Exception) {
                // Catch genérico (não `Error`) intencional: não há uma hierarquia de exceção
                // específica do PDFBox fácil de cobrir aqui, e o objetivo é nunca deixar uma
                // BookVersion presa em INGESTING nem vazar stack trace cru ao operador (CA7,
                // spec.md) — qualquer falha de I/O/parsing do PDF a partir daqui cai neste ramo.
                val reason = "erro ao processar o PDF: ${ex.message ?: ex::class.simpleName}"
                failVersion(versionId, reason)
                // Stack trace completo só no log do servidor (ex como último argumento, convenção
                // SLF4J/Logback) — nunca na reason acima, que vai para o operador via
                // IngestionOutcome.Failed. Sem isso, um bug de programação genuíno dentro do try
                // (ex.: no Chunker, chamado por extractCleanAndChunk) não deixaria rastro nenhum
                // para diagnóstico.
                logger.warn("bookVersion={}: falha inesperada ao processar o PDF", versionId, ex)
                return IngestionOutcome.Failed(bookId, versionId, reason)
            }

        when (val validation = chunkValidator.validate(drafts)) {
            is ChunkValidationResult.Invalid -> {
                val reason = validation.violations.joinToString("; ")
                failVersion(versionId, reason)
                return IngestionOutcome.Failed(bookId, versionId, reason)
            }
            ChunkValidationResult.Valid -> Unit
        }

        val chunkCount =
            try {
                embedAndPersistInBatches(versionId, drafts)
            } catch (ex: EmbeddingClientException) {
                val reason = ex.message ?: "erro ao gerar embeddings"
                failVersion(versionId, reason)
                return IngestionOutcome.Failed(bookId, versionId, reason)
            } catch (ex: Exception) {
                // Catch genérico (depois do catch específico de EmbeddingClientException acima,
                // ordem importa) para qualquer falha inesperada durante a persistência do lote
                // (ex.: DataIntegrityViolationException, queda/timeout de conexão do Postgres em
                // chunkRepository.saveAll) — sem isso, a BookVersion ficaria presa em INGESTING
                // para sempre e a exceção crua vazaria ao operador (CA7, spec.md).
                val reason = "erro inesperado ao gerar/persistir chunks: ${ex.message ?: ex::class.simpleName}"
                failVersion(versionId, reason)
                // Stack trace completo só no log do servidor (ex como último argumento, convenção
                // SLF4J/Logback) — nunca na reason acima, que vai para o operador via
                // IngestionOutcome.Failed.
                logger.warn("bookVersion={}: falha inesperada ao gerar/persistir chunks", versionId, ex)
                return IngestionOutcome.Failed(bookId, versionId, reason)
            }

        try {
            completeVersion(bookId, versionId, pageCount, chunkCount)
        } catch (ex: Exception) {
            // completeVersion roda numa TransactionTemplate — se algo falhar dentro dela, o Spring
            // já reverte a transação inteira automaticamente (a versão nova nunca chega a READY, e
            // se havia uma versão ativa anterior, o swap nunca acontece parcialmente: ela continua
            // intacta). Aqui só marcamos a versão NOVA como FAILED (numa transação curta separada,
            // via failVersion) e devolvemos IngestionOutcome.Failed em vez de deixar a exceção crua
            // subir ao operador (CA7, spec.md).
            val reason = "erro inesperado ao finalizar a ingestão: ${ex.message ?: ex::class.simpleName}"
            failVersion(versionId, reason)
            logger.warn("bookVersion={}: falha inesperada ao finalizar a ingestão", versionId, ex)
            return IngestionOutcome.Failed(bookId, versionId, reason)
        }

        return IngestionOutcome.Completed(bookId, versionId, pageCount, chunkCount)
    }

    /**
     * Falha de leitura do arquivo ANTES de existir uma `BookVersion` — não há `versionId` para
     * marcar `FAILED`, daí [IngestionOutcome.Failed] com `versionId` nulo (CA7, `spec.md`). Usado
     * pelos dois pontos de [ingest] que leem o arquivo antes de [startVersion] existir: [sha256Hex]
     * e `PdfTextExtractor.pageCount`.
     */
    private fun failReadingPdf(
        bookId: String,
        ex: Exception,
    ): IngestionOutcome.Failed {
        val reason = "não foi possível ler o PDF: ${ex.message ?: ex::class.simpleName}"
        logger.warn("Ingestão de bookId={} falhou ao ler o PDF: {}", bookId, reason)
        // Stack trace completo só no log do servidor (ex como último argumento, convenção
        // SLF4J/Logback) — nunca na reason acima, que vai para o operador via
        // IngestionOutcome.Failed. Sem isso, um bug de programação genuíno dentro do try (não um
        // PDF corrompido de fato) não deixaria rastro nenhum para diagnóstico.
        logger.warn("bookId={}: falha inesperada ao ler o PDF", bookId, ex)
        return IngestionOutcome.Failed(bookId, versionId = null, reason = reason)
    }

    /**
     * Cria a `BookVersion` (status `INGESTING`) numa transação curta — passo 1 do pipeline (T7).
     * `book_version.book_id` tem uma FK `NOT NULL` para `book.id` (migration V1): se o `Book`
     * ainda não existir, ele é criado aqui, com `activeVersionId` nulo — só [completeVersion] o
     * aponta para uma versão, e só depois que ela chega a `READY` (nunca uma versão incompleta
     * visível para a busca, CA5).
     */
    private fun startVersion(
        bookId: String,
        title: String,
        fileHash: String,
        pageCount: Int,
    ): UUID {
        val version =
            transactionTemplate.execute {
                if (!bookRepository.existsById(bookId)) {
                    bookRepository.save(Book(id = bookId, title = title))
                }
                bookVersionRepository.save(
                    BookVersion(
                        id = UUID.randomUUID(),
                        bookId = bookId,
                        fileHash = fileHash,
                        embeddingModel = voyageProperties.model,
                        embeddingModelVersion = voyageProperties.modelVersion,
                        status = BookVersionStatus.INGESTING,
                        pageCount = pageCount,
                    ),
                )
            }
        return version!!.id
    }

    /**
     * Extrai (em lotes de página, [PAGE_EXTRACTION_BATCH_SIZE]) e limpa o texto do PDF inteiro, e
     * chunka o resultado de uma vez só (T5b) — passo 2 do pipeline (T7). Sem tocar banco.
     *
     * Verifica CA3 (`spec.md`) incrementalmente, no mesmo lote de extração — sem reabrir o PDF
     * numa segunda varredura só para isso: cada página crua é checada por
     * [ScannedPdfDetector.isPageWithoutText] antes de limpar, e [ScannedPdfDetector.isScanned] é
     * avaliado ao final. Um PDF sinalizado como escaneado lança [ScannedPdfException] em vez de
     * silenciosamente produzir uma lista de chunks vazia — que [ChunkValidator] aprovaria de forma
     * vácua (nenhum chunk para violar nada) e deixaria a `BookVersion` `READY` com zero chunks.
     */
    private fun extractCleanAndChunk(
        file: File,
        pageCount: Int,
    ): List<ChunkDraft> {
        if (pageCount == 0) return emptyList()

        val cleanedPages = LinkedHashMap<Int, String>()
        var pagesWithoutText = 0
        var start = 1
        while (start <= pageCount) {
            val end = minOf(start + PAGE_EXTRACTION_BATCH_SIZE - 1, pageCount)
            val rawPages = pdfTextExtractor.extractRange(file, start, end)
            rawPages.values.forEach { pageText ->
                if (scannedPdfDetector.isPageWithoutText(pageText)) pagesWithoutText++
            }
            cleanedPages += textCleaner.clean(rawPages)
            start = end + 1
        }

        if (scannedPdfDetector.isScanned(pagesWithoutText, pageCount)) {
            throw ScannedPdfException(
                "PDF parece não ter camada de texto extraível ($pagesWithoutText de $pageCount " +
                    "páginas sem texto útil) — provável PDF escaneado sem OCR",
            )
        }

        return chunker.chunk(cleanedPages)
    }

    /**
     * Processa [drafts] em lotes de [IngestionProperties.chunkEmbeddingBatchSize] — passo 4 do
     * pipeline (T7): embedding em batch **fora** de transação, persistência do lote numa transação
     * curta própria. Devolve o total de chunks persistidos.
     */
    private fun embedAndPersistInBatches(
        versionId: UUID,
        drafts: List<ChunkDraft>,
    ): Int {
        var persisted = 0
        drafts.chunked(ingestionProperties.chunkEmbeddingBatchSize).forEach { batch ->
            val vectors = embeddingClient.embed(batch.map { it.text })
            transactionTemplate.executeWithoutResult {
                val chunks =
                    batch.mapIndexed { index, draft ->
                        Chunk(
                            id = UUID.randomUUID(),
                            bookVersionId = versionId,
                            page = draft.page,
                            charOffset = draft.charOffset,
                            tokenCount = draft.tokenCount,
                            text = draft.text,
                            embedding = vectors[index],
                            chapter = draft.chapter,
                        )
                    }
                chunkRepository.saveAll(chunks)
            }
            persisted += batch.size
            // Progresso incremental ao operador (CA6, `spec.md`) — IngestCommand (T10) não recebe
            // callback dedicado do IngestionService; este log já aparece no console via Logback.
            logger.info(
                "bookVersion={}: {} de {} chunks processados (embedding + persistência)",
                versionId,
                persisted,
                drafts.size,
            )
        }
        return persisted
    }

    /**
     * Marca a `BookVersion` como `FAILED` numa transação curta (CA7, `spec.md`). [reason] não é
     * persistido — o schema (`V1__book_bookversion_chunk.sql`) não tem uma coluna para isso; a
     * mensagem chega ao operador via o `IngestionOutcome.Failed` devolvido por [ingest], não pela
     * linha do banco.
     */
    private fun failVersion(
        versionId: UUID,
        reason: String,
    ) {
        logger.warn("Ingestão de bookVersion={} falhou: {}", versionId, reason)
        transactionTemplate.executeWithoutResult {
            val version = bookVersionRepository.findById(versionId).orElseThrow()
            version.status = BookVersionStatus.FAILED
            version.completedAt = Instant.now()
            bookVersionRepository.save(version)
        }
    }

    /**
     * Marca a `BookVersion` como `READY` e aponta `Book.activeVersionId` para esta versão — passo
     * 5 do pipeline (T7/T9), numa única transação curta, sem I/O de rede (`plan.md`, seção "Swap
     * atômico"). Se o `Book` já tinha uma versão ativa **diferente** (reindexação, T9), é o swap
     * atômico em si: troca o ponteiro para a nova versão e remove a versão anterior e todos os
     * seus chunks ([ChunkRepository.deleteByBookVersionId]) na mesma transação — nunca há uma
     * janela em que a busca veria um livro parcialmente reindexado, nem em que o livro fica sem
     * versão ativa (CA5, `spec.md`). Se não havia versão ativa anterior (primeira ingestão do
     * livro), nada é removido.
     */
    private fun completeVersion(
        bookId: String,
        versionId: UUID,
        pageCount: Int,
        chunkCount: Int,
    ) {
        transactionTemplate.executeWithoutResult {
            val version = bookVersionRepository.findById(versionId).orElseThrow()
            version.status = BookVersionStatus.READY
            version.pageCount = pageCount
            version.chunkCount = chunkCount
            version.completedAt = Instant.now()
            bookVersionRepository.save(version)

            val book = bookRepository.findById(bookId).orElseThrow()
            val oldActiveVersionId = book.activeVersionId
            book.activeVersionId = versionId
            book.updatedAt = Instant.now()
            bookRepository.save(book)

            if (oldActiveVersionId != null && oldActiveVersionId != versionId) {
                chunkRepository.deleteByBookVersionId(oldActiveVersionId)
                bookVersionRepository.deleteById(oldActiveVersionId)
            }
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read = input.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
