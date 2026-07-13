package com.buscai.backend.ingestion

import com.buscai.backend.catalog.BookVersionRepository
import com.buscai.backend.catalog.ChunkRepository
import com.buscai.backend.catalog.EMBEDDING_DIMENSIONS
import com.buscai.backend.ingestion.embedding.EmbeddingClient
import com.buscai.backend.ingestion.pdf.PdfFixtures
import com.buscai.backend.ingestion.pdf.PdfTextExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.nio.file.Path

/** Número de páginas do PDF sintético — dentro da faixa 600-800 pedida por T11 (`tasks.md`), livros grandes são o caso comum (CA2). */
private const val PAGE_COUNT = 700

/**
 * Palavras por página — mesma ordem de grandeza da fixture de [IngestionServiceTest] (200),
 * suficiente para o [Chunker] produzir chunks válidos (300-800 tokens) sem inflar o PDF/tempo de
 * teste à toa (T11 permite otimizar o texto para o mínimo necessário, não precisa ser realista).
 */
private const val WORDS_PER_PAGE = 200

/** Gera "w<start> w<start+1> ... w<end>". */
private fun words(range: IntRange): String = range.joinToString(" ") { "w$it" }

/**
 * Marcador alfabético único por página (a, b, ..., z, aa, ab, ...) — nunca contém dígito, ao
 * contrário de um marcador tipo "page42". Isso importa porque
 * [TextCleaner.removeRepeatedHeaderFooterLines] normaliza sequências de dígitos para "#" antes de
 * comparar candidatos de header/footer entre páginas (`headerFooterSignature`): com 700 páginas de
 * uma única linha cada (marcador + palavras "w<n>"), um marcador numérico colidiria após essa
 * normalização e a linha inteira de cada página seria removida por engano, como já documentado
 * pelo mesmo cuidado em `IngestionServiceTest.PAGE_MARKERS` (lá com uma lista fixa de 9 nomes,
 * aqui gerado para cobrir 700 páginas sem repetir).
 */
private fun letterMarker(page: Int): String {
    var index = page
    val marker = StringBuilder()
    while (index > 0) {
        val remainder = (index - 1) % 26
        marker.insert(0, ('a' + remainder))
        index = (index - remainder - 1) / 26
    }
    return marker.toString()
}

/**
 * Palavras extras só na última página — sem isso, o último grupo de parágrafos do livro (que o
 * [Chunker] deliberadamente permite fechar abaixo de [MIN_CHUNK_TOKENS] só no fim real do texto,
 * ver T5b) fica abaixo do mínimo por pouco (o remanescente natural de agrupar 700 páginas de
 * [WORDS_PER_PAGE] palavras não fecha exatamente no teto a cada grupo) e [ChunkValidator] rejeitaria
 * a ingestão inteira — isso não é uma falha do pipeline, é só a fixture precisando garantir que o
 * fim do livro sintético tenha conteúdo suficiente, do mesmo jeito que um livro real teria.
 */
private const val LAST_PAGE_PADDING_WORDS = 100

/**
 * Texto de cada página do PDF sintético de volume: um marcador único ([letterMarker]) seguido de
 * [WORDS_PER_PAGE] tokens sequenciais — mesmo desenho de `IngestionServiceTest.fixtureBookPageTexts`,
 * escalado para [PAGE_COUNT] páginas ([LAST_PAGE_PADDING_WORDS] a mais só na última, ver acima).
 */
private fun fixtureLargeBookPageTexts(): List<String> =
    (1..PAGE_COUNT).map { page ->
        val start = (page - 1) * WORDS_PER_PAGE + 1
        val end = page * WORDS_PER_PAGE + (if (page == PAGE_COUNT) LAST_PAGE_PADDING_WORDS else 0)
        "${letterMarker(page)} ${words(start..end)}"
    }

/**
 * Grava, para cada chamada a [PdfTextExtractor.extractRange] observada pelo spy instrumentado
 * (ver [IngestionServiceVolumeTest.VolumeTestConfig]), quantas páginas foram pedidas de uma vez —
 * é a instrumentação pedida por T11 (`specs/ingestao-pdf/tasks.md`) para provar, sem depender só do
 * heap restrito, que o pico de páginas extraídas simultaneamente em memória não cresce com o
 * tamanho do livro.
 */
class PageBatchSizeRecorder {
    private val requestedSizes = mutableListOf<Int>()

    @Synchronized
    fun record(size: Int) {
        requestedSizes += size
    }

    val callCount: Int
        @Synchronized get() = requestedSizes.size

    val peak: Int
        @Synchronized get() = requestedSizes.maxOrNull() ?: 0
}

/**
 * Fake determinístico de [EmbeddingClient] para o teste de volume — sem chamada de rede real
 * (mesma convenção de `IngestionServiceTest.FakeEmbeddingClient`, simplificado aqui porque T11 não
 * precisa rastrear transação ativa durante a chamada, isso já é coberto por T7).
 */
class VolumeFakeEmbeddingClient : EmbeddingClient {
    override fun embed(texts: List<String>): List<FloatArray> =
        texts.map { text -> FloatArray(EMBEDDING_DIMENSIONS) { i -> (text.hashCode() % 997) / 997f + i * 1e-6f } }
}

/**
 * T11 (`specs/ingestao-pdf/tasks.md`) — teste de aceite de volume, obrigatório no CI (CA2,
 * `specs/ingestao-pdf/spec.md`): livros de 300+ páginas são o caso comum do acervo, não uma
 * exceção. Ingere um PDF sintético de [PAGE_COUNT] páginas de ponta a ponta e confirma três coisas:
 *
 * (a) a ingestão completa sem `OutOfMemoryError` sob um heap restrito — não configurado aqui, e
 *     sim na task Gradle dedicada `volumeTest` (`build.gradle.kts`), que roda só esta classe numa
 *     JVM de teste isolada (para não reduzir o heap do restante da suíte, que roda na JVM default
 *     de `test`). Ver o comentário de `volumeTest` no `build.gradle.kts` para o valor escolhido e a
 *     justificativa.
 * (b) o pico de páginas extraídas simultaneamente em memória (via [PageBatchSizeRecorder], que
 *     instrumenta um spy de [PdfTextExtractor] real) é exatamente
 *     [PAGE_EXTRACTION_BATCH_SIZE] — não cresce com o tamanho do livro (700 páginas aqui, mas o
 *     mesmo valor de pico se aplicaria a um livro de 70 ou 7000 páginas).
 * (c) o resultado persistido (`pageCount`, contagem de chunks) bate com o esperado.
 */
@Testcontainers
@ActiveProfiles("testcontainers")
@SpringBootTest
class IngestionServiceVolumeTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16"))

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    /**
     * Substitui o `PdfTextExtractor` real por um spy que delega para a implementação real (via
     * `Mockito.spy`/`callRealMethod`) mas registra, em [PageBatchSizeRecorder], quantas páginas
     * cada chamada a [PdfTextExtractor.extractRange] pediu de uma vez — sem alterar nenhum código
     * de produção (mesmo padrão de sobrescrever bean via `@TestConfiguration`/`@Primary` já usado
     * por `IngestionServiceTest.FakeEmbeddingClientConfig`). Também substitui o `EmbeddingClient`
     * real por [VolumeFakeEmbeddingClient], sem chamada de rede.
     */
    @TestConfiguration
    class VolumeTestConfig {
        @Bean
        fun pageBatchSizeRecorder(): PageBatchSizeRecorder = PageBatchSizeRecorder()

        @Bean
        @Primary
        fun instrumentedPdfTextExtractor(recorder: PageBatchSizeRecorder): PdfTextExtractor {
            val spy = Mockito.spy(PdfTextExtractor())
            Mockito
                .doAnswer { invocation ->
                    val startPage = invocation.getArgument<Int>(1)
                    val endPage = invocation.getArgument<Int>(2)
                    recorder.record(endPage - startPage + 1)
                    invocation.callRealMethod()
                }.`when`(spy)
                // `any(File::class.java)` devolve null (API Java do Mockito); `extractRange` tem
                // `file: File` não anulável, e o Kotlin insere uma checagem de nulidade no início do
                // método que dispara antes do interceptor do Mockito registrar a invocação — por
                // isso o `?:` com um File dummy (nunca usado de fato; só evita o NPE na stubagem).
                .extractRange(any(File::class.java) ?: File(""), anyInt(), anyInt())
            return spy
        }

        @Bean
        @Primary
        fun volumeFakeEmbeddingClient(): EmbeddingClient = VolumeFakeEmbeddingClient()
    }

    @Autowired
    lateinit var ingestionService: IngestionService

    @Autowired
    lateinit var bookVersionRepository: BookVersionRepository

    @Autowired
    lateinit var chunkRepository: ChunkRepository

    @Autowired
    lateinit var pageBatchSizeRecorder: PageBatchSizeRecorder

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `ingere PDF sintetico de 700 paginas sem carregar o livro inteiro em memoria, com resultado persistido correto`() {
        val file = PdfFixtures.textPdf(tempDir, fixtureLargeBookPageTexts())

        val outcome = ingestionService.ingest(bookId = "livro-volume-t11", title = "Livro de Volume T11", file = file)

        val completed = outcome as? IngestionOutcome.Completed
        assertNotNull(completed, "esperava IngestionOutcome.Completed, obteve: $outcome")
        checkNotNull(completed)
        assertEquals(PAGE_COUNT, completed.pageCount)
        assertTrue(completed.chunkCount > 0, "esperava pelo menos 1 chunk")

        // (b): pico de páginas extraídas por chamada não cresce com o tamanho do livro.
        assertTrue(
            pageBatchSizeRecorder.callCount > 1,
            "esperava múltiplas chamadas de extração em lote para $PAGE_COUNT páginas, teve ${pageBatchSizeRecorder.callCount}",
        )
        assertEquals(
            PAGE_EXTRACTION_BATCH_SIZE,
            pageBatchSizeRecorder.peak,
            "pico de páginas extraídas numa única chamada deveria ser exatamente o tamanho do lote " +
                "configurado ($PAGE_EXTRACTION_BATCH_SIZE), não o tamanho do livro ($PAGE_COUNT páginas)",
        )

        // (c): resultado persistido bate com o esperado.
        val version = bookVersionRepository.findById(completed.versionId).orElseThrow()
        assertEquals(PAGE_COUNT, version.pageCount)
        assertEquals(completed.chunkCount, version.chunkCount)
        val persistedChunkCount = chunkRepository.findAll().count { it.bookVersionId == completed.versionId }
        assertEquals(completed.chunkCount, persistedChunkCount)
    }
}
