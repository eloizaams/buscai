# Planejamento — App Android de Q&A sobre Livros (RAG) com SDD + Claude Code

**Objetivo:** app Android que ingere livros em PDF, indexa o conteúdo em um banco vetorial e responde perguntas sobre os livros com citações (livro/página).
**Método de desenvolvimento:** Spec-Driven Development (SDD) usando Claude Code como harness.

---

## Fase 0 — Decisões de arquitetura (antes de escrever código)

> **Concluída em 2026-07-12.** As tabelas abaixo eram o ponto de partida; a decisão final,
> incluindo mudanças de escopo (ingestão fora do app, backend completo, Spring Boot, Neon +
> Render/Fly, auth por chave estática, estado de conversa no servidor), está registrada e é a
> fonte da verdade em [`docs/adr/`](docs/adr/) (ADR-0001 a ADR-0007). As Fases 3, 4 e 6 abaixo
> precisam ser lidas à luz desses ADRs — a ingestão e o retrieval deixaram de ser feature do app
> Android e viraram responsabilidade do backend.

### Decisão 1: onde roda o RAG
| Opção | Descrição | Prós | Contras |
|---|---|---|---|
| A — 100% on-device | Ingestão, embeddings, busca e geração no aparelho (LLM local) | Offline total, privacidade | Geração fraca, app pesado |
| **B — Híbrido (recomendada)** | Ingestão, embeddings e vector DB on-device; geração via Claude API | Boa qualidade de resposta, dados dos livros ficam no aparelho | Precisa de rede p/ responder |
| C — Backend completo | Kotlin/Spring + pgvector/Qdrant; app é só cliente | Aproveita seu perfil backend, escala | Servidor p/ manter, PDFs saem do device |

### Decisão 2: modelo de embedding (on-device)
- **Recomendado:** ONNX Runtime Android + modelo **multilíngue** (ex.: `multilingual-e5-small` ou `paraphrase-multilingual-MiniLM-L12-v2`), quantizado int8. Essencial se os livros forem em PT-BR.
- Alternativa simples: MediaPipe Text Embedder (foco em inglês — validar qualidade em PT antes).
- Nota: a Anthropic não oferece API de embeddings própria; se preferir embeddings via API, a recomendação oficial é Voyage AI.

### Decisão 3: vector DB on-device
- **Recomendado:** ObjectBox (índice HNSW nativo, API Kotlin-first).
- Alternativas: SQLite + extensão `sqlite-vec`; Room + busca brute-force (só para POC pequena).

### Decisão 4: LLM de geração
- **Recomendado:** Claude API (Haiku 4.5 p/ custo, Sonnet p/ qualidade) via **proxy backend** — nunca embutir a API key no APK.
- Modo offline (v2): Gemma via MediaPipe LLM Inference API.

**Entregável da fase:** ADRs (Architecture Decision Records) curtos em `docs/adr/` — vão alimentar as specs.

---

## Fase 1 — Setup do harness (Claude Code)

1. Criar repositório Git + projeto Android (Kotlin, Jetpack Compose, minSdk 26+, Gradle KTS, version catalog).
2. Instalar o Claude Code e rodar `/init` na raiz para gerar o `CLAUDE.md`.
3. Refinar o `CLAUDE.md` com: stack, estrutura de módulos, convenções (ktlint/detekt), comandos de build/teste (`./gradlew testDebugUnitTest`, `lint`), regras ("toda feature nasce de uma spec em `specs/`", "sem lógica em Composable").
4. Configurar permissões em `.claude/settings.json`: allowlist para comandos Gradle e Git seguros.
5. Hooks: pós-edição rodar `ktlintFormat` + testes rápidos do módulo tocado.
6. Comandos customizados em `.claude/commands/` (ex.: `/spec-feature`, `/review`, `/gen-tests`).
7. Subagents (ex.: `code-reviewer`, `test-writer`) em `.claude/agents/`.
8. CI (GitHub Actions): build + lint + testes em PR. Opcional: Claude Code GitHub Action para review automático de PRs.

Docs oficiais: https://docs.claude.com/en/docs/claude-code/overview

---

## Fase 2 — SDD: especificações

Usar o **spec-kit** (GitHub) integrado ao Claude Code, ou estrutura manual em `specs/`. Artefatos:

1. **constitution.md** — princípios inegociáveis: privacidade (livros não saem do device), arquitetura em camadas, cobertura mínima de testes, sem API keys no cliente.
2. **spec.md (funcional)** — requisitos: importar PDFs, biblioteca com status de indexação, indexação com progresso e cancelamento, chat com respostas em streaming citando livro+página, histórico de conversas, filtro de busca por livro.
3. **plan.md (técnico)** — módulos, contratos entre camadas, modelos de dados (Book, Chunk, Embedding, Conversation), dependências escolhidas na Fase 0.
4. **tasks.md** — quebra em tarefas pequenas, testáveis e ordenadas. Cada tarefa vira uma sessão do Claude Code.

**Loop por feature:** especificar → planejar (plan mode do Claude Code) → gerar tasks → implementar → review (subagent) → commit. Nunca implementar sem spec.

---

## Fase 3 — Pipeline de ingestão

1. Seleção de PDFs via Storage Access Framework (SAF).
2. Extração de texto com **PdfBox-Android** (`com.tom-roush:pdfbox-android`). Detectar PDFs escaneados (sem camada de texto) e avisar o usuário — OCR com ML Kit fica para v2.
3. Limpeza/normalização: remover hifenização de quebra de linha, headers/footers repetidos, normalizar espaços.
4. **Chunking:** 300–800 tokens por chunk, overlap de 10–20%, respeitando limites de parágrafo. Metadados por chunk: `bookId`, `page`, `chapter` (se detectável), `charOffset`.
5. Geração de embeddings em batch com **WorkManager** (foreground service com notificação de progresso).
6. Persistência: chunks + vetores no ObjectBox; metadados do livro (título, autor, hash, status) em entidade própria.
7. Idempotência: hash SHA-256 do arquivo para evitar reindexação do mesmo livro.

**Teste de aceite:** ingerir um livro de ~300 páginas sem OOM e com progresso visível.

---

## Fase 4 — Busca e retrieval

1. Busca vetorial top-k (cosine) via HNSW, com filtro opcional por livro.
2. **Busca híbrida (recomendada):** combinar busca lexical (FTS/BM25) + vetorial com fusão RRF — melhora recall em nomes próprios e termos exatos.
3. Re-ranking opcional (v2): cross-encoder pequeno ou LLM re-rank sobre os top-20.
4. Montagem de contexto: deduplicar chunks vizinhos, limitar por orçamento de tokens, anexar metadados para citação.

**Teste de aceite:** latência de busca < 100 ms com ~50k chunks.

---

## Fase 5 — Geração (RAG)

1. **Prompt de sistema:** responder somente com base no contexto fornecido; citar livro e página; declarar explicitamente quando a resposta não está nos livros.
2. **Proxy backend fino** (Ktor ou Spring — seu território): recebe pergunta + contexto do app, chama a Messages API da Claude com streaming (SSE), devolve tokens ao app. API key só no servidor. Docs: https://docs.claude.com/en/api/overview
3. Multi-turno: **query rewriting** — reescrever a pergunta atual usando o histórico antes do retrieval.
4. UI de citações: cada trecho citado clicável, abrindo o chunk/página de origem.
5. (Opcional v2) Modo offline com Gemma on-device como fallback.

---

## Fase 6 — App Android

**Arquitetura:** MVVM + camadas limpas, Hilt, Coroutines/Flow. Módulos: `:app`, `:core:data`, `:core:domain`, `:feature:library`, `:feature:chat`, `:ingestion`, `:rag`.

**Telas:**
1. **Biblioteca** — lista de livros, status (indexando/pronto/erro), importar/remover.
2. **Chat** — streaming da resposta, citações, seletor de escopo (todos os livros ou um específico), histórico.
3. **Detalhe do livro** — metadados, nº de chunks, reindexar.
4. **Configurações** — endpoint do proxy, top-k, modelo, limpar dados.

---

## Fase 7 — Testes e avaliação de RAG

1. **Unit:** chunking (fronteiras, overlap), normalização de texto, montagem de prompt, fusão RRF.
2. **Instrumented:** DAO/vector store, fluxo de ingestão fim a fim com PDF pequeno de fixture.
3. **Eval de RAG (crítico):** golden set de 30–50 perguntas com respostas esperadas por livro. Métricas: recall@k do retrieval, groundedness/faithfulness da resposta (LLM-as-judge via Claude). Rodar como script/task no harness.
4. Regressão: eval automatizado a cada mudança em chunking, embedding ou prompt.

---

## Fase 8 — Performance e release

1. Benchmarks: tempo de ingestão por página, latência de busca, memória de pico.
2. Quantização do modelo ONNX (int8), controle de threads.
3. Baseline Profiles + R8/minify.
4. Extração de PDFs grandes em streaming (página a página), nunca carregar o livro inteiro em memória.
5. Play Console — internal testing → closed track.

---

## Roadmap sugerido

| Semana | Entrega |
|---|---|
| 1 | Fases 0–2: ADRs, harness Claude Code, specs completas |
| 2–3 | Fases 3–4: ingestão + busca, validadas por testes instrumentados (antes de UI) |
| 4 | Fase 5: proxy + geração com streaming e citações |
| 5 | Fase 6: UI completa |
| 6 | Fases 7–8: eval de RAG, performance, release interno |

---

## Riscos e mitigações

- **PDFs escaneados sem texto** → detectar na importação e informar; OCR em v2.
- **Qualidade de embedding em PT-BR** → modelo multilíngue + rodar o eval de retrieval já na semana 2.
- **API key exposta** → proxy obrigatório; nunca no APK.
- **OOM em livros grandes** → extração e embedding em streaming/batches via WorkManager.
- **Deriva de qualidade ao mudar chunking/prompt** → golden set + eval de regressão desde cedo.
