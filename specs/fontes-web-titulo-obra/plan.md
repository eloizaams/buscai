# Plan — Fontes no cliente web + título real da obra

Parecer do `android-architect`: aprovado com ressalvas (sem ADR novo — R1 fica dentro de
ADR-0011/0012, R2 dentro de ADR-0008/0013). Este plano incorpora as ressalvas e resolve as duas
perguntas em aberto do parecer.

## R1 — Lista de fontes no cliente web

Arquivo único: `web/app.js` (sem novo arquivo — projeto é HTML/CSS/JS puro, ADR-0011/0012).

### Contrato consumido (já existe, sem mudança de backend)

`event: sources`, `data:` é um JSON (linha única) de `{ sources: [{ chunkId, bookId, bookTitle,
reference, referenceType, text }] }` (`ChatEvent.kt:27-29`/`SourceItem` `:48-55`), emitido antes
de qualquer `token`, nunca emitido em `NoRelevantContext`.

### Mudanças

1. **Modelo da mensagem** (`web/app.js:354`, `assistantMessage`): ganha dois campos —
   `sources: []` (preenchido pelo evento) e `sourcesExpanded: false` (estado de UI, sobrevive ao
   re-render — ver ponto 3).
2. **`dispatch`** (`web/app.js:277-305`): novo ramo `if (eventName === "sources")` —
   `JSON.parse(data)`, atribui `assistantMessage.sources = parsed.sources`, garante a mensagem em
   `state.messages` (mesmo padrão do ramo `token`/`error`) e chama `renderMessages()`. Fontes
   chegam antes do primeiro token, então a mensagem do assistente nasce na lista já com fontes.
3. **`renderMessages`** (`web/app.js:241-253`): ao montar a bolha de uma mensagem `ASSISTANT` com
   `sources.length > 0`, anexa um `<details>` nativo (`<summary>N fontes</summary>` + `<ul>`, um
   `<li>` por fonte). Resolve a ressalva de estado do arquiteto: em vez de depender do `open` do
   DOM (perdido a cada rebuild, já que `renderMessages` sempre recria tudo), o `details.open` é
   setado a partir de `message.sourcesExpanded` na montagem, e um listener `toggle` no `<details>`
   escreve de volta em `message.sourcesExpanded` — o estado sobrevive ao rebuild de cada `token`
   porque vive no objeto da mensagem, não no DOM.
4. **Rótulo por fonte:** `bookTitle` + rótulo de referência (`CHAPTER` → "Capítulo N",
   `NUMBERED_ITEM` → "Pergunta N", `referenceType` nulo → sem rótulo de referência, só o título —
   ADR-0013 §5/constitution §4) + o `text` do chunk.
5. **Segurança:** todo texto (título, rótulo, trecho) via `textContent`/`document.createElement`
   (padrão já usado em `renderBookList`/`renderMessages` hoje) — nunca `innerHTML`, já que
   `text`/`bookTitle` vêm de conteúdo de livro e podem conter `<`/`>`/`&`.
6. **CA3 (conversa reaberta sem fontes):** sem trabalho extra — `openConversation`
   (`web/app.js:207-210`) monta mensagens só com `{role, content}`, então `sources` fica
   `undefined`/vazio e o `<details>` simplesmente não é renderizado.

### Fora do arquivo `web/app.js`

Nenhuma mudança em `web/index.html`/`web/styles.css` é estritamente necessária (o `<details>`
nativo não exige CSS novo); se o resultado visual ficar pobre, uma task pequena de estilo mínimo
pode ser adicionada — decisão do `kotlin-implementer` na hora, não bloqueia o plano.

### Verificação

Sem harness de teste automatizado para `web/` (projeto não tem JS test runner — fora de escopo
introduzir um só para isso). CA1/CA2/CA3/CA4 verificados manualmente: `scripts/dev-run.sh` +
pergunta real no chat local, observando (a) lista recolhida aparece com resposta fundamentada,
(b) expandir/recolher funciona e sobrevive ao streaming, (c) pergunta sem contexto não mostra
lista, (d) reabrir conversa antiga não mostra lista nem quebra o render.

## R2 — Título real da obra

Arquivos: `backend/src/main/kotlin/com/buscai/backend/ingestion/cli/IngestCommand.kt`,
`backend/src/test/kotlin/com/buscai/backend/ingestion/cli/IngestCommandTest.kt`.

### Mudanças de código

1. **`IngestArgsParser.parse`** (`IngestCommand.kt:92` hoje: `options[TITLE_KEY]?.takeIf {
   it.isNotBlank() } ?: bookId`): título passa a ser obrigatório, mesmo padrão de `--book-id`
   (`:77-80`)/`--file` (`:82-85`):
   ```
   val title = options[TITLE_KEY]
   if (title.isNullOrBlank()) {
       return IngestArgsResult.Error("Argumento obrigatório ausente: --title=<titulo da obra>.")
   }
   ```
2. **Mensagem de uso** (`IngestCommand.kt:71-73`, ramo "Argumento não reconhecido"): mover
   `--title=<titulo>` para fora dos colchetes opcionais, já que deixa de ser opcional.
3. **KDoc de `IngestArgsParser`** (`IngestCommand.kt:42-47`): remover a frase "quando ausente,
   usa o próprio `book-id`..." e documentar que `--title` é obrigatório desde esta mudança.
4. **KDoc de `IngestCommand`** (exemplos de invocação, `:152-153`): incluir `--title=` nos
   exemplos, senão o operador copia um comando que agora falha.
5. **`CLAUDE.md`** (raiz): exemplo de `scripts/dev-ingest.sh` já usa
   `--book-id=dom-casmurro --file=...` sem `--title` — adicionar `--title=` ao exemplo.
6. **`specs/ingestao-pdf/tasks.md:122`** ("`--title` opcional"): nota datada apontando que a
   obrigatoriedade foi decidida nesta spec (mesmo padrão já usado nos ADRs 0008/0013 — não reabre
   a task antiga, só evita doc contraditória).

### Testes (constitution §4 — função alterada nasce/atualiza teste na mesma task)

- `IngestCommandTest.kt:33-42` (`parse aceita book-id e file obrigatorios, sem reindex e sem
  title`): passa a esperar `IngestArgsResult.Error` com a mensagem exata do item 1 acima —
  renomear o teste para refletir o novo comportamento.
- Novo caso: `parse rejeita quando --title está ausente` (mesmo formato dos testes de
  `--book-id`/`--file` ausentes, `IngestCommandTest.kt:113-129`), com o texto exato da mensagem.
- `IngestCommandTest.kt:47-57` (`--reindex e --title explicitos`) já passa `--title=Dom
  Casmurro` — não muda.

### Correção do dado já em produção (ação de operação, fora do código desta spec)

Uma vez feito o merge, rodar manualmente (fora da CLI, sem task de código):
```sql
UPDATE book SET title = 'O Livro dos Espíritos' WHERE id = 'o-livro-dos-espiritos';
```
Coerente com ADR-0008 (operador age diretamente quando a correção é pontual) e com o fato,
confirmado em `IngestionService.kt:229-240`, de que `title` só é gravado na criação do `Book` —
reingestão não atualiza título de livro existente (fora de escopo, YAGNI, conforme spec.md). O
`UPDATE` se propaga sozinho para `/books` e para `SourceItem.bookTitle` (ambos leem a linha
`Book` em tempo de consulta — `RetrievalService.kt:89`), sem exigir deploy.

### Verificação

`./gradlew ktlintFormat test` (dentro de `backend/`) verde, incluindo os testes novos/alterados
de `IngestArgsParser`. CA5 (título correto em produção) é verificado manualmente após o `UPDATE`,
via `scripts/dev-run.sh` local apontando pro Neon de produção ou observação direta do chat em
produção — não é um teste automatizado, é checagem operacional pontual.

## Ordem de tasks (ver tasks.md)

R1 e R2 são independentes entre si (arquivos diferentes, sem dependência de dado) — podem ser
implementadas em qualquer ordem ou em paralelo. Cada uma vira uma task só (couberam inteiras numa
sessão de `kotlin-implementer`, conforme parecer do arquiteto não apontar necessidade de quebrar
mais).
