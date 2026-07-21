# Plan — Referência estruturada de chunk (capítulo/item) e lista de fontes no chat

Depende de `spec.md` (critérios de aceite, revisado pelo `android-architect`) e das decisões já
fixadas no [ADR-0013](../../docs/adr/0013-referencia-estruturada-de-chunk-capitulo-ou-item.md), que
amenda o [ADR-0008](../../docs/adr/0008-identidade-e-versionamento-de-livros-ingeridos.md) (piso de
chunk condicional ao estilo de referência). Estende `specs/retrieval/plan.md` e
`specs/geracao/plan.md` como já estão — nenhuma mudança em `catalog/` além do schema descrito
abaixo, nenhuma mudança na fusão RRF/`ContextAssembler` em si.

## Módulo e localização

Mesmo módulo Gradle único `backend/`. Um tipo novo compartilhado (`ingestion/chunking`, reaproveitado
por `catalog`/`retrieval`/`generation`), o resto são mudanças em arquivos já existentes:

```
backend/src/main/kotlin/com/buscai/backend/
  ingestion/
    chunking/
      ReferenceType.kt              — NOVO: enum CHAPTER / NUMBERED_ITEM (compartilhado: é o
                                       mesmo enum usado pela flag da CLI, por ChunkDraft/Chunk e
                                       por RetrievedChunk/evento SSE — um único tipo, não um por
                                       camada)
      ReferenceAnnotator.kt         — NOVO: detecta a referência de cada parágrafo conforme o
                                       ReferenceType declarado (ver "Detecção de referência")
      Chunker.kt                    — TOCADO: chunk(pageTexts, referenceType: ReferenceType?):
                                       List<ChunkDraft>; ChunkDraft ganha reference: String?
                                       (chapter removido); grouping muda para NUMBERED_ITEM (ver
                                       "Chunking atômico por item")
      ChunkValidator.kt             — TOCADO: validate(chunks, referenceType): piso de
                                       MIN_CHUNK_TOKENS vira condicional (pulado quando
                                       referenceType == NUMBERED_ITEM)
    cli/
      IngestCommand.kt              — TOCADO: IngestArgs ganha referenceType: ReferenceType?;
                                       IngestArgsParser aceita
                                       --reference-style=chapter|numbered-item
    IngestionService.kt             — TOCADO: ingest(...) ganha referenceType: ReferenceType? =
                                       null, propaga para startVersion (persiste em
                                       BookVersion.referenceType) e para
                                       extractCleanAndChunk/Chunker.chunk; embedAndPersistInBatches
                                       grava Chunk.reference/referenceType em vez de chapter
  catalog/
    Chunk.kt                        — TOCADO: chapter: String? → reference: String? +
                                       referenceType: ReferenceType? (@Enumerated(STRING))
    BookVersion.kt                  — TOCADO: + referenceType: ReferenceType? (proveniência da
                                       ingestão — usado por ChunkValidator/relatórios; a citação
                                       usa sempre o par por chunk, não este campo)
  retrieval/
    RetrievedChunk.kt                — TOCADO: chapter: String? → reference: String? +
                                       referenceType: ReferenceType? (text já existe)
    search/
      HybridSearchRow.kt             — TOCADO: idem
      HybridSearchDao.kt             — TOCADO: SELECT/RowMapper leem reference/reference_type em
                                       vez de chapter
    RetrievalService.kt              — TOCADO: mapeamento HybridSearchRow → RetrievedChunk
                                       propaga reference/referenceType
  generation/
    GenerationService.kt             — TOCADO: ANSWER_SYSTEM_PROMPT reescrito (capítulo/item, não
                                       página); buildUserPrompt cita reference; answer() ganha
                                       onSourcesResolved: (List<RetrievedChunk>) -> Unit = {}
    web/
      ChatEvent.kt                   — TOCADO: + data class Sources(sources: List<SourceItem>);
                                       SourceItem(chunkId, bookId, bookTitle, reference,
                                       referenceType, text)
      ChatController.kt              — TOCADO: sendChatEvent serializa Sources como JSON (ver
                                       "Serialização do evento sources"); runChat passa
                                       onSourcesResolved emitindo o evento antes do primeiro token
  db/migration/
    V4__chunk_reference.sql          — NOVO (ver "Schema")
```

## Schema (Flyway V4)

```sql
ALTER TABLE chunk
    ADD COLUMN reference VARCHAR(1000),
    ADD COLUMN reference_type VARCHAR(20)
        CHECK (reference_type IN ('CHAPTER', 'NUMBERED_ITEM'));

ALTER TABLE book_version
    ADD COLUMN reference_type VARCHAR(20)
        CHECK (reference_type IN ('CHAPTER', 'NUMBERED_ITEM'));

-- Backfill idempotente e seguro: chapter nunca foi preenchido em produção (Chunker sempre gravou
-- null — ver ADR-0013, Contexto), mas o backfill é feito de qualquer forma por correção, não por
-- necessidade prática observada.
UPDATE chunk SET reference = chapter, reference_type = 'CHAPTER' WHERE chapter IS NOT NULL;

ALTER TABLE chunk DROP COLUMN chapter;
```

`page`/`char_offset` continuam `NOT NULL` — permanecem metadado interno de proveniência/debug
(`RetrievalDebugCommand`), nunca citados ao usuário a partir desta feature (ADR-0013, constitution.md
seção 4 já revisada).

## Detecção de referência (`ReferenceAnnotator`)

Roda dentro de `Chunker.chunk`, entre a divisão em parágrafos (já existente,
`splitIntoParagraphs`) e o agrupamento em chunks (`groupUnits`) — anota cada `ParagraphUnit` com uma
`reference: String?` antes de agrupar, na ordem de leitura (página a página, parágrafo a parágrafo).
`referenceType == null` (nenhuma flag na ingestão): nenhuma anotação roda, todo `reference` fica
`null` — comportamento idêntico ao atual.

- **`CHAPTER`**: mantém "o último cabeçalho de capítulo visto" como estado ao percorrer os
  parágrafos em ordem. Regex de cabeçalho: linha (parágrafo curto, tipicamente < 8 tokens) que casa
  `^CAP[ÍI]TULO\s+([IVXLCDM]+|\d+)` (case-insensitive) — captura o rótulo completo do parágrafo
  (ex. `"Capítulo XII"`) como a referência corrente. Todo parágrafo seguinte, até o próximo
  cabeçalho, herda essa referência. Parágrafos antes do primeiro cabeçalho ficam com `reference =
  null` (ex.: prefácio/introdução antes do Capítulo I).
- **`NUMBERED_ITEM`**: regex de abertura de item `^\s*(\d+)\.\s` no início do parágrafo — o número
  capturado é a referência daquele parágrafo e de qualquer parágrafo seguinte que não abra com um
  novo número (nota/continuação do mesmo item), até o próximo parágrafo que abrir com número.
  Parágrafos antes do primeiro item numerado ficam com `reference = null`.

Ambos os casos: implementado como uma função pura `annotate(units: List<ParagraphUnit>, type:
ReferenceType): List<ParagraphUnit>` (devolve as mesmas unidades com `reference` preenchido) — sem
estado além do que já é local ao laço, testável isoladamente com uma lista de parágrafos sintética
(sem precisar de PDF real).

## Chunking atômico por item (`NUMBERED_ITEM`) — amenda ADR-0008

Aplica-se só quando `referenceType == NUMBERED_ITEM`, dentro de `groupUnits`:

- **Fronteira de grupo obrigatória a cada mudança de `reference`** entre parágrafos consecutivos:
  `groupUnits` fecha o grupo atual sempre que o próximo parágrafo tem uma `reference` diferente do
  grupo em andamento — mesmo que o grupo esteja abaixo de `MIN_CHUNK_TOKENS`. Isso substitui a
  regra MIN-first (item 3b do KDoc atual de `Chunker`) **só para este estilo**: nunca corta um
  parágrafo para "completar" o piso às custas de misturar dois itens.
- **`reference` do `ChunkDraft`**: o número do item quando o grupo contém um único valor de
  `reference` entre seus parágrafos; um intervalo (`"155–158"`, primeiro–último) quando o
  agrupamento por `MAX_OWN_CONTENT_TOKENS` (itens curtos consecutivos, sem violar a fronteira acima)
  junta mais de um item no mesmo chunk.
- **Item maior que `MAX_OWN_CONTENT_TOKENS` sozinho**: cai no caminho já existente de
  `splitOversizedParagraph` (corta em fronteira de token). Limitação aceita e registrada aqui: um
  item assim fica citado com o mesmo número em dois chunks vizinhos (cada um cobre uma parte) — não
  há forma de manter um teto de tamanho de chunk E nunca cortar um item que sozinho excede o teto;
  não é o caso comum em catecismo numerado (itens são tipicamente curtos), por isso não justifica
  tratamento especial além do que `splitOversizedParagraph` já faz.
- **`CHAPTER`**: sem mudança de comportamento de agrupamento — o piso/teto de 300–800 tokens do
  ADR-0008 continua valendo como hoje (capítulos são tipicamente muito maiores que 300 tokens, a
  imprecisão de "chunk cruza fronteira de capítulo" já existe hoje de forma equivalente com página e
  não é o problema que este ADR resolve). `reference` de cada chunk é simplesmente o rótulo de
  capítulo corrente no primeiro parágrafo do grupo.

`ChunkValidator.validate(chunks, referenceType)`: quando `referenceType == NUMBERED_ITEM`, pula a
verificação de `MIN_CHUNK_TOKENS` (mantém: não vazio, `<= MAX_CHUNK_TOKENS`, overlap 10–20% entre
vizinhos — a lógica de overlap não muda, ela mede tokens reais compartilhados, independente do
piso). Para `CHAPTER` ou `null`, comportamento idêntico ao atual (piso sempre verificado).

## CLI de ingestão

`IngestArgsParser` ganha `--reference-style=chapter|numbered-item` (opcional; ausente = `null`,
comportamento atual). Valor inválido (nem `chapter` nem `numbered-item`) é erro de parsing
(`IngestArgsResult.Error`), mesmo padrão dos demais argumentos — nunca chega a chamar
`IngestionService.ingest` com um valor não mapeável.

```
SPRING_PROFILES_ACTIVE=ingest ./gradlew bootRun --args="--book-id=o-livro-dos-espiritos --file=/caminho/livro.pdf --reference-style=numbered-item"
SPRING_PROFILES_ACTIVE=ingest ./gradlew bootRun --args="--book-id=dom-casmurro --file=/caminho/livro.pdf --reference-style=chapter"
```

`--reindex` continua igual (ADR-0008) — reindexar um livro para adicionar referência estruturada é
rodar de novo com `--reference-style` + `--reindex`, exatamente o "caminho de migração" descrito no
ADR-0013 seção 5. `IngestionService.ingest` ganha o parâmetro `referenceType: ReferenceType? = null`,
gravado em `BookVersion.referenceType` (em `startVersion`) e repassado para `chunker.chunk(...)` e
`chunkValidator.validate(...)`.

## Contratos entre camadas

- **Propagação `reference`/`referenceType`**: `ReferenceAnnotator` (dentro de `Chunker`) →
  `ChunkDraft` → `Chunk` (persistido) → `HybridSearchRow` (SELECT nativo de `HybridSearchDao`) →
  `RetrievedChunk` (`RetrievalService`) → `GenerationService` (prompt + `onSourcesResolved`) →
  `ChatEvent.Sources` (`ChatController`). Nenhuma camada nova nessa cadeia — cada uma já existe e já
  carrega um campo equivalente (`chapter`) sendo substituído.
- **`GenerationService.answer` — novo parâmetro `onSourcesResolved`:** mesmo padrão de
  `onConversationResolved`/`onToken` (callback, não acesso direto de `ChatController` a
  `RetrievalService` — mantém `GenerationService` como única porta de entrada da lógica,
  `specs/geracao/plan.md`). Invocado **uma vez**, no ramo `RetrievalResult.Found`, com
  `retrievalResult.chunks`, **antes** da chamada a `claudeClient.generate` — replica a mesma janela
  de tempo em que `onConversationResolved` já roda antes de qualquer geração, o que garante a ordem
  `conversation? → sources? → token*` no SSE (CA1 da spec). No ramo `NoRelevantContext`, o callback
  não é invocado (CA2).
- **`ChatController.runChat`**: passa `onSourcesResolved = { chunks -> emitter.sendChatEvent(
  ChatEvent.Sources(chunks.map { it.toSourceItem() }) ) }` — mapeamento local `RetrievedChunk →
  SourceItem`, sem expor `RetrievedChunk` (tipo de domínio) diretamente na camada web.
- **`ANSWER_SYSTEM_PROMPT` (reescrito, supersede o texto atual):** instrui citar livro + capítulo
  (`referenceType == CHAPTER`) ou livro + número do item/pergunta (`referenceType ==
  NUMBERED_ITEM`) diretamente no texto da resposta; quando o chunk não tem `reference` (`null`),
  cita só o livro, nunca inventa nem cai em página. Página nunca é mencionada — não é enviada ao
  prompt como algo citável (o bloco de contexto de `buildUserPrompt` já não inclui mais "p. X").
- **`buildUserPrompt` (formato do bloco de contexto por chunk):** `"[${bookTitle}${referenceLabel}]
  \n${text}"`, onde `referenceLabel` é `", capítulo: $reference"`, `", item: $reference"` ou vazio
  (referenceType nulo), conforme o `referenceType` do chunk — substitui o formato atual
  `", p. $page — capítulo: $chapter"`.

## Serialização do evento `event: sources`

`ChatEvent.Sources(sources: List<SourceItem>)`, `SourceItem(chunkId: UUID, bookId: String,
bookTitle: String, reference: String?, referenceType: ReferenceType?, text: String)`.
`ChatController` ganha uma dependência de `com.fasterxml.jackson.databind.ObjectMapper` (bean já
auto-configurado pelo Spring Boot — nenhuma dependência nova; hoje nenhuma classe do projeto injeta
`ObjectMapper` diretamente, mas é a forma padrão do framework de serializar um DTO como o
`event.data()` de um `SseEmitter`, que espera `String`) para serializar `sources` como o array JSON
descrito no ADR-0013 seção 3. `referenceType`, quando presente, serializa como o nome do enum
(`"CHAPTER"` / `"NUMBERED_ITEM"`) — comportamento padrão do Jackson para enum Kotlin, sem
customização.

`sendChatEvent` ganha o ramo:
```kotlin
is ChatEvent.Sources -> SseEmitter.event().name("sources").data(objectMapper.writeValueAsString(event))
```

## Compatibilidade com o cliente-web já publicado

- Verificação (task dedicada, não assumida): confirmar que o parser SSE de `web/app.js` (já em
  produção via PR #11) ignora com segurança um `event:` desconhecido — não trata `data:` de
  `sources` como delta de `token` nem quebra o parsing do stream. Se o parser hoje despacha por
  `event.data` sem checar `event.type`/nome do evento primeiro, isso é um bug a corrigir nesta
  mesma feature antes de introduzir `event: sources` em produção (não pode regredir o cliente já no
  ar).
- Mudança de rótulo da citação inline (página → capítulo/item) é comportamento novo visível
  imediatamente no cliente já publicado, sem exigir mudança nenhuma no `web/app.js` (a citação
  continua chegando como texto dentro de `event: token`, só o conteúdo do texto muda).
- Painel de fontes a partir de `event: sources` fica fora desta feature (spec/tasks do
  `specs/cliente-web/`, como já registrado em `spec.md`).

## Retrocompatibilidade (livros já ingeridos, `reference` NULL)

Coberto por CA6 da spec: `SourceItem` para um chunk sem referência vem com `reference: null` e
`referenceType: null`, sempre presente na lista (nunca omitido). `GenerationService` trata esse caso
no prompt (citação só por livro). Nenhuma migração de dado automática — reindexar com
`--reference-style` é ação explícita do operador (ADR-0008/ADR-0013).

## Gate de avaliação (constitution.md, seção 4)

Duas mudanças desta feature exigem `rag-evaluator` contra `specs/eval/golden-set.json` antes do
merge (constitution.md seção 4, CLAUDE.md): o novo `ANSWER_SYSTEM_PROMPT` (citação por
capítulo/item) e o piso de chunk condicional a `NUMBERED_ITEM`. O golden set precisa de casos novos
cobrindo um livro `numbered-item` real — as perguntas "qual a pergunta 157/158" e "o que acontece no
momento da morte" desta sessão (sobre *O Livro dos Espíritos*) são o ponto de partida, com
`expected_sources` apontando `reference`/`referenceType` em vez de página.

## Fora do plano desta feature

- Busca estruturada exata por número de item (consulta direta em `reference` no retrieval) —
  follow-up registrado no ADR-0013, spec própria depois desta.
- Truncamento do `text` no `event: sources` — vai completo (ADR-0013); revisar só se o tamanho do
  payload se mostrar um problema real em produção.
- UI do painel de fontes no `web/` — spec/tasks do `specs/cliente-web/`.
- Re-ingestão em massa do acervo existente para popular `reference` — a critério do operador,
  livro a livro, via `--reindex`.
- Detecção automática (sem flag) do estilo de referência — rejeitada no ADR-0013.
