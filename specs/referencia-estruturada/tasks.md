# Tasks — Referência estruturada de chunk (capítulo/item) e lista de fontes no chat

Ordem sequencial — cada item depende do(s) anterior(es) e cabe numa sessão do
`kotlin-implementer`. Todos em `backend/` (T6 toca `web/`, JS puro). Rodar `./gradlew ktlintFormat`
e os testes do módulo (`backend/`) ao final de cada task que toca Kotlin.

- [x] **T1 — `ReferenceType` + `ReferenceAnnotator` + `Chunker`/`ChunkValidator` (chunking atômico
  por item)**
  Pacote `com.buscai.backend.ingestion.chunking`. `ReferenceType.kt`: enum `CHAPTER` /
  `NUMBERED_ITEM` — tipo único reaproveitado por `ChunkDraft`, `Chunk` (T2), `IngestArgs` (T2) e
  `RetrievedChunk`/`SourceItem` (T3/T5), conforme `plan.md`. `ReferenceAnnotator.kt`: função pura
  `annotate(units: List<ParagraphUnit>, type: ReferenceType): List<ParagraphUnit>` (ou tipo
  equivalente que carregue a referência por parágrafo) implementando a detecção descrita em
  `plan.md` ("Detecção de referência") — `CHAPTER` mantém o último cabeçalho `Capítulo
  [IVXLCDM\d]+` visto como estado; `NUMBERED_ITEM` mantém o último número de abertura de item
  (`^\s*(\d+)\.\s`) visto como estado; parágrafos antes do primeiro marcador ficam com referência
  `null`. `Chunker.chunk` ganha o parâmetro `referenceType: ReferenceType?` (default `null`),
  chama `ReferenceAnnotator` logo após `splitIntoParagraphs` quando não-nulo; `ChunkDraft.chapter`
  vira `ChunkDraft.reference: String?`. `groupUnits`: quando `referenceType == NUMBERED_ITEM`,
  fecha o grupo sempre que a `reference` do próximo parágrafo difere da do grupo em andamento
  (nunca mistura dois itens, mesmo abaixo do piso — substitui a regra MIN-first 3b só para este
  estilo); `reference` do `ChunkDraft` resultante é o valor único ou o intervalo `"primeiro–último"`
  quando o grupo cobre mais de um item. `CHAPTER`/`null`: comportamento de agrupamento inalterado
  (só o preenchimento de `reference` muda). `ChunkValidator.validate` ganha o parâmetro
  `referenceType: ReferenceType?`; pula a checagem de `MIN_CHUNK_TOKENS` quando `NUMBERED_ITEM`
  (mantém não-vazio, teto `MAX_CHUNK_TOKENS`, overlap 10–20%). Testes: `ReferenceAnnotatorTest`
  (lista sintética de parágrafos, sem PDF real) para os dois estilos + caso sem marcador antes do
  primeiro; `ChunkerTest` novo(s) caso(s) confirmando que `NUMBERED_ITEM` nunca produz um chunk que
  mistura dois números de item cortando um parágrafo no meio, que um item isolado curto vira um
  chunk abaixo de `MIN_CHUNK_TOKENS`, e que itens curtos consecutivos podem ser agrupados com
  `reference` em intervalo; `CHAPTER` e `referenceType = null` continuam batendo os testes já
  existentes (nenhuma regressão); `ChunkValidatorTest` confirmando o piso pulado só para
  `NUMBERED_ITEM`.

- [x] **T2 — Migration V4, `Chunk`/`BookVersion`, `IngestArgsParser`/`IngestionService` (fim a fim
  na ingestão)**
  `V4__chunk_reference.sql` (Flyway) exatamente como `plan.md`, seção "Schema" (add
  `reference`/`reference_type` em `chunk`, add `reference_type` em `book_version`, backfill,
  `DROP COLUMN chapter`). `Chunk.kt` (`catalog`): `chapter: String?` → `reference: String?` +
  `referenceType: ReferenceType? ` (`@Enumerated(EnumType.STRING)`, `@Column(name =
  "reference_type")`). `BookVersion.kt`: `+ referenceType: ReferenceType?`
  (`@Enumerated(EnumType.STRING)`). `IngestArgsParser`: aceita
  `--reference-style=chapter|numbered-item` (opcional); valor fora desse conjunto é
  `IngestArgsResult.Error` claro, sem chegar a `IngestionService.ingest`. `IngestArgs` ganha
  `referenceType: ReferenceType?`. `IngestionService.ingest` ganha `referenceType: ReferenceType? =
  null`: `startVersion` grava em `BookVersion.referenceType`; `extractCleanAndChunk` repassa para
  `chunker.chunk(cleanedPages, referenceType)`; a chamada a `chunkValidator.validate` repassa
  `referenceType`; `embedAndPersistInBatches` grava `Chunk(reference = draft.reference,
  referenceType = referenceType, ...)` em vez de `chapter = draft.chapter`. `IngestCommand` repassa
  `parsed.referenceType` para `ingestionService.ingest`. Teste (Testcontainers,
  `IngestionServiceTest`/equivalente já existente): ingerir um PDF/lote de páginas sintético com
  `referenceType = NUMBERED_ITEM` persiste chunks com `reference`/`referenceType` corretos,
  incluindo ao menos um chunk abaixo de `MIN_CHUNK_TOKENS` (confirma que `ChunkValidator` não
  reprova); ingerir com `referenceType = null` (nenhuma flag) persiste todos os chunks com
  `reference`/`referenceType` nulos, sem nenhuma outra regressão de comportamento (CA7 da spec);
  `IngestArgsParserTest` novo(s) caso(s) para a flag nova (ausente, valor válido de cada estilo,
  valor inválido → erro).

- [x] **T3 — Retrieval propaga `reference`/`referenceType`**
  `RetrievedChunk.kt`, `HybridSearchRow.kt`: `chapter: String?` → `reference: String?` +
  `referenceType: ReferenceType?`. `HybridSearchDao`: `HYBRID_SEARCH_SQL` seleciona
  `ch.reference`/`ch.reference_type` em vez de `ch.chapter`; `ROW_MAPPER` lê `reference` (string) e
  `referenceType` (`rs.getString("reference_type")?.let { ReferenceType.valueOf(it) }`).
  `RetrievalService`: mapeamento `HybridSearchRow` → `RetrievedChunk` propaga os dois campos novos
  (substitui a linha que hoje propaga `chapter`). Atualizar os testes existentes de
  `HybridSearchDaoIntegrationTest`/`RetrievalServiceTest` que hoje usam `chapter` para usar
  `reference`/`referenceType` (nenhuma regressão de comportamento de busca — só o campo de
  citação muda de nome/tipo).

- [x] **T4 — `GenerationService`: prompt por capítulo/item + `onSourcesResolved`**
  `ANSWER_SYSTEM_PROMPT` reescrito (supersede o texto atual, `plan.md` "Contratos entre camadas"):
  instrui citar livro + capítulo ou livro + número do item/pergunta diretamente no texto da
  resposta, e citar só o livro (sem inventar capítulo/item/página) quando o chunk não tiver
  referência — nunca menciona página. `buildUserPrompt`: bloco de contexto por chunk vira
  `"[${chunk.bookTitle}${referenceLabel(chunk)}]\n${chunk.text}"`, `referenceLabel` = `",
  capítulo: $reference"` / `", item: $reference"` / `""` conforme `referenceType`/nulidade (remove
  `p. $page` do bloco). `GenerationService.answer` ganha o parâmetro `onSourcesResolved:
  (List<RetrievedChunk>) -> Unit = {}`, invocado uma única vez no ramo `RetrievalResult.Found`,
  antes de `claudeClient.generate` — não invocado no ramo `NoRelevantContext`. Teste
  (`GenerationServiceTest`, já existente): novo(s) caso(s) confirmando que `onSourcesResolved` é
  chamado exatamente uma vez com os chunks de `Found`, antes de qualquer `onToken`, e nunca chamado
  no ramo `NoRelevantContext`; asserção sobre o texto montado por `buildUserPrompt` (via o que
  chega ao `ClaudeClient` fake) confirmando ausência de "p." e presença do rótulo certo por
  `referenceType`; casos existentes que hoje verificam a citação por página são atualizados para o
  novo formato.

- [x] **T5 — `ChatEvent.Sources` + `ChatController` emite `event: sources`**
  `ChatEvent.kt`: `data class Sources(val sources: List<SourceItem>) : ChatEvent()`;
  `SourceItem(chunkId: UUID, bookId: String, bookTitle: String, reference: String?, referenceType:
  ReferenceType?, text: String)` (arquivo próprio ou nested, a critério da task).
  `ChatController` ganha `private val objectMapper: ObjectMapper` no construtor (bean já
  auto-configurado pelo Spring Boot — sem dependência nova). `sendChatEvent` ganha o ramo `is
  ChatEvent.Sources -> SseEmitter.event().name("sources").data(objectMapper.writeValueAsString(event))`.
  `runChat`: `generationService.answer(..., onSourcesResolved = { chunks -> emitter.sendChatEvent(
  ChatEvent.Sources(chunks.map { it.toSourceItem() })) }, ...)` — mapeamento local `RetrievedChunk
  → SourceItem` (função privada em `ChatController` ou extensão no mesmo arquivo, sem vazar
  `RetrievedChunk` como tipo de resposta HTTP). Teste (`ChatControllerTest`/equivalente já
  existente, `ClaudeClient` fake): pergunta com contexto relevante produz `event: sources` único,
  antes do primeiro `event: token`, com o JSON esperado (campos e `referenceType` serializado como
  `"CHAPTER"`/`"NUMBERED_ITEM"`/ausente); pergunta sem contexto relevante (`NoRelevantContext`) não
  produz `event: sources`; chunk sem referência aparece no JSON com `reference`/`referenceType`
  `null`, nunca omitido da lista (CA6 da spec).

- [x] **T6 — Compatibilidade do `web/app.js` com evento SSE desconhecido**
  Inspecionar o parser SSE de `web/app.js` (já em produção, PR #11): confirmar que despacha por
  `event.type`/nome do evento (não assume que todo `data:` recebido é um delta de `token`) e que um
  `event:` não reconhecido (ex. `sources`, antes de a UI de fontes existir) é ignorado sem quebrar o
  parsing do restante do stream nem poluir a resposta renderizada. Se o parser atual não distinguir
  eventos por nome, corrigir isso nesta task (bug pré-existente que esta feature expõe, não
  característica nova) — sem implementar nenhuma UI de fontes ainda (fora de escopo, `specs/
  cliente-web/`). Teste manual ou automatizado leve (se já existir suíte de teste para `web/`):
  simular um evento SSE `sources` chegando no meio do stream e confirmar que o texto da resposta
  renderizada não é afetado.

- [x] **T7 — Teste de aceite de ponta a ponta (CA1-CA8)**
  Teste de integração (Testcontainers + `MockMvc`/`TestRestTemplate`, `ClaudeClient` fake
  determinístico) cobrindo o pipeline completo: ingerir um pequeno acervo sintético com
  `referenceType = NUMBERED_ITEM` (via `IngestionService`, não via CLI) contendo ao menos um item
  isolado num chunk e um par de itens curtos agrupados num só chunk; perguntar por HTTP e confirmar
  — `event: sources` presente, único, antes do primeiro `token`, com os campos certos (CA1);
  `NoRelevantContext` não produz `event: sources` (CA2); nenhuma citação, nem inline nem em
  `sources`, menciona página (CA3); o item isolado é citado pelo número exato, nunca intervalo
  (CA4); uma pergunta que bate o `reference` de um item específico recupera o chunk certo quando o
  contexto de teste garante que ele está no top-k (CA5, sob as condições controladas do teste, não
  uma garantia geral de busca — ver "Fora de escopo" de `spec.md`); um chunk de um livro ingerido
  sem `referenceType` (fixture separada) aparece em `sources` com `reference`/`referenceType` nulos,
  nunca omitido (CA6); ingestão sem `--reference-style` continua idêntica ao comportamento anterior
  a esta feature, reaproveitando fixtures/testes já existentes de `specs/ingestao-pdf/` (CA7).

- [ ] **T8 — `golden-set.json`, `rag-evaluator` e sincronização de docs**
  Adicionar a `specs/eval/golden-set.json` casos cobrindo um livro `numbered-item` real (*O Livro
  dos Espíritos* — as perguntas "qual a pergunta 157?", "qual a pergunta 158?" e "o que acontece no
  momento da morte?" desta sessão são o ponto de partida), com `expected_sources` usando
  `reference`/`referenceType` em vez de página. Rodar o subagent `rag-evaluator` (constitution.md,
  seção 4) — gate obrigatório para o novo `ANSWER_SYSTEM_PROMPT` (T4) e o piso de chunk condicional
  a `NUMBERED_ITEM` (T1), conforme `plan.md` "Gate de avaliação"; se mostrar regressão de
  recall/groundedness, resolver antes de prosseguir (não é o mesmo caso "golden set vazio,
  não-bloqueante" das features anteriores — desta vez o golden set passa a ter conteúdo real).
  Confirmar que nenhuma decisão tomada durante a implementação (nome exato dos parâmetros/eventos,
  formato final do JSON de `sources`, regex final de detecção de capítulo/item) diverge do que está
  registrado em `docs/adr/0013-...md`/`plan.md`; atualizar com nota datada onde divergir, mesmo
  padrão já usado no fechamento das features anteriores.

Depois de T1–T8: rodar `/review` (subagent `code-reviewer`) sobre o diff completo antes de
qualquer commit/PR, conforme `CLAUDE.md`.
