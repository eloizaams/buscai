# Tasks — Fontes no cliente web + título real da obra

Duas tasks independentes (arquivos diferentes, sem dependência de dado entre elas) — qualquer
ordem, cada uma é uma chamada nova de `kotlin-implementer`.

## T1 — Renderizar `event: sources` no cliente web (R1)

Arquivo: `web/app.js`. Sem gate de `rag-evaluator` (não toca retrieval/generation).

- [ ] Adicionar `sources: []` e `sourcesExpanded: false` ao objeto de mensagem do assistente
  (`assistantMessage`, criado em `web/app.js:354`).
- [ ] Novo ramo `sources` em `dispatch` (`web/app.js:277-305`): `JSON.parse(data)`, grava em
  `assistantMessage.sources`, garante a mensagem em `state.messages`, chama `renderMessages()`.
- [ ] Em `renderMessages` (`web/app.js:241-253`), para mensagem `ASSISTANT` com
  `sources.length > 0`: montar `<details>` com `<summary>` ("N fontes") + `<ul>`/`<li>` (um item
  por fonte: título do livro + rótulo de referência + trecho), tudo via `textContent`/
  `createElement` (nunca `innerHTML` — texto de livro pode conter `<`/`>`/`&`).
- [ ] `details.open = message.sourcesExpanded` na montagem; listener `toggle` grava o novo estado
  de volta em `message.sourcesExpanded` (sobrevive ao rebuild completo que `renderMessages` faz a
  cada `token`).
- [ ] Rótulo de referência: `CHAPTER` → "Capítulo N", `NUMBERED_ITEM` → "Pergunta N",
  `referenceType` nulo → sem rótulo, só o título do livro (nunca expor página, constitution §4).
- [ ] Verificação manual (sem harness de teste em `web/`): `scripts/dev-run.sh` local + pergunta
  real — checar CA1 (lista recolhida aparece), CA2 (expandir/recolher sobrevive ao streaming),
  CA3 (pergunta sem contexto não mostra lista), CA4 (reabrir conversa antiga não quebra e não
  mostra lista).

## T2 — Título obrigatório na ingestão + correção do dado em produção (R2)

Arquivos: `backend/src/main/kotlin/com/buscai/backend/ingestion/cli/IngestCommand.kt`,
`backend/src/test/kotlin/com/buscai/backend/ingestion/cli/IngestCommandTest.kt`,
`specs/ingestao-pdf/tasks.md`, `CLAUDE.md` (raiz). Sem gate de `rag-evaluator`.

- [ ] `IngestArgsParser.parse`: `--title` passa a ser obrigatório — mesmo padrão de
  `--book-id`/`--file` (`IngestCommand.kt:77-85`): ausente/em branco retorna
  `IngestArgsResult.Error("Argumento obrigatório ausente: --title=<titulo da obra>.")`.
- [ ] Mensagem de "argumento não reconhecido" (`IngestCommand.kt:71-73`): mover `--title=<titulo>`
  para fora dos colchetes de opcional.
- [ ] Atualizar o KDoc de `IngestArgsParser` (`:42-47`) e os exemplos de invocação no KDoc de
  `IngestCommand` (`:152-153`) para refletir `--title` obrigatório.
- [ ] `IngestCommandTest.kt:33-42`: renomear/reescrever o teste "sem title" para esperar
  `IngestArgsResult.Error` com a mensagem exata acima.
- [ ] Novo teste: `--title` ausente é rejeitado (mesmo formato de `IngestCommandTest.kt:113-129`,
  os testes de `--book-id`/`--file` ausentes).
- [ ] Atualizar exemplo de `scripts/dev-ingest.sh` no `CLAUDE.md` (raiz) para incluir `--title=`.
- [ ] Nota datada em `specs/ingestao-pdf/tasks.md:122` apontando que `--title` deixou de ser
  opcional nesta spec (mesmo padrão das notas datadas já usadas nos ADRs 0008/0013).
- [ ] `./gradlew ktlintFormat test` verde dentro de `backend/`.
- [ ] **Ação manual pós-merge (não é código, não vira commit desta task):** operador roda
  `UPDATE book SET title = 'O Livro dos Espíritos' WHERE id = 'o-livro-dos-espiritos';` no Neon de
  produção. Documentar esse passo pendente na entrega da task, não executá-lo como parte do
  commit (é operação em dado de produção, fora do escopo de código versionado).
