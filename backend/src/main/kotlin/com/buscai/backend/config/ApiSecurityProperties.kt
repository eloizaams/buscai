package com.buscai.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Binding de `buscai.api-key` e `buscai.rate-limit.requests-per-minute` (`application.yml`,
 * ADR-0005). Registrado via `@ConfigurationPropertiesScan` em `BackendApplication` (mesmo mecanismo
 * de [com.buscai.backend.embedding.VoyageProperties]).
 *
 * [apiKey] não tem default de verdade: `application.yml` interpola `${BUSCAI_API_KEY:}` (string
 * vazia se a variável de ambiente não estiver setada). Decisão explícita entre as duas opções
 * levantadas na task: fazer o boot falhar sem `BUSCAI_API_KEY` setado quebraria `contextLoads` e
 * qualquer teste que suba o contexto Spring sem configurar a variável (nenhum teste hoje faz
 * isso); em vez disso, [ApiKeyFilter] trata `apiKey` nulo/vazio/só espaço como "negar toda
 * requisição" (fail-closed), nunca como "aceitar qualquer coisa por acidente" (fail-open) — um
 * ambiente de produção sem a variável setada fica indisponível (401 para todo mundo), não vira um
 * proxy aberto para as APIs pagas de Claude/Voyage.
 *
 * [rateLimit] é "a calibrar" — mesma ressalva de
 * [com.buscai.backend.retrieval.RetrievalProperties.minCosineSimilarity]: 30 requisições/minuto é
 * um ponto de partida, não um número medido contra tráfego real.
 */
@ConfigurationProperties(prefix = "buscai")
data class ApiSecurityProperties(
    val apiKey: String? = null,
    val rateLimit: RateLimit = RateLimit(),
) {
    data class RateLimit(
        val requestsPerMinute: Int = 30,
    )
}
