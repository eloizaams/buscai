package com.buscai.backend.embedding

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Binding de `buscai.ai.voyage.*` (`application.yml`, ADR-0003). Registrado via
 * `@ConfigurationPropertiesScan` em `BackendApplication`. [apiKey] só vem da variável de ambiente
 * `VOYAGE_API_KEY` (nunca hardcoded/logada — CLAUDE.md); ficar vazia (default local/teste sem a
 * env var setada) não impede o contexto Spring de subir, só falha de fato ao chamar a Voyage real
 * ([VoyageEmbeddingClient]).
 *
 * [modelVersion] é a versão registrada em `BookVersion.embeddingModelVersion` (ADR-0008 — chave de
 * gatilho skip/reindex-necessário, ver `IngestionService`, T7): um tag de versão mantido pelo
 * operador, independente do nome do modelo em si ([model]) — permite forçar reindexação quando algo
 * na configuração de embedding muda sem o nome do modelo da Voyage mudar (ex.: ajuste de chunking).
 *
 * Defaults nos parâmetros espelham os defaults de `application.yml` (`${VOYAGE_API_KEY:}` /
 * `voyage-3` / `v1`) — necessários porque `src/test/resources/application.yml` substitui (não
 * mescla) o `application.yml` principal na classpath de teste, então `buscai.ai.voyage.*` fica
 * ausente nos testes que sobem o contexto Spring sem sobrescrever essas chaves.
 */
@ConfigurationProperties(prefix = "buscai.ai.voyage")
data class VoyageProperties(
    val apiKey: String = "",
    val model: String = "voyage-3",
    val modelVersion: String = "v1",
)
