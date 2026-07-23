# Plan: Delimitação de conteúdo e remoção de overlap (R3+R4)

## Parecer do Arquiteto (resumo)

**Veredito:** aprovado, condicionado a três ajustes já incorporados nesta spec: (1) CA7 exige diff
caso-a-caso vs. T7 em vez de teto agregado — `espiritos-029` revertendo a `NoRelevantContext` é
melhora, não regressão; (2) CA4 registra que `--content-pages` é inerte sem `--reindex` (o
intervalo não entra na chave de gatilho do ADR-0008); (3) notas datadas em ADR-0002/0008
registrando que o overlap deixa de ser universal (vira condicional ao `referenceType`) e a
capacidade nova de delimitação — mesma estratégia de emenda que o ADR-0013 usou para o piso
mínimo de tokens.

**Pré-requisitos confirmados** (verificados no histórico em 2026-07-22):
- `specs/limite-item-numerado/` mergeada — PR #17, merge `6d4ca6e`, incluindo o fix do
  `--reindex` (`3568782`) e o skip de validação de overlap para item numerado (`856574d`).
- Gate rag-evaluator (T5) é **operador-run**: exige backend real + Postgres/pgvector +
  credenciais Voyage/Anthropic (`.env` na raiz, `scripts/dev-run.sh`/`dev-ingest.sh`) —
  indisponível em sandbox; a task correspondente marca isso explicitamente.

## R3 — Delimitação de conteúdo (`--content-pages`)

### Arquivos

- `backend/src/main/kotlin/com/buscai/backend/ingestion/cli/IngestCommand.kt` — `IngestArgs`,
  `IngestArgsParser`, `IngestionOutcomeFormatter`, `IngestCommand.run` (tudo neste arquivo hoje)
- `backend/src/main/kotlin/com/buscai/backend/ingestion/IngestionService.kt` — assinatura de
  `ingest`, validação contra `pageCount`, laço de extração em `extractCleanAndChunk`
- `backend/src/test/kotlin/com/buscai/backend/ingestion/cli/IngestCommandTest.kt`
- `backend/src/test/kotlin/com/buscai/backend/ingestion/IngestionServiceTest.kt`

### Parsing (`IngestArgsParser`)

O parser **não abre o PDF** (decisão documentada no KDoc: erro de parsing nunca vira exceção crua
de `File`/PDFBox) — então aqui só se valida o **formato**; o limite contra `pageCount` fica no
`IngestionService`. Seguir o padrão existente à risca: nada de exceção, tudo via
`IngestArgsResult.Error` com mensagem no formato das atuais.

```kotlin
// IngestArgs ganha o campo (tipo idiomático: IntRange, 1-indexed, inclusivo):
data class IngestArgs(
    val bookId: String,
    val file: File,
    val title: String,
    val reindex: Boolean,
    val referenceType: ReferenceType? = null,
    val contentPages: IntRange? = null,
)

// IngestArgsParser: nova chave + validação de formato (após --reference-style):
private const val CONTENT_PAGES_KEY = "content-pages"

val contentPagesRaw = options[CONTENT_PAGES_KEY]
val contentPages: IntRange? =
    when {
        contentPagesRaw == null -> null
        else -> {
            val match = Regex("""^(\d+)-(\d+)$""").matchEntire(contentPagesRaw)
                ?: return IngestArgsResult.Error(
                    "Valor inválido para --content-pages: '$contentPagesRaw'. " +
                        "Use '<início>-<fim>' (ex.: 15-280).",
                )
            val (start, end) = match.destructured.toList().map { it.toInt() }
            if (start < 1 || end < start) {
                return IngestArgsResult.Error(
                    "Intervalo inválido para --content-pages: '$contentPagesRaw'. " +
                        "Use início >= 1 e fim >= início.",
                )
            }
            start..end
        }
    }
```

Também atualizar a mensagem de "Argumento não reconhecido" (usage) e o KDoc do parser para
listar `[--content-pages=<início>-<fim>]`.

### `IngestionService`

- `ingest(...)` recebe parâmetros nomeados hoje (`bookId`, `title`, `file`, `reindex`,
  `referenceType`) — adicionar `contentPages: IntRange? = null` e repassar do `IngestCommand.run`.
- **Validação contra o documento** (logo após `pdfTextExtractor.pageCount(file)`): se
  `contentPages.last > pageCount`, retornar `IngestionOutcome.Failed` com razão descritiva
  ("Intervalo --content-pages (15-999) excede o total de páginas do documento (410)."), antes de
  criar `BookVersion`. Não confiar no `require` de `PdfTextExtractor.extractRange` para isso —
  ele estouraria exceção crua.
- **Filtro por extração restrita, não pós-filtro**: em `extractCleanAndChunk`, em vez de extrair
  o PDF inteiro e descartar páginas depois, restringir o próprio laço de lotes ao intervalo
  (`var start = contentPages?.first ?: 1`; teto = `min(pageCount, contentPages?.last ?: pageCount)`).
  Ganho direto: front/back matter (potencialmente dezenas de páginas de índice remissivo) nem é
  extraído/limpo.
- **Consequência deliberada**: a detecção de PDF escaneado (ADR-0008, limiar de 90% de páginas
  sem texto) passa a ser medida **só sobre as páginas do intervalo** — mais fiel, capas/imagens
  do front matter deixam de contar. Registrar no KDoc.
- Log INFO ao aplicar intervalo: páginas incluídas vs. total (sanity-check barato para o
  operador, sugestão do parecer).

### CA4 — `--reindex` e mensagem de skip (sem migration)

Decisão: **nenhuma persistência nova** do intervalo (YAGNI — detectar "intervalo mudou" exigiria
migration em `book_version`; o comportamento correto já emerge da chave de gatilho existente:
mesmo arquivo + intervalo novo ⇒ `Skipped`). O ajuste é só de mensagem, em
`IngestionOutcomeFormatter`:

```
Skipped: "Livro 'x' já ingerido (versão N) — nada a fazer. Use --reindex para reprocessar
          (ex.: para aplicar outro --content-pages)."
```

`ReindexRequired` já orienta `--reindex` e não muda.

## R4 — Remoção de overlap para `NUMBERED_ITEM`

### Arquivos

- `backend/src/main/kotlin/com/buscai/backend/ingestion/chunking/Chunker.kt`
- `backend/src/test/kotlin/com/buscai/backend/ingestion/chunking/ChunkerTest.kt`
- `ChunkValidator` **não muda** — o skip da checagem de overlap para `NUMBERED_ITEM` já existe
  (`856574d`, ADR-0008 nota 2026-07-22) e permanece coerente com overlap ausente.

### Mudança (código real, `Chunker.chunk`, linhas 160–178)

Hoje o laço de montagem prefixa overlap incondicionalmente:

```kotlin
val overlapText = previousOwnText?.let { lastTokensSubstring(it, overlapTargetTokens(previousOwnTokenCount)) }
val fullText = if (overlapText.isNullOrEmpty()) ownText else "$overlapText\n\n$ownText"
```

Passa a ser condicional ao estilo:

```kotlin
val overlapText =
    if (referenceType == ReferenceType.NUMBERED_ITEM) {
        null
    } else {
        previousOwnText?.let { lastTokensSubstring(it, overlapTargetTokens(previousOwnTokenCount)) }
    }
```

Consequências (documentar no KDoc da classe, que hoje descreve o overlap como universal):

- Para `NUMBERED_ITEM`, `text == conteúdo próprio` — `reference` (item único ou faixa) descreve
  exatamente o que está no texto (CA5).
- `page`/`charOffset` já apontam para o início do conteúdo próprio — sem mudança.
- `MAX_OWN_CONTENT_TOKENS` (695, dimensionado para deixar folga de overlap dentro do teto de 800)
  fica **deliberadamente inalterado**: chunks `NUMBERED_ITEM` passam a ter teto efetivo ~695
  tokens em vez de ~800. Aceitável — recalibrar o teto por estilo seria tuning sem evidência de
  necessidade (R7 do roadmap cobre calibração empírica).

## Notas de ADR (entram no mesmo PR, task T4)

- **ADR-0002** — nota datada 2026-07-22 (`specs/conteudo-paginas-overlap/`): (a) overlap de
  10–20% deixa de ser universal — condicional ao `referenceType` (`CHAPTER` mantém; `NUMBERED_ITEM`
  sem overlap no texto, corte é sempre fronteira deliberada de item, ADR-0013); (b) ingestão ganha
  delimitação de conteúdo por intervalo de páginas fornecido pelo operador (`--content-pages`),
  coerente com o modelo de responsabilidade do operador do ADR-0008.
- **ADR-0008** — complementar a nota de 2026-07-22: `--content-pages` não entra na chave de
  gatilho `(bookId, fileHash, embeddingModel)`; aplicar intervalo novo ao mesmo arquivo exige
  `--reindex` (swap atômico cobre o versionamento).
- **ADR-0013** — sem nota nova: a remoção física do overlap é consequência direta de "item
  numerado é a unidade atômica de chunk" já registrado lá.

## Gate rag-evaluator (T5 — operador-run, diff caso-a-caso)

Régua: transcrição bruta de T7 em `specs/eval/history.md` (recall 28/33, groundedness 33/33).

1. Reingerir "O Livro dos Espíritos" com `scripts/dev-ingest.sh` +
   `--reference-style=numbered-item --content-pages=<intervalo do corpo> --reindex`
   (operador determina o intervalo inspecionando o PDF).
2. Subir backend (`scripts/dev-run.sh`), rodar os 33 casos do golden set via `POST /chat` —
   mesmo roteiro manual de T2/T7 (ver blocos correspondentes em `history.md`).
3. Classificar **cada caso** vs. T7: melhora / regressão real / neutro. Esperados:
   `espiritos-029` reverte a `NoRelevantContext` (**melhora**); casos de lookup por item
   (`espiritos-001` etc.) mantêm ou melhoram a correspondência texto↔referência.
4. Registrar em `history.md` no formato padrão (timestamp ISO, contexto, diff por caso, resumo,
   groundedness, recomendação).
5. **Aprovação:** regressões reais ≤ 2 (excluindo rejeição de ruído, que é melhora) e
   groundedness 33/33. Acima disso: contingente, investigar antes do merge.

## Ordem de Tasks (detalhe em `tasks.md`)

- **T1** — parsing/validação de `--content-pages` (`IngestArgsParser`/`IngestArgs`/pass-through
  no `IngestCommand`) + testes.
- **T2** — `IngestionService`: assinatura, validação vs. `pageCount`, extração restrita ao
  intervalo, mensagem de `Skipped` + testes.
- **T3** — `Chunker`: overlap condicional ao `referenceType` + testes.
- **T4** — notas de ADR (0002/0008) + `ktlintFormat` + suíte completa do módulo.
- **T5** — gate rag-evaluator (operador-run, infra real).
- **T6** — `/pr` (review da branch, push, PR); merge é do usuário.

T1→T2 são dependentes; T3 é independente de T1/T2 (pode paralelizar, mas commits separados —
um commit por task). Cada task de código nasce com seus testes (constitution §4).

## Checklist de Merge

- [ ] spec.md/plan.md/tasks.md revisados pelo usuário.
- [ ] CI verde (dentro de `backend/`: `./gradlew build`).
- [ ] `code-reviewer` sem itens Críticos.
- [ ] Gate rag-evaluator registrado em `history.md` com recomendação ✅.
- [ ] Notas de ADR-0002/0008 no PR.
- [ ] Merge feito pelo usuário (nunca pelo agente).
