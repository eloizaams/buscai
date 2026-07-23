# Plan — busca-exata-item (R5)

Deriva de `spec.md` + parecer do `android-architect` (2026-07-23, spec aprovada com ressalvas).
Abordagem decidida pelo dono do repo: **A+C** — a busca exata entra como terceira CTE na query
única do `HybridSearchDao` (preserva a invariante "porta única de busca", 1 round-trip — relevante
no cold start do Neon free tier), e o predicado de match usa **colunas inteiras derivadas**
`chunk.item_start`/`chunk.item_end` indexadas, em vez de parse de string por query. Sem
reingestão: migration + backfill sobre o índice existente.

Opção B (query separada + merge em Kotlin) foi descartada no parecer: viola a porta única, exige
`rrfScore` sintético e dedup manual, e custa 2 round-trips.

## Escopo de módulos

Só `backend/` (+ `docs/adr/` e `specs/eval/golden-set.json`). Nenhuma mudança em `web/` (contrato
SSE inalterado, RF6) nem em `android/`.

## Modelo de dados

**Migration `V5__chunk_item_range.sql`:**
- `ALTER TABLE chunk ADD COLUMN item_start INT NULL, ADD COLUMN item_end INT NULL`.
- Backfill idempotente, restrito a `reference_type = 'NUMBERED_ITEM'`, parseando `reference`:
  - valor único `"N"` → `item_start = item_end = N`;
  - faixa `"N–M"` — separador **en-dash `–` (U+2013)**, nunca hífen (fonte: `Chunker.kt`,
    `groupReference`, literal `"$first–$last"`) — → `item_start = N`, `item_end = M`;
  - defesa: linha cujo parse falhe ou produza `item_start > item_end` fica com as colunas NULL
    (nunca aborta a migration; com o índice pós R3/R4 isso não deve ocorrer, mas a migration não
    pode depender disso).
  - O parse de string em SQL acontece **só aqui, uma vez** (dado legado); o caminho quente nunca
    parseia string.
- Índice parcial para o predicado de range-contains:
  `CREATE INDEX idx_chunk_item_range ON chunk (book_version_id, item_start, item_end) WHERE item_start IS NOT NULL`.
- `CHECK (item_start IS NULL OR item_end >= item_start)`.

**Fonte única (DRY):** na ingestão daqui pra frente, `item_start`/`item_end` são derivados **do
mesmo ponto que produz o rótulo** — `Chunker.groupReference` já tem a lista ordenada de
referências do grupo (`references.firstOrNull()`/`lastOrNull()`, strings de dígitos); os inteiros
saem de `toInt()` sobre esses mesmos valores, nunca de re-parse do rótulo montado. `reference`
segue como rótulo de exibição/citação; `item_start`/`item_end` são índice derivado — registrado na
nota do ADR-0013 (abaixo). Para `CHAPTER` e estilo ausente, ambos ficam nulos.

## Contratos entre camadas (mudanças)

Cadeia de propagação (estende contratos existentes; nenhuma camada nova):

1. **`ChunkDraft`** (`ingestion/chunking/Chunker.kt`): + `itemStart: Int?`, `itemEnd: Int?`
   (default `null`). `groupReference` passa a devolver os três valores juntos (rótulo + ints) —
   um único ponto de derivação.
2. **`Chunk`** (entidade, `catalog`): + `itemStart: Int?`/`itemEnd: Int?` mapeando
   `item_start`/`item_end`. `IngestionService` propaga do draft.
3. **`ItemLookupDetector`** (novo, pacote `retrieval` — componente puro, sem I/O): implementa RF1.
   - Entrada: o texto da pergunta. Saída: `List<Int>` de números de item detectados (vazia = sem
     lookup), já **limitada a `RetrievalProperties.maxExactItemNumbers`** (risco R7 do parecer).
   - Regex de marcador pt-BR, case-insensitive, tolerante a acento: `pergunta|questão|questao|
     item|nº|n\.?º?|número|numero` seguido (com artigos/espaços opcionais) de `\d+`. O léxico
     exato e os casos de falso positivo obrigatórios (CA4: "1857", "os 10 mandamentos",
     "salmo 23", "capítulo 25" — capítulo NÃO é marcador de item nesta feature) nascem nos testes
     da task.
4. **`HybridSearchDao.search`**: + parâmetro `exactItemNumbers: List<Int>` (vazia = query idêntica
   à atual em comportamento). SQL ganha a CTE `exact_rank`:
   - predicado: `c.book_version_id IN (:eligibleBookVersionIds) AND c.reference_type =
     'NUMBERED_ITEM' AND c.item_start <= n AND c.item_end >= n` para cada `n` (via
     `JOIN unnest(:exactItemNumbers)` ou `EXISTS`), com `LIMIT` de segurança (= `topK`);
   - guard `reference_type` é o RF4/risco R9 (blinda contra colisão com livros `CHAPTER` futuros);
   - **fusão por boost aditivo fixo, não peso RRF**: a CTE contribui `EXACT_MATCH_SCORE`
     (constante no DAO, ex.: `1.0` — duas ordens de grandeza acima do teto teórico do RRF,
     `2/(rrfK+1) ≈ 0.033`) somado ao `rrf_score` do FULL OUTER JOIN (agora 3-way). Racional: o
     `ContextAssembler` reordena por `rrfScore` internamente, então "reservar slot" por posição de
     lista não sobrevive à passagem — o boost garante que o chunk exato ordene à frente em
     **todas** as camadas (take(topK), dedup, orçamento) sem mudar o `ContextAssembler` (risco R2).
     O afogamento do contexto semântico em pergunta mista (risco R3) é contido pelo **cap** de
     números (item 3) e pelo tamanho típico pequeno de chunk de item — o restante do
     `tokenBudget` continua sendo preenchido pelos candidatos híbridos na ordem RRF normal.
   - dedup de identidade: grátis — o FULL OUTER JOIN por `chunk_id` unifica o chunk que veio de
     2–3 ramos (risco R8 não se aplica na opção A).
5. **`HybridSearchRow`**: + `matchedExactBranch: Boolean` (análogo a `matchedLexicalBranch`).
6. **`RetrievalService`**: chama o `ItemLookupDetector` sobre `query`, repassa os números ao DAO;
   o filtro de relevância (CA7) ganha a terceira cláusula
   `|| row.matchedExactBranch` — sem ela, um chunk que só veio do ramo exato (cosine 0.0, sem
   match léxico) seria descartado e CA1/CA2 falhariam silenciosamente (risco **crítico R1** do
   parecer; mesma classe de bug já documentada no KDoc de `matchedLexicalBranch`).
7. **`RetrievalProperties`**: + `maxExactItemNumbers: Int = 3` (`buscai.retrieval.
   max-exact-item-numbers`). `EXACT_MATCH_SCORE` fica constante no DAO (não é knob de calibração —
   só precisa dominar o RRF; YAGNI expor em config).

Sem mudança em: `ContextAssembler`, `RetrievedChunk`, `GenerationService`, `ChatEvent`/SSE,
`ChunkValidator` (o invariante `item_start <= item_end` é por construção no Kotlin e por CHECK no
banco).

## Documentação (decisão do parecer: sem ADR novo completo)

- **Nota datada no ADR-0013 §4** promovendo o follow-up a decidido: (a) 3ª CTE na porta única
  `HybridSearchDao`; (b) `matchedExactBranch` como exceção deliberada ao filtro CA7; (c) colunas
  derivadas `item_start`/`item_end` (fonte única em `groupReference`; `reference` segue sendo o
  rótulo); (d) detector de intenção RF1 e seu risco de falso positivo.
- **Linha de referência no ADR-0003**: a busca híbrida ganha um ramo estruturado exato
  (aponta para a nota do ADR-0013).

## Avaliação (gate)

Mudança de retrieval ⇒ gate `rag-evaluator` obrigatório (constitution §4), régua = bloco
"Resultado: Gate T5" de `specs/eval/history.md`. O golden set ganha antes do gate:
- caso positivo de lookup puro ("qual a pergunta 25?" — o caso real da sessão de 2026-07-22,
  fonte esperada `reference` contendo 25; espiritos-013 já cobre a pergunta mista);
- caso negativo CA4 (número sem marcador, ex.: pergunta com ano, sem fonte de item esperada);
- caso CA5 (item inexistente, ex.: "pergunta 9999" — esperado não alucinar).

Infra real necessária (Neon/.env/PDF local — ver memória `project_rag_evaluator_gate_infra_real`):
o gate roda com o dono do repo executando via `!`, comandos de linha única.

## Riscos aceitos / decisões registradas

- Backfill em SQL parseia o en-dash uma única vez (dado legado); qualquer falha vira NULL, nunca
  erro — chunk sem colunas preenchidas simplesmente não participa do ramo exato (degrada para o
  comportamento atual, RF5).
- `EXACT_MATCH_SCORE` fixo em vez de reserva de slot por posição: escolhido porque o
  `ContextAssembler` reordena por score; revisitar só se o gate mostrar pergunta mista com
  contexto semântico insuficiente.
- Múltiplos livros numerados no escopo: todos os matches entram (RF4) — o `LIMIT` da CTE é
  teto de segurança, não ranking por obra.
