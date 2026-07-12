# buscai

App de perguntas e respostas sobre livros em PDF (RAG). O usuário final só faz perguntas pelo
app Android; os livros são fornecidos e ingeridos pelo desenvolvedor via ferramenta própria no
backend — não há import de PDF pelo app.

**Antes de qualquer decisão de arquitetura, leia `docs/adr/`.** É a fonte da verdade — não
redecida modelo de embedding, vector DB, framework backend, hospedagem ou autenticação; se algo
não estiver coberto, pare e proponha um novo ADR em vez de assumir.

## Estrutura do repositório

```
android/     — app Android (cliente fino de chat, Kotlin + Compose)
backend/     — API + ingestão (Kotlin + Spring Boot)
docs/adr/    — Architecture Decision Records (fonte de verdade da arquitetura)
specs/       — constitution.md, spec.md, plan.md, tasks.md por feature (Fase 2, SDD)
```

`android/` e `backend/` são dois builds Gradle **independentes** (raízes separadas) — sempre rode
os comandos Gradle dentro do diretório correto, nunca da raiz do repo.

## Arquitetura (resumo — detalhe em docs/adr/)

- App Android: só chat. Envia pergunta + id de conversa, recebe streaming (SSE) com citações
  (livro/página). Sem ObjectBox, ONNX Runtime ou PdfBox no device (ADR-0001).
- Backend Spring Boot concentra: ingestão via CLI própria (Apache PDFBox, ADR-0002), embeddings
  via Voyage AI (ADR-0003), vector DB pgvector/Postgres (ADR-0003), geração via Claude API com
  streaming (ADR-0004), autenticação por chave estática + rate limit (ADR-0005), hospedagem em
  Neon (Postgres) + Render/Fly (API), ambos free tier — logo com cold start ocasional (ADR-0006),
  e histórico de conversa persistido no backend, não no app (ADR-0007).

## android/ — app Android

Stack: Kotlin, Jetpack Compose, Hilt, minSdk 26, AGP 9 (Kotlin embutido — **não** aplicar o plugin
`org.jetbrains.kotlin.android`, ver `android/gradle/libs.versions.toml`), Gradle version catalog.

Comandos (rodar dentro de `android/`):
```
./gradlew ktlintFormat              # formata antes de considerar qualquer tarefa concluída
./gradlew testDebugUnitTest         # testes unitários do módulo :app
./gradlew lint                      # Android lint
./gradlew :app:assembleDebug        # build (exige Android SDK instalado)
```

Convenções:
- Sem lógica de negócio em `@Composable` — Composables só leem estado de um `ViewModel`/`UiState`.
- MVVM: `ViewModel` expõe `StateFlow<UiState>`, nunca `LiveData`.
- Toda chamada de rede passa pelo cliente HTTP do backend (nunca chamar Claude/Voyage direto do
  app — a key nunca existe no cliente, ver ADR-0004/ADR-0005).
- `BACKEND_BASE_URL`/`BACKEND_API_KEY` vêm de `BuildConfig` (hoje com placeholder em
  `app/build.gradle.kts` — sobrescrever localmente, nunca commitar valor real).

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

## Regras gerais (SDD)

- Toda feature nasce de uma spec em `specs/` (constitution.md → spec.md → plan.md → tasks.md,
  ver planejamento-app-rag-android.md Fase 2). Nunca implementar sem spec — exceção: scaffolding
  de infraestrutura já coberto por um ADR (como este setup inicial).
- Antes de decidir arquitetura nova (módulo, dependência, vector DB, provedor de IA, hospedagem):
  delegue ao subagent `android-architect` e registre a decisão em `docs/adr/`.
- Depois de qualquer implementação e antes de commit/PR: rode o subagent `code-reviewer`.
- Mudança em chunking, embedding, retrieval ou prompt de geração: rode o subagent `rag-evaluator`
  contra `specs/eval/golden-set.json` antes de mergear.
