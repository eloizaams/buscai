# Tasks — Limite de item numerado sem linha em branco entre itens + golden set expandido

Ordem é significativa (ver `plan.md`, "Ordem das tasks"): golden set expandido e baseline vêm
antes do fix, para CA6 ter um "antes" válido também para os casos novos. Cada task é uma chamada
nova de `kotlin-implementer` (ou trabalho direto de conteúdo, quando marcado), seguida de
`code-reviewer` antes do commit — regra já fixada em `CLAUDE.md`.

## T1 — Expandir `specs/eval/golden-set.json` para 30-50 casos (CA7)

- Adicionar casos cobrindo: lookup direto por número de item (com `reference` real, numeração
  1-1019 confirmada no diagnóstico — usar itens verificáveis a partir do texto já extraído),
  pergunta semântica em linguagem natural, pergunta com nome do livro, pergunta sem nome do livro,
  pergunta sem resposta no acervo (`NoRelevantContext` esperado), pergunta multi-chunk. Manter os
  3 casos existentes (`espiritos-001/002/003`).
- `expected_answer_gist`/`expected_sources.reference` de cada caso novo verificados contra o
  conteúdo real — eu proponho o rascunho a partir do texto já extraído, você confirma antes de eu
  considerar a task concluída (ver `plan.md`, "Quem verifica o conteúdo").
- Sem código de produção tocado.
- **Critério de pronto**: CA7 da spec satisfeito.

## T2 — Baseline: rodar `rag-evaluator` contra o golden set expandido, código ainda não corrigido

- Rodar `rag-evaluator` contra o golden set de T1, sem nenhuma mudança em `Chunker`/
  `ReferenceAnnotator` ainda — este é o "antes" que falta para os casos novos.
- Requer infra real (Postgres, credenciais) — roteiro em `DESAFIOS.md` (2026-07-21); comandos de
  linha única via `!` quando chegar a hora (extração/PDF eu já alcanço diretamente nesta sessão,
  mas Postgres/API keys continuam só na sua máquina).
- Registrar em `specs/eval/history.md`.
- **Critério de pronto**: baseline datada cobrindo todos os casos de T1.

## T3 — Corrigir `Chunker.splitIntoParagraphs` para reconhecer abertura de item como fronteira

- Fonte única da regex de abertura de item, compartilhada entre `Chunker.kt` e
  `ReferenceAnnotator.kt` (ver `plan.md`, "Fonte única da regex").
- `splitIntoParagraphs` passa a receber `referenceType`; quando `NUMBERED_ITEM`, uma linha que
  abre item também é fronteira de parágrafo, além de linha em branco. Comportamento inalterado
  para `null`/`CHAPTER` (CA3).
- `charOffset` de cada sub-parágrafo recalculado corretamente (ver `plan.md`).
- Teste novo com fixture sintética: vários itens numerados curtos consecutivos, sem linha em
  branco entre eles (reproduz o padrão real do livro) — verifica que cada item recebe sua própria
  `reference` corretamente.
- Rodar testes existentes de `Chunker`/`ReferenceAnnotator`/`ChunkValidator` sem regressão (CA3).
- **Critério de pronto**: CA3 satisfeito; teste novo verde.

## T4 — Resolver o achado de overlap em itens curtos (se o teste de T3 confirmar)

- Rodar `ChunkValidator.validate` sobre o resultado do teste sintético de T3 (muitos itens curtos
  consecutivos). Se **não houver violação de overlap**: task vira só a checagem (nenhuma mudança
  de código), registrar isso e encerrar.
- Se **houver violação**: aplicar a Opção 1 já pré-aprovada em `plan.md` ("⚠️ PENDENTE
  (contingente)") — pular a checagem de overlap para `referenceType == NUMBERED_ITEM` em
  `ChunkValidator`, mesmo precedente do `MIN_CHUNK_TOKENS`. Adicionar nota datada ao ADR-0008.
- **Critério de pronto**: CA4 da spec satisfeito (reingestão vai completar sem violação de
  overlap, verificável a partir do teste sintético mesmo antes da reingestão real).

## T5 — Notas datadas nos ADRs

- ADR-0008: nota datada (2026-07-21) para o achado do `--reindex` (força reprocessamento mesmo com
  trigger-key idêntica — decisão já aprovada pelo usuário em sessão anterior) — se T6 ainda não
  tiver adicionado essa nota.
- ADR-0013: nota datada registrando (a) `NUMBERED_ITEM_OPENING_REGEX` com uso duplo (detecção +
  fronteira de parágrafo, fonte única) e (b) a decisão deliberada de não validar
  monotonicidade/continuidade da numeração (YAGNI/frágil, ver `plan.md`).
- Sem código de produção tocado — só documentação.

## T6 — Corrigir `--reindex` para forçar reprocessamento mesmo com `fileHash` inalterado

- **Decisão já aprovada** (sessão anterior, mantida nesta spec como CA8).
- `IngestionService.ingest`: quando `reindex=true`, reprocessar mesmo se a chave de gatilho
  `(bookId, fileHash, embeddingModel, embeddingModelVersion)` bater com a versão ativa.
- Teste novo em `IngestionServiceTest`: mesmo `bookId`/`fileHash`/modelo, `reindex=true` →
  `Completed` (reprocessa), não `Skipped`; sem a flag continua `Skipped` (nenhuma regressão).
- Nota datada no ADR-0008 (pode ser feita junto com T5, se ainda pendente).
- **Critério de pronto**: CA8 satisfeito.

## T7 — Reingestão real e avaliação final

- Você reingere *O Livro dos Espíritos* com `--reindex --reference-style=numbered-item` (roteiro
  em `DESAFIOS.md`) usando o código de T3+T4+T6.
- Confirmar `IngestionOutcome.Completed` (CA4) e, por amostra (`RetrievalDebugCommand` ou consulta
  direta), que os itens 157/158 saem com `reference` correta (CA2) e que ao menos 950 números de
  item distintos aparecem entre os chunks (CA1).
- Rodar `rag-evaluator` contra o mesmo golden set de T2, comparar ponto a ponto com a baseline —
  recall/groundedness não regride (CA6).
- Registrar o resultado final em `specs/eval/history.md`.
- **Critério de pronto**: CA1, CA2, CA4, CA6 satisfeitos; frente encerrada — atualizar
  `docs/planejamento-melhoria-qualidade-rag.md` (Frente 1 pivotou de espaçamento para este
  problema; registrar a nota datada explicando o pivô) e a Frente 2 embutida, com data e link para
  esta spec/PR.

## Fora destas tasks (fica para as frentes seguintes do roadmap)

Busca exata por número (Frente 3 — agora ainda mais motivada, já que `reference` passa a ser
confiável), prompt/histórico (Frente 4), calibração de retrieval (Frente 5) — nenhuma task acima
toca `retrieval/` ou `generation/`.
