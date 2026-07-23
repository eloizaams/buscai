package com.buscai.backend.retrieval.search

import com.buscai.backend.catalog.Book
import com.buscai.backend.catalog.BookRepository
import com.buscai.backend.catalog.BookVersion
import com.buscai.backend.catalog.BookVersionRepository
import com.buscai.backend.catalog.BookVersionStatus
import com.buscai.backend.catalog.EMBEDDING_DIMENSIONS
import com.buscai.backend.embedding.EmbeddingClient
import com.buscai.backend.embedding.EmbeddingInputType
import com.buscai.backend.retrieval.RetrievalResult
import com.buscai.backend.retrieval.RetrievalScope
import com.buscai.backend.retrieval.RetrievalService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import kotlin.random.Random
import kotlin.system.measureNanoTime
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

/** ~50 mil chunks é a escala-alvo do CA8 (`specs/retrieval/spec.md`), não um caso extremo. */
private const val CHUNK_COUNT = 50_000

/** Chunk cujo embedding é usado como vetor da query — cosine_similarity = 1.0 garantido para ele. */
private const val TARGET_INDEX = CHUNK_COUNT / 2

/** Termo léxico raro (presente em só ~0.2% dos chunks, ver [textFor]) usado como texto da query. */
private const val NEEDLE_TERM = "codinome"

/** Vocabulário fixo para gerar texto plausível para `to_tsvector('portuguese', ...)`. */
private val VOCABULARY =
    listOf(
        "livro",
        "capitulo",
        "personagem",
        "protagonista",
        "viagem",
        "cidade",
        "historia",
        "tempo",
        "vida",
        "amor",
        "misterio",
        "segredo",
        "jornada",
        "reino",
        "floresta",
        "montanha",
        "rio",
        "estrela",
        "sonho",
        "memoria",
        "familia",
        "guerra",
        "paz",
        "amizade",
        "coragem",
    )

/** Defaults de `RetrievalProperties`/`plan.md` ("Config nova") usados para medir `HybridSearchDao.search` isoladamente. */
private const val VECTOR_CANDIDATES = 50
private const val LEXICAL_CANDIDATES = 50
private const val RRF_K = 60

/**
 * Meta de CA8 (`specs/retrieval/spec.md`): latência de busca abaixo de 100 ms em estado estável
 * com ~50 mil chunks. Ver comentário no corpo do teste sobre o resultado medido neste ambiente e
 * o eventual débito técnico registrado, caso a meta não bata (não é para ser relaxada
 * silenciosamente — CA8 já qualifica a meta como "a validar").
 */
private const val LATENCY_BUDGET_MS = 100.0

/**
 * Repetições medidas (após o warm-up) para reduzir o risco de falso-negativo do gate de CI a
 * partir de uma amostra única vulnerável a uma pausa isolada de GC/JIT (revisão da T8) — usa-se a
 * mediana, não a média, para não deixar um outlier puxar o resultado.
 */
private const val MEASURED_REPETITIONS = 5

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
}

/** Embedding determinístico (seed fixa por índice) — sem chamada à Voyage real, conforme T8. */
private fun embeddingFor(index: Int): FloatArray {
    val random = Random(index.toLong() * 31L + 17L)
    return FloatArray(EMBEDDING_DIMENSIONS) { random.nextFloat() * 2f - 1f }
}

/** Texto determinístico (seed fixa por índice); todo múltiplo de 500 ganha o termo raro [NEEDLE_TERM]. */
private fun textFor(index: Int): String {
    val random = Random(index.toLong() * 97L + 3L)
    val words = (1..12).map { VOCABULARY[random.nextInt(VOCABULARY.size)] }
    val needle = if (index % 500 == 0) " $NEEDLE_TERM" else ""
    return words.joinToString(" ") + needle
}

/** Mesmo formato de literal `vector` do pgvector usado por `HybridSearchDao` (função privada lá,
 * não reaproveitável daqui) — aqui só para o insert em lote via JDBC cru, sem passar pelo JPA. */
private fun FloatArray.toPgVectorLiteral(): String = joinToString(prefix = "[", postfix = "]") { it.toString() }

/**
 * T8 (`specs/retrieval/tasks.md`) — teste de aceite de latência (CA8, obrigatório, não opcional):
 * mede `HybridSearchDao.search` (e, fim a fim, `RetrievalService.search`) sobre um acervo sintético
 * de [CHUNK_COUNT] chunks, persistido via Testcontainers/Postgres real com pgvector (mesmo padrão
 * de volume de `IngestionServiceVolumeTest`, T11 de `specs/ingestao-pdf/tasks.md`).
 *
 * Insert em lote via JDBC cru (não `ChunkRepository.saveAll`, que faria 50 mil round-trips
 * individuais sob JPA) — só o suficiente para popular a fixture rapidamente, a lógica de
 * persistência em produção continua toda em `IngestionService`/`ChunkRepository`.
 *
 * Execução "quente": a primeira chamada de cada método medido é descartada (paga custo de
 * warm-up/plano de query do Postgres) antes de medir de fato — CA8 qualifica a meta como "estado
 * estável", não a latência de uma query fria.
 *
 * Isolado da task `test` (ver `build.gradle.kts`, mesmo padrão de `volumeTest`/T11): roda numa task
 * Gradle própria (`retrievalVolumeTest`) da qual `check`/`build` depende, para não pesar o loop de
 * desenvolvimento a cada mudança pequena sem deixar de rodar no CI.
 */
@Testcontainers
@ActiveProfiles("testcontainers")
@SpringBootTest
class HybridSearchDaoVolumeTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16"))

        @DynamicPropertySource
        @JvmStatic
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }

        private const val INSERT_SQL =
            """
            INSERT INTO chunk (id, book_version_id, page, char_offset, token_count, text, embedding)
            VALUES (:id, :bookVersionId, :page, :charOffset, :tokenCount, :text, CAST(:embedding AS vector))
            """

        private const val BATCH_SIZE = 1000
    }

    /**
     * Substitui o `EmbeddingClient` real por um fake que sempre devolve o embedding do chunk-alvo
     * ([TARGET_INDEX]) — mesma convenção de `RetrievalServiceIntegrationTest.FakeQueryEmbeddingClient`,
     * sem chamada de rede.
     */
    @TestConfiguration
    class FixedVectorEmbeddingClientConfig {
        @Bean
        @Primary
        fun fixedVectorEmbeddingClient(): EmbeddingClient =
            object : EmbeddingClient {
                override fun embed(
                    texts: List<String>,
                    inputType: EmbeddingInputType,
                ): List<FloatArray> = texts.map { embeddingFor(TARGET_INDEX) }
            }
    }

    @Autowired
    lateinit var bookRepository: BookRepository

    @Autowired
    lateinit var bookVersionRepository: BookVersionRepository

    @Autowired
    lateinit var jdbcTemplate: NamedParameterJdbcTemplate

    @Autowired
    lateinit var hybridSearchDao: HybridSearchDao

    @Autowired
    lateinit var retrievalService: RetrievalService

    /**
     * Persiste 1 livro/versão `READY` e [CHUNK_COUNT] chunks em lote via JDBC cru. Feito dentro do
     * próprio `@Test` (não um `@BeforeAll`/`@TestInstance(PER_CLASS)`): essa combinação com
     * containers estáticos do Testcontainers conflita com a criação do `ApplicationContext` do
     * Spring (`IllegalStateException: Mapped port can only be obtained after the container is
     * started`, reproduzido ao tentar `@TestInstance(Lifecycle.PER_CLASS)` aqui) — mesmo padrão de
     * um único `@Test` por classe de volume já usado em `IngestionServiceVolumeTest` (T11).
     */
    private fun populateFixture(): UUID {
        val bookId = "livro-volume-t8-${UUID.randomUUID()}"
        bookRepository.save(Book(id = bookId, title = "Livro de Volume T8"))
        val version =
            bookVersionRepository.save(
                BookVersion(
                    id = UUID.randomUUID(),
                    bookId = bookId,
                    fileHash =
                        UUID
                            .randomUUID()
                            .toString()
                            .replace("-", "")
                            .repeat(2)
                            .take(64),
                    embeddingModel = "voyage-3",
                    embeddingModelVersion = "v1",
                    status = BookVersionStatus.READY,
                ),
            )
        val versionId = version.id
        bookRepository.findById(bookId).ifPresent { book ->
            book.activeVersionId = versionId
            bookRepository.save(book)
        }

        val insertElapsedNanos =
            measureNanoTime {
                var buffer = ArrayList<SqlParameterSource>(BATCH_SIZE)
                for (i in 0 until CHUNK_COUNT) {
                    buffer.add(
                        MapSqlParameterSource()
                            .addValue("id", UUID.randomUUID())
                            .addValue("bookVersionId", versionId)
                            .addValue("page", i + 1)
                            .addValue("charOffset", 0)
                            .addValue("tokenCount", 50)
                            .addValue("text", textFor(i))
                            .addValue("embedding", embeddingFor(i).toPgVectorLiteral()),
                    )
                    if (buffer.size == BATCH_SIZE) {
                        jdbcTemplate.batchUpdate(INSERT_SQL, buffer.toTypedArray())
                        buffer = ArrayList(BATCH_SIZE)
                    }
                }
                if (buffer.isNotEmpty()) {
                    jdbcTemplate.batchUpdate(INSERT_SQL, buffer.toTypedArray())
                }
            }
        println(
            "[T8/CA8] setup: $CHUNK_COUNT chunks inseridos em lote em " +
                "${"%.1f".format(insertElapsedNanos / 1_000_000.0)} ms",
        )

        // ANALYZE explícito (revisão da T8): sem estatísticas atualizadas após um insert em lote de
        // 50 mil linhas, o planner do Postgres pode escolher entre index scan (HNSW/GIN) e seq scan
        // de forma inconsistente, dependendo de quando o autovacuum rodaria por conta própria — o
        // que tornaria a medição de latência sensível a timing de fundo em vez de refletir o "estado
        // estável" que CA8 pede.
        jdbcTemplate.jdbcOperations.execute("ANALYZE chunk")

        return versionId
    }

    /**
     * Confirma que o ramo léxico da fusão RRF (`text_search @@ plainto_tsquery(...)`) tem
     * candidatos reais para [term] — sem isso, o chunk-alvo (que já bate `cosine_similarity = 1.0`
     * sozinho no ramo vetorial) faria o teste passar mesmo que `lexical_rank` voltasse vazio
     * (regressão silenciosa no FTS, revisão da T8), e a latência medida ficaria otimista por não
     * pagar o custo real do ramo léxico populado.
     */
    private fun countLexicalMatches(
        versionId: UUID,
        term: String,
    ): Long =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM chunk
            WHERE book_version_id = :versionId AND text_search @@ plainto_tsquery('portuguese', :term)
            """,
            MapSqlParameterSource().addValue("versionId", versionId).addValue("term", term),
            Long::class.java,
        ) ?: 0L

    @Test
    fun `HybridSearchDao search e RetrievalService search sobre ~50 mil chunks em execucao quente (CA8)`() {
        val versionId = populateFixture()
        val queryVector = embeddingFor(TARGET_INDEX)

        // Cobertura do ramo léxico (revisão da T8): confirma que existem candidatos reais para
        // NEEDLE_TERM via text_search antes de medir — sem isso, o teste passaria igual mesmo que
        // o ramo léxico da fusão RRF tivesse regredido para vazio (o chunk-alvo já bate
        // cosine_similarity = 1.0 sozinho no ramo vetorial).
        val lexicalMatches = countLexicalMatches(versionId, NEEDLE_TERM)
        assertTrue(
            lexicalMatches > 0,
            "esperava candidatos léxicos reais para '$NEEDLE_TERM' (text_search @@ plainto_tsquery), " +
                "encontrou $lexicalMatches — sem isso a medição de latência não exercita o ramo léxico de fato",
        )
        println("[T8/CA8] ramo léxico: $lexicalMatches chunks casam com '$NEEDLE_TERM' via text_search")

        // Execução fria (warm-up): paga o custo de plano de query/cache do Postgres, descartada da medição.
        hybridSearchDao.search(
            queryVector = queryVector,
            queryText = NEEDLE_TERM,
            eligibleBookVersionIds = listOf(versionId),
            vectorCandidates = VECTOR_CANDIDATES,
            lexicalCandidates = LEXICAL_CANDIDATES,
            rrfK = RRF_K,
            // Ramo exato (busca-exata-item/T4) fora do escopo deste teste de latência — lista
            // vazia preserva o comportamento medido pré-T4 (HybridSearchDao.search, KDoc).
            exactItemNumbers = emptyList(),
            exactMatchLimit = VECTOR_CANDIDATES,
        )

        // Mediana de MEASURED_REPETITIONS amostras (revisão da T8) — reduz o risco de uma única
        // pausa isolada de GC/JIT decidir o resultado do gate de CI.
        val daoSamplesMs =
            (1..MEASURED_REPETITIONS).map {
                val elapsedNanos =
                    measureNanoTime {
                        hybridSearchDao.search(
                            queryVector = queryVector,
                            queryText = NEEDLE_TERM,
                            eligibleBookVersionIds = listOf(versionId),
                            vectorCandidates = VECTOR_CANDIDATES,
                            lexicalCandidates = LEXICAL_CANDIDATES,
                            rrfK = RRF_K,
                            exactItemNumbers = emptyList(),
                            exactMatchLimit = VECTOR_CANDIDATES,
                        )
                    }
                elapsedNanos / 1_000_000.0
            }
        val daoMedianMs = median(daoSamplesMs)

        // Resultado medido + parâmetros do ambiente (CA8 é uma meta "a validar", `plan.md`) — ver
        // KDoc da classe e o comentário logo abaixo sobre o limiar de asserção usado.
        println(
            "[T8/CA8] HybridSearchDao.search sobre $CHUNK_COUNT chunks (dimensão $EMBEDDING_DIMENSIONS, " +
                "Postgres ${postgres.dockerImageName}, vectorCandidates=$VECTOR_CANDIDATES, " +
                "lexicalCandidates=$LEXICAL_CANDIDATES, rrfK=$RRF_K): mediana de " +
                "${"%.2f".format(daoMedianMs)} ms sobre $MEASURED_REPETITIONS amostras quentes " +
                "(amostras: ${daoSamplesMs.map { "%.2f".format(it) }}, meta CA8 < $LATENCY_BUDGET_MS ms)",
        )

        assertTrue(
            daoMedianMs < LATENCY_BUDGET_MS,
            "HybridSearchDao.search levou (mediana) ${"%.2f".format(daoMedianMs)} ms sobre $CHUNK_COUNT " +
                "chunks (amostras: $daoSamplesMs), acima da meta de CA8 ($LATENCY_BUDGET_MS ms). Se isso se " +
                "repetir de forma consistente neste ambiente (Testcontainers/CI), registrar como débito " +
                "técnico explícito em specs/retrieval/tasks.md (T8) em vez de simplesmente afrouxar este limiar.",
        )

        // Fim a fim, "se possível" (`specs/retrieval/tasks.md`, T8) — inclui embed da query
        // (fake, sem rede) + resolução de escopo + ContextAssembler, não só a query nativa. Não é
        // o alvo direto de CA8 (que é sobre HybridSearchDao.search), só um número de referência
        // adicional para acompanhar o orçamento de latência da futura Fase 5 (geração).
        val scope = RetrievalScope.Books(setOf(bookId(versionId)))
        retrievalService.search(NEEDLE_TERM, scope) // warm-up, mesma justificativa acima.

        // measureTimedValue captura o retorno da própria chamada medida — evita uma 3ª chamada só
        // para obter o `result` (revisão da T8).
        val (result, serviceDuration) = measureTimedValue { retrievalService.search(NEEDLE_TERM, scope) }
        val serviceElapsedMs = serviceDuration.inWholeMicroseconds / 1000.0
        check(result is RetrievalResult.Found) { "esperava RetrievalResult.Found, obteve: $result" }

        println(
            "[T8/CA8] RetrievalService.search (fim a fim, inclui embed da query + ContextAssembler) " +
                "sobre $CHUNK_COUNT chunks: ${"%.2f".format(serviceElapsedMs)} ms (execução quente; " +
                "número informativo, meta CA8 é sobre HybridSearchDao.search)",
        )
    }

    /** `versionId` -> `bookId` (1:1 nesta fixture, um único livro/versão) via `BookVersionRepository`. */
    private fun bookId(versionId: UUID): String = bookVersionRepository.findById(versionId).orElseThrow().bookId
}
