# Plan — Limite de item numerado sem linha em branco entre itens + golden set expandido

Depende de `spec.md` (revisado pelo `android-architect` — parecer completo abaixo) e do achado já
aprovado pelo usuário em sessão anterior (CA8: `--reindex` deve forçar reprocessamento mesmo com
`fileHash`/modelo inalterados — mantido deste plan anterior, só renumerado). Amenda ADR-0013 (uso
duplo da regex de abertura de item) e ADR-0008 (duas notas datadas, ver "ADRs a atualizar").

## Parecer do android-architect (resumo)

Aprovado como **bug fix dentro do ADR-0013** (não é decisão de arquitetura nova — o ADR já decidiu
"item numerado é a unidade atômica de chunk"; a implementação de detecção de limite estava
incompleta). Sem ADR novo, só notas datadas. Risco de falso-positivo pontual (listas numeradas
dentro de prosa) aceito, sem exigir validação de monotonicidade (YAGNI/frágil). Achado adicional de
severidade média, incorporado no `spec.md` (CA4) e detalhado abaixo: o `ChunkValidator` nunca
exerceu de verdade a checagem de overlap contra itens atômicos curtos, porque nenhum item era
detectado antes desta correção — risco real de a reingestão falhar.

## Causa raiz e correção

`Chunker.splitIntoParagraphs` (linha 294) só reconhece linha em branco (`PARAGRAPH_BREAK`) como
limite de parágrafo. Para `--reference-style=numbered-item`, isso precisa mudar: uma linha que abre
um item numerado (mesma regex de `ReferenceAnnotator.NUMBERED_ITEM_OPENING_REGEX`) também é
fronteira de parágrafo, mesmo sem linha em branco antes dela.

**Fonte única da regex** (ponto levantado pelo arquiteto): mover `NUMBERED_ITEM_OPENING_REGEX` para
um local compartilhado entre `Chunker.kt` e `ReferenceAnnotator.kt` (mesmo pacote
`ingestion/chunking` — pode ficar como `internal val` em qualquer um dos dois arquivos, referenciado
pelo outro) — nunca duas cópias/variações da mesma regex. Atenção: o uso em `splitIntoParagraphs` é
por linha (`MULTILINE` ou testado linha a linha dentro do laço), enquanto `ReferenceAnnotator` testa
o início do parágrafo inteiro (`text.substringBefore`/`^` sem `MULTILINE`) — comportamento
equivalente depois da correção, porque o parágrafo passa a sempre começar exatamente numa linha de
abertura, mas o *padrão* usado nos dois lugares tem que ser literalmente o mesmo objeto `Regex`.

**`charOffset` ao quebrar no meio de um bloco antes fundido**: `splitIntoParagraphs`/
`addParagraphIfNotBlank` (linha 308) já mantêm disciplina de somar `leadingWhitespace` ao offset
relativo à página. A nova quebra (por linha de abertura de item, não só por linha em branco) precisa
recalcular o offset de cada sub-parágrafo com o mesmo cuidado, ou `page`/`charOffset` (usados por
`RetrievalDebugCommand` e como proveniência) saem errados para chunks depois do primeiro item de um
bloco antes fundido.

**Assinatura**: `chunk()` já recebe `referenceType` (linha 141); `splitIntoParagraphs` (hoje sem
esse parâmetro) passa a recebê-lo, com comportamento inalterado para `null`/`CHAPTER` (protege
CA3 — nenhuma regressão fora de `NUMBERED_ITEM`).

## Achado: `ChunkValidator` de overlap nunca foi exercitado por itens atômicos curtos

`ChunkValidator.measureOverlapRatio` divide o overlap medido pelo `tokenCount` **total** do chunk
anterior (`fullText`, que já inclui o overlap que esse chunk herdou do seu próprio antecessor) —
não pelo `ownTokenCount` (conteúdo próprio, sem overlap herdado). `Chunker.overlapTargetTokens`
calcula o overlap a inserir como `ceil(previousOwnTokenCount * 0.15).coerceAtLeast(1)` — para um
item muito curto (poucos tokens de conteúdo próprio), o piso `coerceAtLeast(1)` mais essa diferença
de denominador pode facilmente estourar `OVERLAP_MAX_RATIO` (0.20). Esse caminho nunca foi
exercitado de verdade: antes desta correção, nenhum item numerado era detectado (tudo virava um
único chunk de prosa fundida, que passa tranquilo no validador); esta correção é a **primeira vez**
que ~1000 itens atômicos, muitos curtos, passam pelo `ChunkValidator` de verdade.

**Não dá para confirmar sem testar** — nem sintético nem contra o livro real ainda rodou o
`ChunkValidator` pós-correção. Por isso, a decisão de resolução é **contingente**, mas quero
resolver isso adiantado (mesmo espírito do CA7 da sessão anterior) para a task não travar no meio.

=== ⚠️ PENDENTE (contingente): ===
**Se o teste sintético de T-fix (item numerado curto, muitos itens consecutivos, ver `tasks.md`)
mostrar que a checagem de overlap do `ChunkValidator` falha para chunks `NUMBERED_ITEM` curtos,
como resolver?**
- **Opção 1 (recomendada)**: pular a checagem de overlap inteira para `referenceType ==
  NUMBERED_ITEM` — mesmo precedente já aberto pelo ADR-0013 para `MIN_CHUNK_TOKENS` (seção 4: "item
  numerado é a unidade atômica de chunk"). Justificativa: overlap existe para dar contexto de
  continuidade quando o corte de parágrafo é arbitrário; quando o corte é sempre uma fronteira de
  item deliberada (ADR-0013), a premissa que motiva o overlap não se aplica da mesma forma. Amenda
  de uma linha ao ADR-0008 (mesmo padrão da nota de `MIN_CHUNK_TOKENS`).
- Opção 2: corrigir `measureOverlapRatio` para dividir pelo conteúdo próprio (`ownTokenCount`), não
  pelo total com overlap herdado — mais "correto" matematicamente, mas mexe em código compartilhado
  por todos os `referenceType` (`CHAPTER`/`null` também), risco de mascarar uma checagem que hoje
  funciona para esses casos; mais invasivo que o problema pede agora.
- Opção 3: não fazer nada e deixar a reingestão falhar caso a caso, ajustando manualmente — rejeitada
  (é exatamente o tipo de descoberta tardia, cara, que o diagnóstico antecipado deveria evitar).

Minha recomendação é a Opção 1, pré-aprovada aqui **só se o teste sintético de T-fix confirmar o
problema** — se o teste passar sem violação, esta seção não se aplica e nada muda no
`ChunkValidator`. Preciso do seu "ok" para essa resolução contingente antes de eu poder fechar
T-fix caso o teste confirme o problema (senão a task para no meio à espera de decisão sua de novo).
===

## Módulo e localização

```
backend/src/main/kotlin/com/buscai/backend/ingestion/chunking/
  ReferenceAnnotator.kt   — TOCADO: NUMBERED_ITEM_OPENING_REGEX vira a fonte única (ou é movida
                            para Chunker.kt — decisão de organização menor, `kotlin-implementer`
                            decide olhando o arquivo).
  Chunker.kt              — TOCADO: splitIntoParagraphs(page, text, referenceType) — nova
                            fronteira de parágrafo por abertura de item quando referenceType ==
                            NUMBERED_ITEM; charOffset recalculado por sub-parágrafo.
  ChunkValidator.kt        — TOCADO só se o "⚠️ PENDENTE (contingente)" acima confirmar o problema
                            (Opção 1: pular checagem de overlap para NUMBERED_ITEM).
backend/src/test/kotlin/com/buscai/backend/ingestion/chunking/
  ChunkerTest.kt (ou equivalente já existente) — NOVO teste: fixture sintética com múltiplos itens
                            numerados curtos consecutivos, sem linha em branco entre eles (reproduz
                            o padrão real do livro) — verifica referência correta por item E (é o
                            teste que resolve o achado acima) roda `ChunkValidator.validate` sobre o
                            resultado para confirmar se overlap estoura ou não.
  ReferenceAnnotatorTest.kt (se existir) — regressão: CHAPTER inalterado.
specs/eval/
  golden-set.json          — TOCADO: expandido de 3 para 30-50 casos (CA7), com `reference` real
                            verificável (numeração 1-1019 confirmada no diagnóstico).
  history.md                — TOCADO: baseline (golden set expandido, código ainda não corrigido) e
                            rodada final pós-fix, mesma convenção de sempre.
docs/adr/
  0008-identidade-e-versionamento-de-livros-ingeridos.md — TOCADO: nota datada (2026-07-21) para
                            CA8 (`--reindex` força reprocessamento mesmo com trigger-key idêntica)
                            e, se aplicável, para a Opção 1 do achado de overlap acima.
  0013-referencia-estruturada-de-chunk-capitulo-ou-item.md — TOCADO: nota datada registrando (a)
                            NUMBERED_ITEM_OPENING_REGEX com uso duplo (detecção + fronteira de
                            parágrafo, fonte única) e (b) a decisão deliberada de não validar
                            monotonicidade da numeração (YAGNI/frágil).
```

## Ordem das tasks (mesma lógica da spec anterior)

Golden set expandido (CA7) e sua linha de base (CA6) vêm antes do fix, para o "antes" existir
também para os casos novos — ver `tasks.md`.

## Quem verifica o conteúdo do golden set expandido

Mesma dependência da spec anterior: `expected_answer_gist`/`expected_sources.reference` de cada
caso novo precisam da sua conferência contra o livro real antes de entrarem como "verificados" —
eu proponho o rascunho a partir do texto já extraído no diagnóstico (tenho acesso direto ao PDF
nesta sessão), você confirma o conteúdo.

## Gates

- Mudança em chunking (`Chunker`/`ReferenceAnnotator`, possivelmente `ChunkValidator`) →
  `rag-evaluator` obrigatório (constitution.md seção 4) — coberto pela ordem de tasks.
- `ktlintFormat` + `./gradlew test` verdes antes de cada task ser considerada concluída.
- Nenhuma mudança em `retrieval/`, `generation/` ou no schema do Postgres.

## Modelo recomendado

Sonnet para todas as tasks — a lógica de fronteira de parágrafo e o teste sintético são bem
delimitados pelo diagnóstico já feito; nenhuma task tem a ambiguidade que justificaria Opus.
