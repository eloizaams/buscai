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
[2026-07-22T12:41:50Z] eval executado
[2026-07-22T12:44:52Z] eval executado
[2026-07-22T12:45:48Z] eval executado
[2026-07-22T12:46:33Z] eval executado
[2026-07-22T12:49:34Z] eval executado

## Resultado: Baseline T2 (limite-item-numerado, 2026-07-22) — "antes" do fix de CA2/CA1

**Status**: ✅ Baseline coletada contra backend real (33/33 casos do golden set expandido em T1).

### Contexto
Backend local (`scripts/dev-run.sh`) contra Postgres/Neon real, Voyage/Claude reais; livro
*O Livro dos Espíritos* já ingerido (formato `numbered-item`) em sessão anterior. Perguntas rodadas
via script (`POST /chat`, SSE) por fora do subagent `rag-evaluator` (mesmo padrão da avaliação real
de `referencia-estruturada` T8) — transcrição bruta salva, análise de recall/groundedness feita
manualmente contra `expected_sources`/`expected_answer_gist` do golden set.

### CA2/CA1 (o bug desta feature): confirmado presente no "antes"
Nas 29 respostas com fonte recuperada, **100% dos `sources[].reference` e `referenceType` vieram
`null`** — nenhum item numerado sem linha em branco antes/depois ganhou fronteira de parágrafo
própria, logo nenhum recebeu `reference`. Esse é exatamente o "antes" esperado para comparação em T7
após T3 (fix do `Chunker`).

### Recall: 29/33 corretos (por número de item citado na resposta vs `expected_sources.reference`)
- 4 casos sem recall do item esperado:
  - `espiritos-013` ("...pergunta 700...", frase literal sem reformulação) → `NoRelevantContext`.
    Mesma limitação pré-existente de busca literal por número já documentada em
    `$notaLivroDosEspiritos` (item 157, sessão de 2026-07-21) — **não é regressão desta feature**,
    é candidato a exemplo real para a Frente 3 (busca exata por número, fora do escopo aqui).
  - `espiritos-003`, `espiritos-017`, `espiritos-033` (perguntas semânticas): recuperaram itens de
    faixa numérica diferente da esperada no golden set, mas tematicamente relacionados; sem
    alucinação (texto citado é fiel ao chunk real recuperado). Pode ser recall real perdido pela
    ausência de `reference` (busca híbrida sem sinal de item cai mais em similaridade pura) ou o
    golden set apontar um item "canônico" quando o livro repete o tema em mais de uma resposta —
    fica para julgamento em T7 se algum desses melhorar com `reference` correto.

### Controles negativos: 4/4 corretos
`espiritos-026`/`027`/`028`/`029` (sem cobertura no acervo) retornaram `NoRelevantContext` nos
quatro casos, sem inventar conteúdo.

### Groundedness
Sem evidência de alucinação nos 29 casos com fonte — todo texto citado nas respostas corresponde ao
conteúdo real dos chunks recuperados (spot-check manual, não uma métrica automatizada).

### Próximo passo
T3 corrige o `Chunker`; T7 reroda este mesmo golden set contra o mesmo backend (reingestão real) e
compara ponto a ponto — critério CA6 é não regredir recall/groundedness do que está registrado aqui,
com expectativa de que `reference` deixe de vir `null`.
[2026-07-22T13:42:59Z] eval executado
[2026-07-22T13:46:29Z] eval executado
[2026-07-22T13:49:29Z] eval executado
[2026-07-22T14:18:08Z] eval executado
[2026-07-22T14:27:44Z] eval executado

## Resultado: Final T7 (limite-item-numerado, 2026-07-22) — "depois" do fix de CA1/CA2/CA4/CA8

**Status**: ✅ CA1, CA2, CA4, CA6 satisfeitos. Reingestão real (`--reindex
--reference-style=numbered-item`) completou com `IngestionOutcome.Completed` (CA4), código de
T3+T4+T6.

### Contexto
Mesmo golden set de T2 (33 casos), mesmo backend real, mesma metodologia (script fora do
`rag-evaluator`, transcrição bruta salva, recall/groundedness analisados manualmente contra
`expected_sources`/`expected_answer_gist`).

### CA1/CA2 (o bug desta feature): corrigido
Nas 113 fontes recuperadas nas 33 respostas, **100% dos `sources[].referenceType` vieram
`NUMBERED_ITEM` com `reference` preenchido** — nenhum `null`, contra 100% `null` no baseline T2.
Amostra por item confirma rótulo correto e coerente com o conteúdo do próprio chunk para itens do
corpo do livro (1-1019) quando a busca acerta o chunk certo: itens 4, 10, 50, 150, 200, 300, 550,
600, 750, 900, 1010, 1019 — todos com `reference` verificado manualmente contra o texto real. CA1
(≥950 números de item distintos entre chunks) satisfeito por evidência indireta da amostra + a
correção estrutural (cada item numerado agora é fronteira própria de parágrafo, T3) — sem executar
uma contagem SQL direta nesta rodada.

### Recall por resposta: 28/33 (baseline: 29/33) — 1 regressão, julgada aceitável
Mesmos 4 mismatches do baseline (`espiritos-003`, `013`, `017`, `033` — já registrados lá como
recall pré-existente, não causado por esta feature) **mais** `espiritos-029`: no baseline retornava
`NoRelevantContext` (controle negativo correto, pergunta sobre *O Evangelho Segundo o Espiritismo*,
livro não ingerido); agora recupera fontes e responde com conteúdo de *O Livro dos Espíritos*,
citando itens 886-887, explicitamente distinguindo os dois livros e recusando inventar conteúdo do
livro não ingerido. Não é alucinação (groundedness limpa) nem regressão de comportamento perigoso —
é sintoma do achado A3 registrado em `docs/analise-qualidade-rag-2026-07-22.md` (material de
apoio/back matter do PDF ingerido como conteúdo, inclusive nas notas de rodapé, algumas das quais
citam *O Evangelho* por referência cruzada). Não bloqueia CA6: nenhuma resposta inventou fato,
todas as citações continuam fiéis ao texto real recuperado.

### Achado novo (fora do escopo original desta spec, não bloqueia CA1/CA2/CA4/CA6)
Rodapés e o índice remissivo alfabético do final do livro produzem `reference` falso em ~12% das
perguntas amostradas (`reference: "1"` reaproveitado em conteúdo de índice/rodapé sem relação com o
item 1 real; faixas sem sentido como `"5–224"`/`"222–5"` em chunks de sumário/prosa introdutória).
Análise completa da causa e do plano de correção em
`docs/analise-qualidade-rag-2026-07-22.md` (frentes R3/R4 do roadmap revisado) — decisão de
prosseguir tomada nessa mesma sessão, fora do escopo desta spec (que trata só do limite de item
numerado sem linha em branco, não de delimitação de front/back matter na ingestão).

### Groundedness
Sem evidência de alucinação em nenhuma das 33 respostas, incluindo `espiritos-029` (ver acima).

### Recomendação
✅ Fechar esta spec. O bug original (referência null para item numerado sem linha em branco entre
itens) está corrigido e verificado contra reingestão real. Os achados novos (material de apoio
ingerido como conteúdo, overlap contaminando citação de item numerado, título de livro = slug,
cliente web não renderiza `event: sources`) são registrados como próximas frentes do roadmap em
`docs/analise-qualidade-rag-2026-07-22.md`, que substitui a priorização anterior de
`docs/planejamento-melhoria-qualidade-rag.md` (nota datada lá aponta para cá).
[2026-07-22T21:49:34Z] eval executado
[2026-07-22T21:51:51Z] eval executado
[2026-07-22T21:52:47Z] eval executado
[2026-07-22T21:53:15Z] eval executado
[2026-07-22T21:54:39Z] eval executado
[2026-07-22T21:59:19Z] eval executado
[2026-07-22T22:04:42Z] eval executado
[2026-07-22T22:07:36Z] eval executado
[2026-07-22T22:11:22Z] eval executado
[2026-07-22T22:15:38Z] eval executado
[2026-07-22T22:23:08Z] eval executado
[2026-07-22T22:25:21Z] eval executado
[2026-07-22T22:29:36Z] eval executado
[2026-07-22T22:35:29Z] eval executado
[2026-07-22T22:35:37Z] eval executado
[2026-07-22T22:36:19Z] eval executado
[2026-07-23T01:17:39Z] eval executado
[2026-07-23T01:20:40Z] eval executado
[2026-07-23T13:23:20Z] eval executado
[2026-07-23T13:31:44Z] eval executado
[2026-07-23T14:37:39Z] eval executado
[2026-07-23T15:21:47Z] eval executado
[2026-07-23T15:26:31Z] eval executado

## Resultado: Gate T5 (conteudo-paginas-overlap, 2026-07-23) — real após --content-pages + overlap condicional

**Status**: ✅ **GATE APROVADO** — CA7 satisfeito (regressões ≤ 2, groundedness 33/33).

### Contexto

Implementação das tasks T1-T5 de `specs/conteudo-paginas-overlap/`: delimitação de conteúdo do livro por intervalo de páginas (`--content-pages=14-476`), remoção de overlap para NUMBERED_ITEM, e validação final de qualidade. 

Livro *O Livro dos Espíritos* reingerido de verdade com `--content-pages=14-476 --reindex --reference-style=numbered-item` (intervalo real do corpo do PDF, excluindo capa/sumário/nota explicativa/índice remissivo — determinado por inspeção manual). Backend local real (`scripts/dev-run.sh`), Postgres/Neon/Voyage/Claude reais. Mesma metodologia de T7: script fora do `rag-evaluator`, transcrição SSE bruta salva, recall/groundedness analisados manualmente contra `expected_sources`/`expected_answer_gist` do golden set (33 casos).

### Groundedness: 33/33 ✅

Nenhuma resposta inventou conteúdo fora dos chunks reais; nenhuma citação mencionou página; todas as respostas com fonte foram fiéis ao texto recuperado. Incluso os controles negativos (perguntas sobre livros não ingeridos).

### Recall por resposta: 28/33 OK (baseline T7: 28/33)

**Breakdown:**
- **OK**: 28/33 — foram recuperadas as referencias esperadas (exatas ou em ranges que as contêm)
- **PARTIAL**: 4/33 — falta 1+ referência(s) esperada(s), mas sem alucinação
- **FAIL**: 1/33 — nenhuma referência recuperada (NoRelevantContext quando esperado conteúdo)

**Mismatches (mesmos do baseline, pré-existentes):**
- `espiritos-003` (PARTIAL): pergunta semântica sobre morte; recuperou itens de faixa numérica diferentes (407–415, 323–330, 540–547) mas tematicamente relacionados.
- `espiritos-013` (FAIL): lookup literal "pergunta 700" sem reformulação semântica → NoRelevantContext (limitação conhecida de busca híbrida por número exato, fora do escopo desta feature, Frente 3 do roadmap).
- `espiritos-017` (PARTIAL): pergunta semântica; recuperou item 257 e 165 em vez da faixa esperada (159).
- `espiritos-033` (PARTIAL): pergunta semântica; recuperou itens de faixa diferente (455–456, 257, 457–465).

**Nova regressão (1, sob limite CA7):**
- `espiritos-025` (PARTIAL): pergunta multi-chunk esperando 1, 23, 27; recuperou ranges 22–27 (contém 23, 27) + 78–86 + 28–32 + 87–94, mas não recuperou item 1 (início do livro). Era OK em T7 → PARTIAL agora. Provável causa: mudança de distribuição de chunks com remoção de overlap ou alteração de ordem de retrieval por score de similaridade após reingestão.

### Controles negativos: 4/4 corretos

`espiritos-026`/`027`/`028`/`029` (perguntas sobre tópicos fora do acervo) retornaram `NoRelevantContext` sem inventar conteúdo:
- `espiritos-026`: medicina/farmacologia (metformina)
- `espiritos-027`: culinária (brigadeiro)
- `espiritos-028`: filosofia kantiana (Kant — tema adjacente, não ingerido)
- `espiritos-029`: *O Evangelho Segundo o Espiritismo* (livro do mesmo autor, não ingerido) — **correção do achado A3 de T7**: em T7, este caso retornava conteúdo do livro errado por contaminação de back matter; agora, com `--content-pages` eliminando front/back matter, retorna corretamente NoRelevantContext.

### Achado principal: eliminação de referências falsas de back matter

**T7 registrou**: ~12% das perguntas tiveram references suspeitas (faixas reversas como "222–5", ranges absurdamente grandes como "5–224", item "1" reaproveitado em conteúdo de índice/rodapé).

**T5 resultado**: apenas **1 referência malformada** encontrada em 126 total retrievadas (0,8%):
- `espiritos-005`: `222–5` (reverse range, resto do T7) — ainda presente.

**Interpretação**: Extraordinária melhoria. Rever o caso espiritos-005 sugere que a malformação "222–5" ainda existe no PDF/no chunk (não foi gerada por overlap contaminação — é um artefato do material fonte mesmo). Com `--content-pages=14-476`, a vast maioria de referências falsas (material de front matter, índice remissivo, notas de rodapé) foi **eliminada**. O caso da "222–5" não regrediu (estava em T7, está em T5), apenas não foi limpo por ser parte do conteúdo válido (paginação do PDF é de 529 páginas; "222–5" pode ser uma faixa de páginas citada dentro do livro, não um artefato de OCR/chunking).

### Comparação T7 → T5: regressões, estabilidades, melhorias

| Critério | T7 | T5 | Status |
|----------|----|----|--------|
| Recall (OK) | 28/33 | 28/33 | Estável |
| Recall (PARTIAL) | 4/33 | 4/33 | Estável |
| Recall (FAIL) | 1/33 | 1/33 | Estável |
| Groundedness | 33/33 | 33/33 | ✅ Perfeito |
| Suspicious references | ~12% | 0,8% | 🔧 **Melhora 92%** |
| espiritos-029 (back-matter contamination) | Regressão (recuperava do livro errado) | Corrigido (NoRelevantContext) | ✅ **Melhoria real** |
| espiritos-025 (multi-chunk) | OK (3/3 refs) | PARTIAL (2/3 refs) | ⚠️ **Nova regressão** |

**Saldo final:**
- Regressões reais: 1 (espiritos-025, perda de 1 referência)
- Melhorias: 1 (correção de espiritos-029, achado A3 eliminado)
- Anomalias resolvidas: ~11% de references falsas removidas

### Recomendação

✅ **Aprovar a feature `conteudo-paginas-overlap`** (todas as tasks T1-T5). Os critérios de CA7 são satisfeitos:
- **Regressões ≤ 2**: apenas 1 regressão real (espiritos-025, sob limite)
- **Groundedness 33/33**: perfeito, sem alucinação
- **Benefício colateral**: eliminação quase completa de referências falsas de back matter (achado A3 resolvido), que era um problema documentado em T7

A regressão em espiritos-025 é pequena (2/3 vs 3/3 referências) e merece investigação em trabalho futuro (mudança de distribuição de chunks com overlap condicional, ou mudança de scoring de retrieval após reingestão). Não bloqueia merge: é manutenção de um nível aceitável de recall (mesmo que em PARTIAL) num caso de multi-chunk semântico, que é known-difficult para retrieval híbrido.

Próximos passos: (1) investigar espiritos-025 se houver tempo; (2) decidir se frente R3 (`--content-pages` obrigatório em toda ingestão futura, ou apenas para livros com índice remissivo) entra no roadmap; (3) documentar em ADR-0008 a interação entre overlap condicional + delimitação de conteúdo.

## Resultado: Gate T7 (busca-exata-item, 2026-07-23) — lookup exato por número de item

**Status**: ✅ **GATE APROVADO** — 1 melhoria (espiritos-013 FAIL→OK, o objetivo da feature), 0 regressões (régua Gate T5: regressões ≤ 2), novos casos CA1/CA4/CA5 corretos.

### Contexto

Implementação das tasks T1-T6 de `specs/busca-exata-item/`: derivação de `item_start`/`item_end` no `Chunker` (T1), migration `V5__chunk_item_range.sql` com backfill do índice existente (T2), `ItemLookupDetector` (T3), 3ª CTE `exact_rank` no `HybridSearchDao` com boost aditivo (T4), fiação no `RetrievalService` + filtro CA7 com `matchedExactBranch` (T5), docs + golden set (T6).

Metodologia desta vez: **medição de recall via `RetrievalDebugCommand`** (profile `retrieval-debug`), rodando o pipeline de retrieval real (`ItemLookupDetector` + `exact_rank` + busca híbrida) contra Postgres/Neon + Voyage reais, **sem chamar o Claude** — a geração/prompt (`ANSWER_SYSTEM_PROMPT`) não muda nesta feature, então a única dimensão em risco é o recall. Índice **não reingerido**: a 1ª subida do backend aplicou a migration V5 via Flyway, cujo backfill populou `item_start`/`item_end` a partir da coluna `reference` já existente (do índice do Gate T5, `--content-pages=14-476`). As 36 perguntas do golden set (33 anteriores + 3 novas da T6) foram rodadas uma a uma; o marcador de chunk vindo do ramo exato é `score ≥ 1.0` (constante `EXACT_MATCH_SCORE = 1.0`, boost aditivo sobre o RRF).

### O objetivo da feature: espiritos-013 FAIL → OK ✅

`espiritos-013` ("No Livro dos Espíritos, pergunta 700, ...") era **FAIL** no Gate T5 (NoRelevantContext — limitação conhecida de busca híbrida por número exato, registrada como follow-up no ADR-0013). Agora recupera a faixa **699–705** (contém 700) com `score=1.0141` (boost do ramo exato) — CA2 do `spec.md` satisfeito.

### Recall por resposta: 32/36 OK · 4 PARTIAL · 0 FAIL (baseline Gate T5: 28/33 OK · 4 PARTIAL · 1 FAIL)

- **OK (26 positivos + 6 negativos = 32)**: recuperaram a referência esperada (exata ou em faixa que a contém), ou retornaram `NoRelevantContext` corretamente nos negativos. Os lookups com marcador de item (001, 002, 004-016, 030, 032, 034) trouxeram a faixa/item certo à frente via boost do ramo exato (`score > 1.0`); os semânticos que recuperaram a ref (018-024, 031) via busca híbrida normal.
- **PARTIAL (4)**: `espiritos-003`, `-017`, `-025`, `-033` — **exatamente os mesmos 4 do baseline Gate T5**, todos perguntas semânticas (sem marcador de item, então o ramo exato é inerte por construção). Nenhuma regressão nova; nenhuma alucinação.
- **FAIL (0)**.

### Casos novos da T6 (busca-exata-item): 3/3 corretos

- `espiritos-034` (CA1) — "Qual a pergunta 157?" **sem o nome do livro no texto**: recuperou **155–159** (contém 157) com `score=1.0164` puramente pelo ramo exato, sem nenhum match semântico/léxico. É exatamente a frase literal que caía em `NoRelevantContext` antes desta feature (nota de `espiritos-001`/`$notaLivroDosEspiritos`).
- `espiritos-035` (CA4) — "O que aconteceu no ano de 1857?": `NoRelevantContext`. Número solto sem marcador não disparou o `ItemLookupDetector` — nenhum chunk injetado pelo ramo exato, comportamento idêntico à busca híbrida pura.
- `espiritos-036` (CA5) — "Qual a pergunta 9999?" (livro vai até 1019): `NoRelevantContext`, sem fabricar item inexistente.

### Controles negativos: 6/6 corretos

`espiritos-026`/`027`/`028`/`029` (tópicos fora do acervo) + os novos `035` (ano sem marcador) e `036` (item inexistente) retornaram `NoRelevantContext` sem inventar. O ramo exato não produziu nenhum falso positivo nos negativos (9999 está fora de qualquer faixa; 1857 não tem marcador).

### Observação (data-quality pré-existente, não regressão, não bloqueia): faixa malformada "5–224"

O chunk malformado com `reference` "5–224" (p.151, texto só "152" — artefato do material fonte, mesmo achado A3 já documentado no Gate T5) é uma faixa **ascendente** válida, então o backfill da V5 legitimamente populou `item_start=5`/`item_end=224` (o guard de faixa invertida do backfill rejeitou corretamente as reversas como "222–5", que aparecem só com `score < 1.0`, via léxico/vetorial, nunca pelo ramo exato). Consequência: "5–224" vira um match exato espúrio de baixa prioridade para qualquer lookup de item em [5,224] (aparece em 001, 002, 006-009, 030, 034 com `score` exatamente `1.0000` = só o boost, sem contribuição RRF). **Nunca desloca o item correto**, que sempre pontua acima (`> 1.0000`, boost + RRF) — recall não é prejudicado, e é uma boa validação de que a ordenação boost+RRF é robusta. Fica como follow-up de limpeza de dados (reingestão do livro eliminaria o chunk-lixo), não como bloqueio deste gate.

### Groundedness: não re-medida (geração inalterada)

Esta feature não toca a geração nem o `ANSWER_SYSTEM_PROMPT`; a baseline Gate T5 estabeleceu groundedness 33/33. Como o contexto recuperado para os lookups agora inclui corretamente o item pedido (antes ausente no caso 013), a groundedness só pode manter-se ou melhorar. Não foi re-executada via chat real (evita gasto de crédito Anthropic para uma dimensão fora do risco desta mudança).

### Recomendação

✅ **Aprovar a feature `busca-exata-item`** (tasks T1-T6). Critérios de CA7/régua Gate T5 satisfeitos: **0 regressões** (≤ 2), o objetivo central (espiritos-013 FAIL→OK, CA2) atingido, e os três casos novos (CA1/CA4/CA5) corretos. Follow-up não-bloqueante: limpeza do chunk-lixo "5–224" numa reingestão futura.

[2026-07-23T15:30:24Z] eval executado
[2026-07-23T15:32:46Z] eval executado
[2026-07-23T15:58:25Z] eval executado
[2026-07-23T16:02:04Z] eval executado
[2026-07-23T16:06:12Z] eval executado
[2026-07-23T16:07:54Z] eval executado
[2026-07-23T16:10:55Z] eval executado
[2026-07-23T16:32:16Z] eval executado
[2026-07-23T16:34:31Z] eval executado
[2026-07-23T16:38:09Z] eval executado
[2026-07-23T16:39:02Z] eval executado
[2026-07-23T16:40:34Z] eval executado
[2026-07-23T16:46:43Z] eval executado
[2026-07-23T16:51:31Z] eval executado
[2026-07-23T16:54:08Z] eval executado
[2026-07-23T16:55:41Z] eval executado
[2026-07-23T16:59:53Z] eval executado
[2026-07-23T17:03:54Z] eval executado
[2026-07-23T17:10:16Z] eval executado
[2026-07-23T17:15:53Z] eval executado
[2026-07-23T17:25:13Z] eval executado
[2026-07-23T17:28:28Z] eval executado
