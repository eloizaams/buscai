package com.buscai.backend.retrieval

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Binding de `buscai.retrieval.*` (`application.yml`, `specs/retrieval/plan.md`, seção "Config
 * nova"). Registrado via `@ConfigurationPropertiesScan` em `BackendApplication` (mesmo mecanismo de
 * [com.buscai.backend.embedding.VoyageProperties]).
 *
 * Defaults nos parâmetros espelham os defaults de `application.yml` — necessários porque
 * `src/test/resources/application.yml` substitui (não mescla) o `application.yml` principal na
 * classpath de teste, então `buscai.retrieval.*` fica ausente nos testes que sobem o contexto
 * Spring sem sobrescrever essas chaves.
 *
 * `minCosineSimilarity` está marcado "a calibrar" em `plan.md` — 0.5 é um ponto de partida, não um
 * número validado contra o golden set (`specs/eval/golden-set.json` ainda vazio, T9).
 * `neighborDedupMinOverlapChars` é uma aproximação documentada em
 * [com.buscai.backend.retrieval.context.ContextAssembler] (metade do overlap mínimo estimado do
 * chunking, ADR-0002), não um número medido do pipeline real.
 */
@ConfigurationProperties(prefix = "buscai.retrieval")
data class RetrievalProperties(
    val vectorCandidates: Int = 50,
    val lexicalCandidates: Int = 50,
    val topK: Int = 8,
    val rrfK: Int = 60,
    val tokenBudget: Int = 3000,
    val minCosineSimilarity: Double = 0.5,
    val neighborDedupMinOverlapChars: Int = 75,
    val maxExactItemNumbers: Int = 3,
)
