# Tasks — busca-exata-item (R5)

Ordem de execução; cada task cabe numa sessão do `kotlin-implementer` (uma chamada de Agent por
task — CLAUDE.md). Toda task: `ktlintFormat` + testes do módulo verdes antes de `/commit`; um
commit por task. Ao final da última: `/pr`.

- [x] **T1 — Derivação de `itemStart`/`itemEnd` na ingestão (fonte única)**
  `ChunkDraft` ganha `itemStart: Int?`/`itemEnd: Int?`; `Chunker.groupReference` passa a derivar
  rótulo e inteiros do mesmo lugar (lista ordenada de referências do grupo — `toInt()` sobre os
  valores, nunca re-parse do rótulo "N–M"). `CHAPTER`/estilo ausente → nulos. Testes unit:
  item único, grupo em faixa, preâmbulo sem referência, capítulo.

- [x] **T2 — Migration V5 + entidade + persistência**
  `V5__chunk_item_range.sql` (colunas, backfill parseando `reference` com en-dash U+2013,
  defesa parse-falhou/`start > end` → NULL, índice parcial, CHECK) conforme `plan.md`; `Chunk`
  (entidade) ganha os campos; `IngestionService` propaga do draft. Teste de integração
  (Testcontainers) cobrindo o backfill: "25", "160–164", lixo ("222–5", não numérico) → NULL.

- [ ] **T3 — `ItemLookupDetector` (RF1)**
  Componente puro em `retrieval`; regex de marcadores pt-BR (léxico do `plan.md`), cap
  `RetrievalProperties.maxExactItemNumbers` (novo, default 3). Testes unit com os falsos
  positivos obrigatórios do CA4 ("1857", "os 10 mandamentos", "salmo 23", "capítulo 25") e
  positivos ("pergunta 25", "questão 700", "item 157", "pergunta nº 25", múltiplos números
  acima do cap).

- [ ] **T4 — CTE `exact_rank` no `HybridSearchDao`**
  Parâmetro `exactItemNumbers: List<Int>`; CTE com predicado range-contains + guard
  `reference_type = 'NUMBERED_ITEM'` + LIMIT; fusão 3-way com `EXACT_MATCH_SCORE` aditivo;
  `HybridSearchRow.matchedExactBranch`. Testes de integração: item único, faixa contendo N,
  número inexistente (lista vazia de matches), guard de `reference_type`, chunk exato ordena à
  frente dos híbridos, lista vazia de números ⇒ comportamento idêntico ao atual.

- [ ] **T5 — Fiação no `RetrievalService`**
  Detector → DAO; filtro de relevância CA7 ganha `|| matchedExactBranch` (risco crítico R1).
  Testes unit (DAO mockado): lookup injeta números, chunk exact-only sobrevive ao filtro,
  pergunta sem marcador não altera a chamada.

- [ ] **T6 — Docs + golden set**
  Nota datada no ADR-0013 §4 e linha de referência no ADR-0003 (conteúdo no `plan.md`, seção
  "Documentação"); golden set ganha os casos novos (lookup puro "pergunta 25", negativo CA4,
  item inexistente CA5).

- [ ] **T7 — Gate `rag-evaluator` (CA6)**
  Rodar o golden set completo contra a stack real (roteiro da memória
  `project_rag_evaluator_gate_infra_real`: infra do dono do repo via `!`, comandos de linha
  única); régua = "Resultado: Gate T5" em `specs/eval/history.md`. Registrar resultado em
  `specs/eval/history.md`. Sem regressão ⇒ `/pr`.
