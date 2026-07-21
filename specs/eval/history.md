# Histórico de execuções do eval de RAG

Cada execução do subagent `rag-evaluator` registra uma linha aqui (via hook SubagentStop em
`.claude/settings.json`). Resultados detalhados (recall@k, groundedness, regressões) devem ser
colados abaixo da linha correspondente pelo próprio agente ao final de cada rodada.

---
[2026-07-16T18:00:33Z] eval executado
[2026-07-16T18:03:32Z] eval executado
[2026-07-16T18:08:49Z] eval executado
[2026-07-16T18:14:45Z] eval executado
[2026-07-16T18:18:37Z] eval executado
[2026-07-16T18:20:28Z] eval executado
[2026-07-16T18:21:39Z] eval executado
[2026-07-16T18:31:03Z] eval executado
[2026-07-16T19:00:35Z] eval executado
[2026-07-16T19:04:42Z] eval executado
[2026-07-16T19:08:08Z] eval executado
[2026-07-16T19:31:38Z] eval executado
[2026-07-16T19:39:29Z] eval executado
[2026-07-16T19:56:05Z] eval executado
[2026-07-16T20:01:18Z] eval executado
[2026-07-16T20:03:28Z] eval executado
[2026-07-17T11:58:43Z] eval executado
[2026-07-17T12:12:52Z] eval executado
[2026-07-17T12:50:16Z] eval executado
[2026-07-17T13:07:25Z] eval executado
[2026-07-17T14:10:42Z] eval executado

## Resultado: Golden set vazio (esperado em Fase 4)

**Status**: ✅ Não bloqueante — conforme T9 de `specs/retrieval/tasks.md`

### Contexto
- **Golden set**: vazio (`specs/eval/golden-set.json` tem apenas um array `questions: []`)
  - **Motivo**: nenhum livro real foi ingerido ainda; golden set será preenchido na Fase 5 (ingestão) com 30-50 perguntas reais e suas respostas esperadas
  - **Não é bloqueante**: T9 prevê explicitamente esse cenário — "specs/eval/golden-set.json continua vazio até haver livros reais ingeridos"

### Avaliação de sanidade do pipeline (sem dados reais)

#### Testes da feature
Todos os testes de retrieval passaram:
- `RetrievalScopeTest` ✅
- `RetrievalServiceIntegrationTest` (Testcontainers) ✅
- `RetrievalDebugCommandTest` ✅
- Build + ktlint: ✅

#### Cobertura de arquitetura
Todos os componentes definidos em `specs/retrieval/plan.md` foram implementados:
- T1: `EmbeddingClient.embed()` com `EmbeddingInputType` (DOCUMENT/QUERY) ✅
- T2: Migration V2 com `text_search` (tsvector) + índice GIN ✅
- T3: `HybridSearchDao` com busca vetorial + léxica + fusão RRF ✅
- T4: `RetrievalService` com resolução de escopo + embedding de query ✅
- T5: `ContextAssembler` com dedup de vizinhos + orçamento de tokens ✅
- T6: Sinal de "sem contexto relevante" baseado em `minCosineSimilarity` ✅
- T7: `RetrievalDebugCommand` + `RetrievalProperties` (config binding) ✅
- T8: Teste de latência em ~50k chunks (CA8) ✅
- T9: Documentação (`docs/adr/`) atualizada ✅

### Próximos passos
1. **Fase 5 (ingestão)**: Primeira vez que um livro real será ingerido (criar fixture PDF real ou synthetic)
2. **Primeira execução do golden set**: Assim que pelo menos um livro estiver ingerido, preencher `golden-set.json` com 5-10 perguntas de teste iniciais (mínimo viável)
3. **Regressão CI**: `rag-evaluator` será acionado em toda mudança futura em retrieval/embedding/chunking, comparando contra o histórico

### Recomendação
✅ **Aprovar para merge**: Pipeline funcional, todas as especificações implementadas, testes passando. A ausência do golden set é esperada e não bloqueante — não há teste de qualidade possível sem dados, mas não há regressão de funcionalidade existente.

[2026-07-17T17:11:30Z] eval executado
[2026-07-19T12:19:56Z] eval executado
[2026-07-19T12:29:53Z] eval executado
[2026-07-19T12:31:49Z] eval executado
[2026-07-19T12:46:08Z] eval executado
[2026-07-19T12:49:51Z] eval executado
[2026-07-19T12:52:00Z] eval executado
[2026-07-20T15:15:21Z] eval executado
[2026-07-20T15:18:03Z] eval executado
[2026-07-20T15:54:41Z] eval executado
[2026-07-20T15:59:19Z] eval executado
[2026-07-20T16:00:37Z] eval executado
[2026-07-20T16:04:53Z] eval executado
[2026-07-20T16:10:30Z] eval executado
[2026-07-20T16:12:37Z] eval executado
[2026-07-20T16:15:43Z] eval executado
[2026-07-20T16:19:05Z] eval executado
[2026-07-20T16:19:08Z] eval executado
[2026-07-20T16:20:13Z] eval executado
[2026-07-20T16:25:13Z] eval executado
[2026-07-20T16:25:18Z] eval executado
[2026-07-20T16:29:23Z] eval executado
[2026-07-20T16:30:55Z] eval executado
[2026-07-20T16:34:49Z] eval executado
[2026-07-20T16:43:03Z] eval executado
[2026-07-20T16:47:31Z] eval executado
[2026-07-20T16:49:13Z] eval executado
[2026-07-20T16:53:42Z] eval executado
[2026-07-20T16:57:56Z] eval executado
[2026-07-20T16:59:54Z] eval executado
[2026-07-21T00:12:44Z] eval executado
[2026-07-21T00:17:27Z] eval executado
[2026-07-21T00:21:23Z] eval executado
[2026-07-21T00:24:28Z] eval executado
[2026-07-21T00:31:05Z] eval executado
[2026-07-21T00:42:45Z] eval executado
[2026-07-21T00:53:15Z] eval executado
[2026-07-21T01:31:10Z] eval executado
[2026-07-21T10:41:18Z] eval executado
[2026-07-21T11:11:33Z] eval executado

## Resultado: Golden set preenchido (referencia-estruturada T8), avaliação real não executada

**Status**: ⚠️ Gate não executado de fato — ver detalhe completo em `$notaLivroDosEspiritos`,
`specs/eval/golden-set.json`

### Contexto
- **Golden set**: deixa de estar vazio — `specs/eval/golden-set.json` ganhou 3 casos reais
  (`espiritos-001`/`espiritos-002`/`espiritos-003`) cobrindo *O Livro dos Espíritos* (livro
  `numbered-item`), com `expected_sources` no formato `bookId`/`bookTitle`/`reference`/
  `referenceType` (`SourceItem`, `ChatEvent.kt`), substituindo o formato antigo por página/capítulo.
- **Avaliação real (recall@k/groundedness) não foi executada nesta sessão** — dois motivos, ambos
  bloqueantes neste ambiente:
  1. O PDF real de *O Livro dos Espíritos* não está commitado no repo (ingestão sempre lê de um
     `--file` local, nunca de um arquivo versionado, ADR-0002/ADR-0008) e não há cópia dele neste
     ambiente de execução.
  2. Rodar o `rag-evaluator` de fato (embeddings Voyage + geração Claude reais) exige
     `VOYAGE_API_KEY`/`ANTHROPIC_API_KEY`, ambas ausentes neste ambiente sandbox.
- Os valores de `reference`/`expected_answer_gist` dos 3 casos novos são uma aproximação de boa-fé
  da estrutura conhecida do livro (catecismo de perguntas numeradas), **não conferidos contra a
  paginação/numeração exata da edição que será de fato ingerida** — pendente de correção quando a
  ingestão real acontecer (ver `$notaLivroDosEspiritos` para o texto completo da ressalva).

### Avaliação de sanidade do pipeline (sem dados reais, mesmo proxy da Fase 4)
Suíte de testes do backend rodada como proxy de regressão (nenhum número de recall/groundedness foi
medido nem deve ser inferido a partir daqui):
- Testes direcionados (`Chunker*`, `ReferenceAnnotator*`, `ChunkValidator*`, `IngestArgsParser*`,
  `IngestionService*`, `HybridSearchDao*`, `RetrievalService*`, `GenerationService*`,
  `ChatController*`, `ReferenciaEstruturadaAcceptance*`): ✅ `BUILD SUCCESSFUL`
- Suíte completa (`./gradlew test`, todos os módulos, incluindo Testcontainers): ✅ `BUILD
  SUCCESSFUL`, sem regressão

### Recomendação
⚠️ **Não bloqueante para esta sessão, mas pendente de reexecução real**: aprovar T8 com a ressalva
registrada em três lugares (`golden-set.json`, `tasks.md` T8, aqui) — nenhum número de qualidade foi
inventado. Assim que o livro for ingerido de verdade (`--book-id=o-livro-dos-espiritos
--reference-style=numbered-item`) com `VOYAGE_API_KEY`/`ANTHROPIC_API_KEY` disponíveis, rodar o
`rag-evaluator` de fato contra os 3 casos novos e corrigir `reference`/`expected_answer_gist` no
golden set se divergirem do que a ingestão real produzir.
[2026-07-21T11:54:07Z] eval executado
[2026-07-21T12:04:12Z] eval executado
[2026-07-21T15:41:55Z] eval executado
[2026-07-21T15:43:52Z] eval executado
[2026-07-21T15:47:20Z] eval executado
[2026-07-21T15:51:45Z] eval executado
[2026-07-21T15:52:22Z] eval executado
[2026-07-21T15:55:03Z] eval executado
[2026-07-21T15:57:14Z] eval executado
[2026-07-21T16:00:15Z] eval executado
[2026-07-21T18:39:21Z] eval executado
[2026-07-21T18:54:19Z] eval executado
[2026-07-21T18:54:58Z] eval executado

## Resultado: avaliação real do golden set (referencia-estruturada T8, 2026-07-21)

**Status**: ✅ Gate executado de fato — supersede o bloco "avaliação real não executada" acima.

### Contexto
Livro *O Livro dos Espíritos* ingerido de verdade pelo dono do produto (`--book-id=o-livro-dos-espiritos
--reference-style=numbered-item`, PDF local, Voyage embeddings reais) contra um Postgres/Neon real.
Servidor real subido localmente (`./gradlew bootRun`) e as perguntas feitas pelo `web/` no navegador
contra o backend real (geração real via Claude) — não um script de eval automatizado (não existe um
no repo, confirmado durante a T8) nem uma execução por mim (Claude Code), mas pelo próprio usuário,
que colou as respostas de volta para análise.

### Groundedness: 3/3
Nenhuma resposta inventou conteúdo fora dos chunks reais; nenhuma citação (inline ou em `sources`)
mencionou página; todas citaram o número do item corretamente quando encontraram contexto. Destaque:
ao ser perguntado por um item 158 com conteúdo incorreto sugerido na pergunta, o modelo recusou
corretamente a premissa errada e ainda assim identificou de forma precisa o item 159 vizinho — sem
inventar, puramente fundamentado nos chunks recuperados.

### Recall: 1 falha real observada, não é regressão desta feature
- "qual a pergunta 157?" (frase literal, sem nome do livro, sem scope): **NoRelevantContext** na
  primeira tentativa — nenhum `event: sources`, resposta correta ("não encontrei essa informação"),
  mas o item 157 não foi recuperado. Reformulada semanticamente ("o que acontece depois da morte?"),
  recuperou o item 157 corretamente, com o texto real do item.
- "qual a pergunte 158 do livro dos espíritos?" (com typo, mas com nome do livro): recuperou o item
  158 correto de primeira.
- Causa provável: busca literal por número de item ("157") sem outro contexto semântico não é bem
  servida pela busca híbrida atual (vetorial + lexical) — **não é uma regressão introduzida por esta
  feature** (RRF/retrieval não mudaram aqui, só o rótulo de citação, T3/T4). É exatamente o caso que
  `specs/referencia-estruturada/plan.md` ("Fora do plano desta feature") já registra como follow-up:
  "Busca estruturada exata por número de item (consulta direta em `reference` no retrieval)".

### Conteúdo real corrige a aproximação de boa-fé do golden set
`expected_answer_gist`/notas de `espiritos-001`/`espiritos-002`/`espiritos-003` em
`specs/eval/golden-set.json` foram atualizados com o texto real dos itens 157/158 (a aproximação
original estava topicamente certa — bloco sobre morte/reencarnação — mas com o texto exato diferente
do livro real).

### Recomendação
✅ **Aprovar a feature `referencia-estruturada`** — groundedness e formato de citação (item, nunca
página) validados contra conteúdo real. A falha de recall para consulta literal por número de item
não bloqueia esta feature (comportamento de retrieval pré-existente, não alterado por ela); registrar
como motivação real e concreta para o follow-up já previsto de busca estruturada exata por
`reference`, quando essa spec futura for priorizada.
[2026-07-21T19:20:00Z] eval executado (real, via web/ contra ingestão de produção — ver bloco acima)
