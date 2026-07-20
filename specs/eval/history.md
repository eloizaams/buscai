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
