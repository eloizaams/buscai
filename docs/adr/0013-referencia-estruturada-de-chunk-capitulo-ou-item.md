# ADR-0013: Referência estruturada de chunk (capítulo ou item numerado) e lista de fontes no SSE

## Status
Aceito — 2026-07-21

## Contexto
Um teste real ingerindo "O Livro dos Espíritos" (Allan Kardec) expôs duas limitações do modelo de
citação atual, que hoje cita fonte só por número de página (`Chunk.page`, ADR-0002):

1. **Página não é referência estável entre edições.** O mesmo livro em outra edição tem paginação
   diferente — citar "p. 42" não é reproduzível para quem consulta outra edição.
2. **Livros de catecismo são estruturados em itens numerados** ("157. Que é a morte? ..."), não em
   capítulos. Hoje esse número vive como texto comum dentro do `chunk.text`; não há como citá-lo nem
   recuperá-lo de forma estruturada, e a busca (`HybridSearchDao`/`RetrievalService`) trata "157"
   como token qualquer — uma pergunta por "pergunta 157" pode não trazer o item 157 mesmo indexado
   (falso negativo).

O campo `Chunk.chapter` existe no schema (V1) e na entidade, mas **nunca é preenchido**: o
`Chunker` (`ChunkDraft.chapter` fixo em `null`) deixou "detecção de capítulo fora do escopo" da task
original de ingestão.

Requisito de produto validado com o dono:
- A resposta da API mantém o texto gerado pela IA (`GenerationService.answer`, streaming por
  `event: token`).
- **Além** do texto, a resposta traz uma **lista estruturada** dos chunks que fundamentaram a
  resposta — não só citação solta no meio do texto.
- Cada chunk citado referencia **capítulo** (prosa) **ou** **número do item/pergunta** (catecismo
  numerado) — **nunca página**.
- Modelo de dado: um campo genérico `reference` (string) + um `referenceType` (enum `CHAPTER` /
  `NUMBERED_ITEM`) por chunk — mais DRY que dois campos opcionais; o cliente checa um único tipo
  para formatar o rótulo ("Capítulo" vs "Pergunta").
- A lista chega ao cliente via um **evento SSE novo** (`event: sources`), disparado uma vez, análogo
  ao `event: conversation` de `ChatController` — fora dos deltas de `event: token`.
- **Precisão da citação de item numerado é prioridade sobre o piso de tamanho de chunk do
  ADR-0008**: citar "157" tem que significar exatamente o item 157, nunca um intervalo aproximado.
- **A lista de fontes inclui o texto do chunk**, não só id + referência — o cliente mostra o trecho
  original ao lado da citação sem chamada extra.

Este ADR decide como detectar, modelar, propagar e transportar essa referência, e o impacto no
`Chunker`. Não redecide embedding (ADR-0003) nem o contrato base de SSE (specs/geracao) — só estende.

## Decisão

### 1. Detecção do estilo de referência na ingestão: flag explícita do operador + extração por estilo

O **estilo** é declarado pelo operador na CLI; o **valor** de cada chunk é extraído por um matcher
específico do estilo. Nova opção em `IngestArgsParser`:

```
--reference-style=chapter | numbered-item
```

- `chapter`: um `ReferenceAnnotator` percorre o texto limpo mantendo o "último cabeçalho de capítulo
  visto" (heurística de regex sobre linhas do tipo `Capítulo N`, `CAPÍTULO N`, algarismos romanos,
  etc.) e estampa esse rótulo em cada parágrafo/chunk subsequente.
- `numbered-item`: regex de início de item (`^\s*(\d+)\.\s`) marca o número que abre cada item; cada
  parágrafo carrega o número de item a que pertence.
- **Ausência da flag** (comportamento padrão, retrocompat): nenhuma referência é detectada
  (`reference`/`referenceType` nulos) — igual ao estado atual, sem quebrar ingestões existentes.

**Alternativas consideradas:**
- *Auto-detecção global (sem flag), por heurística.* Prós: zero esforço do operador. Contras:
  frágil e não-determinística — anos ("1857."), notas de rodapé, listas numeradas dentro de prosa
  geram falsos positivos; um palpite errado rotula referências silenciosamente errado. Rejeitada
  como decisor do *tipo* do livro.
- *Só flag, sem extração automática do valor* (operador digita capítulos/itens). Inviável: milhares
  de itens por livro.
- **Flag decide o estilo + extração automática do valor (escolhida).** Determinística no que é
  arriscado (qual a estrutura do livro), automática no que é volumoso (o valor por chunk).
  Coerente com a filosofia do ADR-0008 (operador fornece `bookId` explicitamente em vez de derivar
  do arquivo) — o operador que ingere conhece a estrutura do livro.

A anotação roda no pipeline de ingestão, entre a limpeza (`TextCleaner`) e o chunking, alimentando
`ChunkDraft`.

### 2. Schema, entidade e propagação

**Migration `V4__chunk_reference.sql`:**
- Adiciona `reference VARCHAR(1000)` e `reference_type VARCHAR(20) CHECK (reference_type IN
  ('CHAPTER','NUMBERED_ITEM'))` em `chunk`, ambos nuláveis.
- Backfill idempotente: `UPDATE chunk SET reference = chapter, reference_type = 'CHAPTER' WHERE
  chapter IS NOT NULL` (no-op na prática — `chapter` nunca foi preenchido — mas correto e seguro).
- Remove a coluna `chapter` (DRY: um único par `reference`/`reference_type`). Destrutivo, mas seguro
  porque `chapter` está 100% nula hoje.
- `book_version.reference_style VARCHAR(20)` como proveniência do estilo declarado na ingestão —
  usado pelo `ChunkValidator` (seção 4) para saber qual invariante estrutural aplicar.

**`page` permanece** `NOT NULL` no schema — continua sendo metadado interno de proveniência/debug
(`RetrievalDebugCommand`, `charOffset`), mas **deixa de aparecer em qualquer citação** ao usuário.

**Entidade `Chunk`:** substitui `chapter: String?` por `reference: String?` + `referenceType:
ReferenceType?` (enum novo, persistido como `@Enumerated(STRING)`).

**Cadeia de propagação** (nenhuma camada nova; estende contratos existentes):
`ChunkDraft(reference, referenceType)` → `Chunk` → `HybridSearchRow`/`HybridSearchDao`
(SELECT + `RowMapper` passam a ler `reference`/`reference_type`) → `RetrievedChunk(reference,
referenceType, text)` (substitui `chapter`) → `GenerationService` (prompt e evento `sources`).

### 3. Payload do `event: sources`

Novo `ChatEvent.Sources`, emitido **uma vez**, quando o retrieval retorna `RetrievalResult.Found`,
**antes do primeiro `event: token`** (as fontes já são conhecidas após a busca, antes da geração — a
mesma janela em que `event: conversation` já é emitido). Ordem final de eventos:
`conversation?` → `sources?` → `token*` → `done` (ou `error`).

Formato (`event: sources`, `data:` = JSON de uma linha), **incluindo o texto do chunk**:

```json
{"sources":[
  {"chunkId":"<uuid>","bookId":"o-livro-dos-espiritos","bookTitle":"O Livro dos Espíritos","reference":"157","referenceType":"NUMBERED_ITEM","text":"157. Que é a morte? — [...]"},
  {"chunkId":"<uuid>","bookId":"dom-casmurro","bookTitle":"Dom Casmurro","reference":"Capítulo XII","referenceType":"CHAPTER","text":"[...]"}
]}
```

`text` é o texto completo do chunk (o mesmo `RetrievedChunk.text` usado para montar o prompt de
geração) — sem truncamento adicional: o chunk já é limitado a `MAX_CHUNK_TOKENS`/o piso por-estilo
da seção 4, então o payload do evento fica limitado ao mesmo teto que já governa o `tokenBudget` do
retrieval. `chunkId` serve de chave/dedup no cliente.

**Emissão via callback**, não acesso direto: `GenerationService.answer` ganha um parâmetro
`onSourcesResolved: (List<RetrievedChunk>) -> Unit`, invocado no ramo `Found` antes de
`claudeClient.generate`, análogo a `onConversationResolved`/`onToken`. `ChatController` mapeia para
`ChatEvent.Sources`. Mantém `GenerationService` como única porta de entrada da lógica (o controller
não chama `RetrievalService` por fora). No ramo `NoRelevantContext`, **nenhum** `event: sources` é
emitido (não há fundamentação a listar).

As fontes = os chunks de `RetrievalResult.Found` (o conjunto que alimentou o prompt). Não há como
saber, sem a API de citations da Claude, qual subconjunto o modelo de fato usou; o conjunto de
contexto é a melhor aproximação honesta de "fundamentaram a resposta".

### 4. Impacto no `Chunker` para itens numerados — piso por-estilo (amenda ADR-0008)

O risco de precisão é o corte **no meio de um item**: a regra MIN-first (item 3b do KDoc do
`Chunker`) pode cortar um parágrafo para atingir `MIN_CHUNK_TOKENS=300`, partindo um item entre dois
chunks e tornando a citação ambígua ou errada.

**Decisão: item numerado é a unidade atômica de chunk.** Para `--reference-style=numbered-item`:
- Nunca partir um item entre chunks.
- Agrupar itens consecutivos até `MAX_CHUNK_TOKENS` (800), como hoje.
- **Permitir chunks abaixo de `MIN_CHUNK_TOKENS` (300)** quando um item completo (ou o resto de um
  agrupamento alinhado a fronteira de item) fica abaixo desse piso — o piso de 300 do ADR-0008 deixa
  de ser aplicado a este estilo.
- `reference` do chunk é **sempre o número de um único item** quando o chunk contém só um; se o
  agrupamento juntar itens curtos consecutivos no mesmo chunk, `reference` vira o intervalo
  ("155–158") **apenas nesse caso** — mas a busca por número exato (follow-up da seção 4, fora deste
  ADR) deve resolver por item individual dentro do chunk, não pelo chunk inteiro, então a
  ambiguidade de citação de resposta livre ("o que acontece na morte") é aceitável; a citação de
  lookup direto ("pergunta 157") deve sempre resolver para o chunk cujo intervalo contém 157.

Isto **amenda o ADR-0008**, seção "Verificação estrutural de chunk": a verificação de piso mínimo
passa a ser **condicional ao `reference_style` do `book_version`** — `ChunkValidator` recebe o
estilo e aplica 300 tokens só quando não é `numbered-item`. Para `numbered-item`, o piso vira "chunk
não vazio, alinhado a fronteira de item" (novo critério estrutural, não ausência de critério).

Por ser mudança de chunking, **passa pelo `rag-evaluator`** contra um golden set que inclua um livro
numerado antes de mergear (CLAUDE.md) — os casos de "pergunta 157"/"pergunta 158" desta sessão viram
entradas do golden set.

**Fora do escopo deste ADR (follow-up):** resolver o falso negativo de "pergunta 157" na *busca* não
depende só da fronteira de chunk — depende de tornar o número do item um predicado **estruturado e
exato** (consulta direta em `reference`), não de confiar no `ts_vector`/embedding. Isso toca
`HybridSearchDao`/`RetrievalService` e, por ser mudança de retrieval, exige `rag-evaluator` próprio.
Fica registrado como trabalho subsequente, habilitado por este ADR (a coluna `reference` passa a
existir e a citar por item único), mas não decidido aqui.

### 5. Retrocompatibilidade (livros já ingeridos, `reference` NULL)

Todos os livros atuais têm `reference` nulo (o `chapter` nunca foi preenchido). Como a decisão de
produto é **nunca cair na página**:

- O chunk **continua aparecendo** no `event: sources`, com `reference: null` e `referenceType: null`
  (mas com `text` preenchido) — o cliente o renderiza como citação **em nível de livro** ("Fonte: O
  Livro dos Espíritos"), sem rótulo de capítulo/pergunta e **sem página**. Omitir a fonte esconderia
  uma fundamentação real; cair na página contrariaria o requisito.
- No prompt de geração, chunk sem `reference` é citado só pelo título do livro (sem página).
- **Caminho de migração:** re-ingerir o livro com `--reference-style` declarado (via `--reindex`,
  ADR-0008) preenche as referências. É o mecanismo esperado para retirar a página de vez das
  citações do acervo existente.

O `ANSWER_SYSTEM_PROMPT` de `GenerationService` muda: de "cite o livro e a **página**" para "cite o
livro e a **referência** (capítulo/pergunta), ou só o livro quando não houver referência" — mudança
de prompt de geração que **exige `rag-evaluator`** antes de mergear (CLAUDE.md).

## Consequências
- **Contratos alterados** (impacto direto no `plan.md` da feature): `ChunkDraft`, `Chunk`,
  `HybridSearchRow`/`HybridSearchDao` (SELECT + RowMapper), `RetrievedChunk` (+`text` já existia,
  ganha `reference`/`referenceType`), `GenerationService` (assinatura de `answer` com
  `onSourcesResolved`, `buildUserPrompt`, system prompt), `ChatEvent` (novo `Sources`),
  `ChatController` (`sendChatEvent`), `IngestArgsParser`/`IngestArgs`, `IngestionService` (propaga
  estilo → `ChunkDraft`), `ChunkValidator` (piso condicional ao estilo), `BookVersion`
  (`referenceStyle`).
- **Migração destrutiva** (`DROP COLUMN chapter`) — segura hoje (coluna 100% nula), mas a partir de
  V4 `chapter` deixa de existir; qualquer código/relatório que a referencie quebra (só
  `RetrievedChunk.chapter`/`HybridSearchRow.chapter` atuais, cobertos pela migração de contrato).
- **ADR-0008 amendado**: a verificação estrutural de piso mínimo de chunk deixa de ser universal —
  passa a ser condicional ao `reference_style`. Livros `numbered-item` podem ter chunks bem menores
  que 300 tokens, por design.
- **Duas mudanças gated por `rag-evaluator`**: o piso por-estilo do chunking (item 4) e o novo
  system prompt (item 5). Precisam de entradas de golden set para um livro numerado
  (`specs/eval/golden-set.json`) antes do merge — as perguntas 157/158/"momento da morte" desta
  sessão são o ponto de partida natural.
- **Página nunca mais é citada ao usuário**, mas permanece como metadado interno — sem custo de
  migração de dados.
- **Payload do `event: sources` cresce** com o texto de cada chunk (até `MAX_CHUNK_TOKENS` cada,
  tipicamente vários por resposta) — aceito deliberadamente pela decisão de produto de mostrar o
  trecho junto da citação sem chamada extra; se o tamanho do evento se tornar um problema real de
  banda/latência, revisitar (ex.: truncar `text` por um teto de caracteres) fica registrado aqui
  como ponto a observar, não decidido preventivamente (YAGNI).
- **Cliente web** (`web/`) precisa renderizar o painel de fontes a partir do `event: sources` e
  formatar o rótulo por `referenceType` ("Capítulo" vs "Pergunta") — impacto de UI, fora do backend.
- O operador ganha responsabilidade de declarar `--reference-style` por livro (como já declara
  `bookId` — ADR-0008); esquecer a flag degrada graciosamente para citação em nível de livro, não
  quebra a ingestão.
