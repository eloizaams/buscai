# Tasks: Delimitação de conteúdo e remoção de overlap (R3+R4)

Comandos Gradle sempre **dentro de `backend/`**. Um commit por task (`/commit`); ao concluir a
última task de código, `/pr`. Merge é do usuário.

## T1 — Parsing e validação de `--content-pages` (CA1)

Em `backend/src/main/kotlin/com/buscai/backend/ingestion/cli/IngestCommand.kt`:

- [x] Campo novo `contentPages: IntRange? = null` em `IngestArgs`.
- [x] `IngestArgsParser`: chave `content-pages`, validação de formato via regex `^(\d+)-(\d+)$`
      e de intervalo (`início >= 1`, `fim >= início`) — erros via `IngestArgsResult.Error`
      seguindo o padrão de mensagem existente ("Valor inválido para --content-pages: ...").
      **Nunca** exceção; o parser não abre o PDF (limite vs. total de páginas é T2).
- [x] Atualizar mensagem de usage ("Argumento não reconhecido") e KDoc do parser com
      `[--content-pages=<início>-<fim>]`.
- [x] `IngestCommand.run` repassa `contentPages` para `IngestionService.ingest`.
- [x] Testes em `IngestCommandTest` (padrão dos testes de `--reference-style`):
      válido `15-280` → `15..280`; ausente → `null`; inválidos `15`, `a-b`, `280-15`, `0-10`
      → `Error` com mensagem exata.

**Pronto quando:** `./gradlew ktlintFormat test` verde. **Responsável:** kotlin-implementer.

## T2 — Extração restrita ao intervalo no `IngestionService` (CA2, CA4)

Em `backend/src/main/kotlin/com/buscai/backend/ingestion/IngestionService.kt`:

- [x] `ingest(...)` ganha parâmetro `contentPages: IntRange? = null`.
- [x] Validação após `pdfTextExtractor.pageCount(file)`: `contentPages.last > pageCount` →
      `IngestionOutcome.Failed` com razão descritiva, antes de criar `BookVersion` (não deixar o
      `require` de `PdfTextExtractor.extractRange` estourar exceção crua).
- [x] `extractCleanAndChunk`: restringir o laço de lotes ao intervalo (início/teto do loop),
      em vez de extrair tudo e filtrar depois. KDoc: detecção de PDF escaneado (ADR-0008) passa
      a ser medida só sobre as páginas do intervalo — deliberado.
- [x] Log INFO com páginas incluídas vs. total quando o intervalo é aplicado.
- [x] `IngestionOutcomeFormatter`: mensagem de `Skipped` passa a orientar `--reindex`
      ("... Use --reindex para reprocessar (ex.: para aplicar outro --content-pages)."), com
      teste de formatação atualizado.
- [x] Testes em `IngestionServiceTest` (Testcontainers, `PdfFixtures`): intervalo parcial gera
      chunks só das páginas do intervalo; `fim > total` → `Failed`; sem intervalo → comportamento
      atual intacto (retrocompat).

**Pronto quando:** `./gradlew ktlintFormat test` verde. **Responsável:** kotlin-implementer.

## T3 — Overlap condicional ao `referenceType` no `Chunker` (CA5)

Em `backend/src/main/kotlin/com/buscai/backend/ingestion/chunking/Chunker.kt` (laço de montagem,
~linhas 160–178):

- [x] `overlapText` vira `null` quando `referenceType == ReferenceType.NUMBERED_ITEM`; demais
      estilos inalterados.
- [x] KDoc da classe atualizado (hoje descreve overlap como universal): overlap condicional;
      `MAX_OWN_CONTENT_TOKENS` (695) deliberadamente inalterado (teto efetivo ~695 para
      `NUMBERED_ITEM`, calibração fica para R7).
- [x] Teste novo em `ChunkerTest`: itens numerados curtos que produzam ≥ 2 chunks — o segundo
      chunk **começa exatamente** na abertura do primeiro item da sua faixa (`text.startsWith`)
      e não contém texto de itens da faixa anterior; `reference` confere com o conteúdo.
- [x] Testes existentes de overlap (prosa/`CHAPTER`) continuam passando sem alteração.

**Pronto quando:** `./gradlew ktlintFormat test --tests "com.buscai.backend.ingestion.chunking.*"`
verde (e depois a suíte toda em T4). **Responsável:** kotlin-implementer.

## T4 — Notas de ADR + suíte completa

- [ ] ADR-0002: nota datada 2026-07-22 (overlap condicional ao `referenceType`; delimitação de
      conteúdo por `--content-pages`) — texto-base na seção "Notas de ADR" do `plan.md`.
- [ ] ADR-0008: complemento à nota de 2026-07-22 (`--content-pages` fora da chave de gatilho;
      intervalo novo exige `--reindex`).
- [ ] `./gradlew ktlintFormat build` verde (build = compile + lint + testes), sem regressão.

**Pronto quando:** build verde + notas revisáveis no diff. **Responsável:** kotlin-implementer.

## T5 — Gate rag-evaluator: diff caso-a-caso vs. T7 (CA3, CA7)

**Operador-run** — exige infra real (Postgres/pgvector, `.env` com Voyage/Anthropic/Buscai keys);
não roda em sandbox. Roteiro completo na seção "Gate rag-evaluator" do `plan.md`; régua = bloco
"Final T7" de `specs/eval/history.md`.

- [ ] Operador determina o intervalo do corpo real do PDF (inspeção manual).
- [ ] Reingestão: `scripts/dev-ingest.sh "--book-id=o-livro-dos-espiritos --file=<pdf>
      --title=O Livro dos Espíritos --reference-style=numbered-item
      --content-pages=<início>-<fim> --reindex"`.
- [ ] 33 casos do golden set via `POST /chat` (mesmo roteiro manual de T2/T7).
- [ ] Diff caso-a-caso vs. T7 (melhora / regressão real / neutro), com `espiritos-029` →
      `NoRelevantContext` esperado como **melhora**.
- [ ] Registro em `history.md` (timestamp ISO, contexto, diff, resumo, groundedness,
      recomendação).
- [ ] Aprovação: regressões reais ≤ 2 e groundedness 33/33.

**Responsável:** usuário/operador (com apoio do agente para tabular o diff).

## T6 — PR

- [ ] `/review` (code-reviewer) sem itens Críticos.
- [ ] `/pr` — branch `feature/conteudo-paginas-overlap`, corpo com link para spec/plan e resumo
      do gate (T5).
- [ ] Merge: **usuário** (nunca o agente).

## Ordem

T1 → T2 (dependentes); T3 independente (commit separado); T4 fecha o código; T5 gate; T6 PR.
T1–T4 são mecânicas com plano claro (Sonnet ou Haiku via kotlin-implementer); T5 é operacional.
