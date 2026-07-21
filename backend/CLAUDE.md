## backend/ — API + ingestão

Stack: Kotlin, Spring Boot 4, Spring Data JPA, Postgres (`pgvector`), Gradle Kotlin DSL.

Comandos (rodar dentro de `backend/`):
```
./gradlew ktlintFormat   # formata antes de considerar qualquer tarefa concluída
./gradlew test           # testes (contextLoads sobe com H2 em memória — ver src/test/resources)
./gradlew build          # build + testes + ktlint check
```

Convenções:
- Sem acesso direto a `pgvector`/repositório fora da camada de serviço.
- Toda variável sensível (`ANTHROPIC_API_KEY`, `VOYAGE_API_KEY`, `BUSCAI_API_KEY`,
  `DATABASE_URL`/`DATABASE_USERNAME`/`DATABASE_PASSWORD`) só via variável de ambiente —
  ver `src/main/resources/application.yml`. Nunca hardcode nem commite um valor real.
- Endpoints que chamam Claude/Voyage devem validar o header `X-Api-Key` (ADR-0005) antes de gastar
  crédito nas APIs pagas.
- Testes que precisam de Postgres real (pgvector) usam Testcontainers — a partir da Fase 3, quando
  as entidades existirem. O `contextLoads` atual não testa nada específico de pgvector de propósito.
