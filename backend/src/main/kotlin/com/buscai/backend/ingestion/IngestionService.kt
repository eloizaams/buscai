package com.buscai.backend.ingestion

import com.buscai.backend.catalog.Book
import com.buscai.backend.catalog.BookRepository
import com.buscai.backend.catalog.BookVersion
import com.buscai.backend.catalog.BookVersionRepository
import com.buscai.backend.catalog.BookVersionStatus
import com.buscai.backend.catalog.Chunk
import com.buscai.backend.catalog.ChunkRepository
import com.buscai.backend.embedding.EmbeddingClient
import com.buscai.backend.embedding.EmbeddingClientException
import com.buscai.backend.embedding.EmbeddingInputType
import com.buscai.backend.embedding.VoyageProperties
import com.buscai.backend.ingestion.chunking.ChunkDraft
import com.buscai.backend.ingestion.chunking.ChunkValidationResult
import com.buscai.backend.ingestion.chunking.ChunkValidator
import com.buscai.backend.ingestion.chunking.Chunker
import com.buscai.backend.ingestion.chunking.ReferenceType
import com.buscai.backend.ingestion.chunking.TextCleaner
import com.buscai.backend.ingestion.pdf.PdfTextExtractor
import com.buscai.backend.ingestion.pdf.ScannedPdfDetector
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
     * skip). [reindex] sempre vence quando `true` (ADR-0008, nota 2026-07-22, CA8): a decisão de
     * skip/bloqueio só é avaliada quando [reindex] é `false`. Nesse caso, se
     * `(fileHash, embeddingModel, embeddingModelVersion)` da versão ativa bate com os valores
     * atuais, devolve [IngestionOutcome.Skipped] sem reprocessar; se não bate, devolve
     * [IngestionOutcome.ReindexRequired] sem reprocessar (protege contra reprocessamento
     * acidental caro, já que embeddings são API paga). Quando [reindex] é `true`, segue direto
     * para o pipeline normal (T7) criando uma nova `BookVersion` mesmo se a chave de gatilho for
     * idêntica à versão ativa (ex.: forçar reprocessamento após mudança no `Chunker`); só quando
     * ela chega a `READY` é que o swap atômico (T9, ver [completeVersion]) troca
     * `Book.activeVersionId` para a nova versão e remove a versão anterior e seus chunks — se a
     * nova ingestão falhar em qualquer ponto do pipeline, a versão ativa anterior nunca é tocada
     * (CA5/CA7, `spec.md`). Um `Book` sem
     * versão ativa (nenhuma ingestão anterior chegou a `READY`) não é "já ingerido" — segue direto
     * para o pipeline normal.
     *
     * Guard único (evita o padrão anterior de um try/catch pontual por passo, que a cada rodada
     * de code review deixava mais um passo do pipeline escapar sem tratamento): todo o corpo do
     * pipeline roda dentro de UM ÚNICO `try`, com [versionId] declarado fora dele e atualizado
     * assim que [startVersion] retorna — é o que [fail] usa, no `catch`, para decidir entre marcar
     * uma `BookVersion` existente como `FAILED` (versão já criada) ou apenas devolver
     * [IngestionOutcome.Failed] com `versionId` nulo (falha antes de [startVersion], ex.: PDF
     * corrompido em [sha256Hex]/`pageCount`). Os catches específicos ([ScannedPdfException],
     * [EmbeddingClientException]) preservam a mensagem já produzida por quem lançou a exceção; o
     * catch genérico final cobre qualquer outra falha inesperada em qualquer passo (I/O do PDF,
     * `Chunker`, persistência de chunks, `completeVersion`), sempre marcando a versão como
     * `FAILED` (quando ela existe) em vez de deixá-la presa em `INGESTING` (CA7, `spec.md`).
     */
    fun ingest(
        bookId: String,
        title: String,
        file: File,
        reindex: Boolean = false,
        referenceType: ReferenceType? = null,
        contentPages: IntRange? = null,
    ): IngestionOutcome {
        var versionId: UUID? = null
        return try {
            val fileHash = sha256Hex(file)

            val activeVersionId = bookRepository.findById(bookId).orElse(null)?.activeVersionId
            if (activeVersionId != null) {
                val activeVersion = bookVersionRepository.findById(activeVersionId).orElseThrow()
                val sameTriggerKey =
                    activeVersion.fileHash == fileHash &&
                        activeVersion.embeddingModel == voyageProperties.model &&
                        activeVersion.embeddingModelVersion == voyageProperties.modelVersion
                // `reindex` sempre vence: só decide skip/bloqueio quando a flag NÃO foi pedida
                // (CA8, ADR-0008, nota 2026-07-22) — do contrário, mesmo com a chave de gatilho
                // idêntica (mesmo arquivo, mesmo modelo), um operador que passou --reindex quer
                // forçar reprocessamento (ex.: mudança no Chunker/ReferenceAnnotator).
                if (!reindex) {
                    if (sameTriggerKey) {
                        return IngestionOutcome.Skipped(bookId, activeVersionId)
                    }
                    return IngestionOutcome.ReindexRequired(bookId, activeVersionId)
                }
            }

            // Falha ANTES deste ponto (sha256Hex ou pageCount) não tem versionId — cai no catch
            // genérico abaixo, que devolve IngestionOutcome.Failed com versionId nulo (CA7).
            val pageCount = pdfTextExtractor.pageCount(file)

            // Validação de --content-pages contra o documento real (T2,
            // specs/conteudo-paginas-overlap/plan.md): o parser (IngestArgsParser) só valida
            // formato, nunca abre o PDF — é aqui, com pageCount em mãos, que um intervalo além do
            // fim do documento é barrado. Feito ANTES de startVersion (sem BookVersion órfã) e
            // ANTES de extractCleanAndChunk, para não deixar o `require` de
            // PdfTextExtractor.extractRange estourar uma exceção crua no meio do pipeline.
            if (contentPages != null && contentPages.last > pageCount) {
                return fail(
                    bookId,
                    null,
                    "Intervalo --content-pages (${contentPages.first}-${contentPages.last}) excede o " +
                        "total de páginas do documento ($pageCount).",
                )
            }

            versionId = startVersion(bookId, title, fileHash, pageCount, referenceType)

            val drafts = extractCleanAndChunk(file, pageCount, referenceType, contentPages)

            // Guard contra BookVersion READY com zero chunks (lacuna preexistente, já mencionada
            // no KDoc de extractCleanAndChunk para PDF escaneado — mas --content-pages, T2, torna
            // esse caminho mais fácil de acionar: um intervalo apontando só para páginas de
            // rosto/contracapa tem texto real o bastante para não disparar ScannedPdfDetector, mas
            // insuficiente para o Chunker produzir qualquer chunk). Sem este guard,
            // chunkValidator.validate aprovaria drafts vazio de forma vácua e completeVersion
            // marcaria a versão como READY com chunkCount=0, sem nenhum erro para o operador.
            if (drafts.isEmpty()) {
                return fail(
                    bookId,
                    versionId,
                    "Nenhum trecho de conteúdo foi gerado a partir do intervalo de páginas " +
                        "processado — revise --content-pages (ou o PDF, se sem --content-pages) e " +
                        "tente novamente.",
                )
            }

            when (val validation = chunkValidator.validate(drafts, referenceType)) {
                is ChunkValidationResult.Invalid ->
                    return fail(bookId, versionId, validation.violations.joinToString("; "))
                ChunkValidationResult.Valid -> Unit
            }

            val chunkCount = embedAndPersistInBatches(versionId, drafts, referenceType)

            completeVersion(bookId, versionId, pageCount, chunkCount)

            IngestionOutcome.Completed(bookId, versionId, pageCount, chunkCount)
        } catch (ex: ScannedPdfException) {
            fail(bookId, versionId, ex.message ?: "PDF sem camada de texto extraível")
        } catch (ex: EmbeddingClientException) {
            fail(bookId, versionId, ex.message ?: "erro ao gerar embeddings")
        } catch (ex: Exception) {
            // Catch genérico (não `Error`) intencional: nenhum passo do pipeline (leitura do
            // arquivo, extração/limpeza/chunking do PDF, persistência de chunks, swap atômico em
            // completeVersion) pode deixar uma exceção crua escapar nem uma BookVersion presa em
            // INGESTING (CA7, spec.md). Mensagem varia só para deixar claro ao operador se a falha
            // foi antes (sem BookVersion) ou depois (BookVersion existente marcada FAILED) de
            // startVersion — sem reason específica por passo, que hoje já é coberta pelos catches
            // acima ou por ChunkValidationResult.Invalid.
            val reason =
                if (versionId == null) {
                    "não foi possível ler o PDF: ${ex.message ?: ex::class.simpleName}"
                } else {
                    "erro inesperado ao processar a ingestão: ${ex.message ?: ex::class.simpleName}"
                }
            fail(bookId, versionId, reason, ex)
        }
    }

    /**
     * Helper único para o padrão "logar + marcar BookVersion como FAILED (se já existir) + montar
     * IngestionOutcome.Failed" — usado por todo `catch` de [ingest] (reduz a duplicação antes
     * espalhada em cada ramo de falha do pipeline). [ex], quando presente, vai como último
     * argumento do log (convenção SLF4J/Logback) para stack trace completo no log do servidor;
     * [reason] é sempre curta e sem stack trace, é o que chega ao operador via
     * [IngestionOutcome.Failed]. [ex] é omitido para falhas "esperadas"/de domínio já com mensagem
     * clara própria ([ScannedPdfException], [EmbeddingClientException]) — só o catch genérico
     * (bugs/erros de infraestrutura inesperados) precisa do stack trace para diagnóstico.
     */
    private fun fail(
        bookId: String,
        versionId: UUID?,
        reason: String,
        ex: Exception? = null,
    ): IngestionOutcome.Failed {
        if (ex != null) {
            logger.warn("Ingestão de bookId={} bookVersion={} falhou: {}", bookId, versionId, reason, ex)
        } else {
            logger.warn("Ingestão de bookId={} bookVersion={} falhou: {}", bookId, versionId, reason)
        }
        if (versionId != null) {
            failVersion(versionId, reason)
        }
        return IngestionOutcome.Failed(bookId, versionId, reason)
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
        referenceType: ReferenceType?,
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
                        referenceType = referenceType,
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
     *
     * [contentPages] (T2, `specs/conteudo-paginas-overlap/plan.md`), quando fornecido, restringe o
     * próprio laço de lotes ao intervalo — em vez de extrair o PDF inteiro e descartar páginas
     * depois — para não gastar tempo extraindo/limpando front/back matter (índice remissivo,
     * capa) que nunca viraria chunk. Consequência deliberada: a checagem de PDF escaneado acima
     * (limiar de 90% de páginas sem texto, ADR-0008) passa a ser medida só sobre as páginas do
     * intervalo quando `--content-pages` é usado — mais fiel (capas/imagens de front matter
     * deixam de contar), não é um bug. [pageCount] permanece o já validado contra
     * [contentPages] em [ingest] (`contentPages.last <= pageCount`); o `minOf` abaixo é só
     * defesa redundante.
     */
    private fun extractCleanAndChunk(
        file: File,
        pageCount: Int,
        referenceType: ReferenceType?,
        contentPages: IntRange? = null,
    ): List<ChunkDraft> {
        if (pageCount == 0) return emptyList()

        val lastPage = contentPages?.let { minOf(pageCount, it.last) } ?: pageCount
        var start = contentPages?.first ?: 1
        if (contentPages != null) {
            logger.info("Restringindo ingestão às páginas {}-{} de {} totais.", start, lastPage, pageCount)
        }

        val cleanedPages = LinkedHashMap<Int, String>()
        var pagesWithoutText = 0
        var pagesConsidered = 0
        while (start <= lastPage) {
            val end = minOf(start + PAGE_EXTRACTION_BATCH_SIZE - 1, lastPage)
            val rawPages = pdfTextExtractor.extractRange(file, start, end)
            rawPages.values.forEach { pageText ->
                if (scannedPdfDetector.isPageWithoutText(pageText)) pagesWithoutText++
            }
            pagesConsidered += rawPages.size
            cleanedPages += textCleaner.clean(rawPages)
            start = end + 1
        }

        if (scannedPdfDetector.isScanned(pagesWithoutText, pagesConsidered)) {
            throw ScannedPdfException(
                "PDF parece não ter camada de texto extraível ($pagesWithoutText de $pagesConsidered " +
                    "páginas sem texto útil) — provável PDF escaneado sem OCR",
            )
        }

        return chunker.chunk(cleanedPages, referenceType)
    }

    /**
     * Processa [drafts] em lotes de [IngestionProperties.chunkEmbeddingBatchSize] — passo 4 do
     * pipeline (T7): embedding em batch **fora** de transação, persistência do lote numa transação
     * curta própria. Devolve o total de chunks persistidos.
     */
    private fun embedAndPersistInBatches(
        versionId: UUID,
        drafts: List<ChunkDraft>,
        referenceType: ReferenceType?,
    ): Int {
        var persisted = 0
        drafts.chunked(ingestionProperties.chunkEmbeddingBatchSize).forEach { batch ->
            val vectors = embeddingClient.embed(batch.map { it.text }, EmbeddingInputType.DOCUMENT)
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
                            reference = draft.reference,
                            // Nunca deriva referenceType só do parâmetro do livro inteiro: um chunk
                            // sem reference (preâmbulo antes do primeiro capítulo/item, ver
                            // ReferenceAnnotator/Chunker) precisa persistir referenceType nulo junto
                            // — senão GenerationService.referenceLabel (que decide o rótulo só por
                            // referenceType, sem checar reference) monta "item: null"/"capítulo: null"
                            // no prompt, e o mesmo par quebrado vaza para o cliente via SourceItem.
                            referenceType = if (draft.reference != null) referenceType else null,
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
     * linha do banco. O log da falha é responsabilidade de [fail] (único chamador) — aqui só a
     * atualização do banco.
     */
    private fun failVersion(
        versionId: UUID,
        reason: String,
    ) {
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
