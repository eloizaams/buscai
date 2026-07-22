# Planejamento — melhoria de qualidade do RAG (pós-eval real de 2026-07-21)

> **Nota (2026-07-22, `specs/limite-item-numerado/` T7 fechado):** este roadmap foi revisado após
> a reingestão real com `reference` corrigido — a Frente 1 (palavras coladas) foi **resolvida** sem
> spec dedicada (o PDF da reingestão de 2026-07-22 extraiu limpo; zero ocorrência do defeito nas
> 113 fontes analisadas). Surgiram achados novos (front/back matter ingerido como conteúdo, overlap
> contaminando citação de item numerado, título de livro = slug, cliente web não renderiza
> `event: sources`) que repriorizam as frentes abaixo. Diagnóstico completo e plano atualizado em
> `docs/analise-qualidade-rag-2026-07-22.md` — **esse documento é a referência vigente**; o
> conteúdo original abaixo fica como histórico do diagnóstico anterior.

Roadmap das frentes de melhoria identificadas após a primeira avaliação real do pipeline
(ingestão de *O Livro dos Espíritos* com `--reference-style=numbered-item`, perguntas reais via
`web/` contra o backend real — resultado completo em `specs/eval/history.md`, bloco "avaliação real
do golden set", e PR #12). Este documento é o registro durável do que precisa ser feito e por quê —
cada frente nasce via `/spec-feature` quando priorizada, seguindo o fluxo SDD do `CLAUDE.md`
(spec.md → parecer do `android-architect` → plan.md → tasks.md), e **nada aqui é implementação**:
escopos e hipóteses abaixo são ponto de partida da spec, não decisão fechada.

## Evidência que motiva este roadmap

Da sessão de eval real (2026-07-21, respostas coladas pelo usuário e analisadas na sessão do
PR #12):

1. **Palavras coladas no texto extraído**: as respostas citaram os chunks literalmente com defeitos
   de extração do PDF — "aalma", "Deacordo", "emparte", "desi", "paraenfim", "quenão", "ascoisas",
   "tesentirás". O defeito nasce na extração (PDFBox), não na geração.
2. **Recall falha para consulta literal por número**: "qual a pergunta 157?" (sem nome do livro,
   sem scope) → `NoRelevantContext`; só recuperou o item 157 após reformulação semântica ("o que
   acontece depois da morte?"). Já registrado como follow-up no ADR-0013 e em
   `specs/referencia-estruturada/plan.md` ("Fora do plano desta feature").
3. **Resposta confusa com histórico**: a resposta sobre o item 158 começou com "Não, essa não é a
   pergunta 158..." refutando uma premissa que o usuário não tinha afirmado — provável confusão do
   modelo com os turnos anteriores da mesma conversa (sobre o item 157).
4. **Groundedness foi boa (3/3)**: nenhuma resposta inventou conteúdo nem citou página — a base do
   pipeline está sólida; as frentes abaixo são refinamento, não conserto estrutural.

## Frente 1 — Correção da extração de texto (palavras coladas) — **prioridade ALTA**

**Problema**: o texto extraído do PDF vem com palavras coladas (espaços perdidos pelo PDFBox neste
PDF específico). Degrada três coisas ao mesmo tempo: legibilidade da resposta (o modelo cita o chunk
com o defeito), embeddings (o vetor de "aalma" não é o de "alma") e busca lexical (o tsvector nunca
casa "quenão" com "não").

**Hipóteses técnicas a investigar na spec** (não decididas — a spec/plan valida):
- Configuração do `PDFTextStripper` do PDFBox: `setSortByPosition(true)`, tolerâncias de
  espaçamento (`setSpacingTolerance`/`setAverageCharTolerance`) — o defeito é típico de PDF com
  kerning/posicionamento que o default do PDFBox interpreta como ausência de espaço.
- Pós-processamento no `TextCleaner` como fallback (ex.: heurística de decolagem por dicionário) —
  provavelmente desnecessário se a config resolver; avaliar custo/benefício na spec.
- Diagnóstico primeiro: extrair algumas páginas do PDF real com configs diferentes e comparar,
  antes de escrever qualquer código de produção.

**Escopo**: `backend/` — `ingestion/pdf/` (extração) e possivelmente `TextCleaner`. Zero mudança em
retrieval/geração.

**Critérios de aceite (esboço para a spec)**: páginas extraídas do PDF real sem palavras coladas
(amostra verificável); nenhuma regressão nos testes de extração/chunking existentes; após
re-ingestão (`--reindex`), as respostas às perguntas do golden set não exibem mais palavras coladas.

**Gates**: mudança em chunking/extração → `rag-evaluator` obrigatório (constitution.md seção 4).
Exige re-ingestão real do livro pelo operador ao final (`--reindex --reference-style=numbered-item`)
— ver roteiro em `DESAFIOS.md` (2026-07-21) para a logística de credenciais/infra.

**Spec sugerida**: `specs/extracao-espacos-pdf/`. **Modelo**: Sonnet (diagnóstico + plano claro).

## Frente 2 — Golden set expandido (30-50 perguntas) — **prioridade ALTA, paralela à Frente 1**

**Problema**: o golden set tem só 3 perguntas — qualquer mudança das outras frentes fica sem régua
de antes/depois. O planejamento original (Fase 4/7, `specs/eval/history.md`) sempre previu 30-50.

**Escopo**: só artefato de avaliação (`specs/eval/golden-set.json`) — nenhum código de produção.
Perguntas cobrindo: lookup direto por número de item (o caso que falha hoje — registrar como
"esperado falhar até a Frente 3"), perguntas semânticas em linguagem natural, perguntas com/sem nome
do livro, perguntas sem resposta no acervo (para medir a taxa de `NoRelevantContext` correto),
perguntas multi-chunk. Formato já definido: `expected_sources` com
`bookId`/`bookTitle`/`reference`/`referenceType` (nunca página).

**Critério de pronto**: 30-50 casos com `expected_answer_gist` verificado contra o livro real (não
aproximação); casos de falha conhecida marcados como tal em `notes`.

**Gates**: nenhum (não muda código). Pode entrar como tarefa dentro da spec da Frente 1 ou como
PR próprio de specs — decidir ao abrir a spec.

**Modelo**: Sonnet para estruturar; o conteúdo das perguntas/respostas idealmente com participação
do usuário (que conhece o livro e o uso real).

## Frente 3 — Busca estruturada exata por número de item — **prioridade MÉDIA-ALTA**

**Problema**: consulta literal por número ("qual a pergunta 157?") não é bem servida pela busca
híbrida (vetorial + lexical + RRF) — número isolado não carrega semântica e o match lexical de "157"
compete mal. Follow-up já registrado no ADR-0013 (seção "Consequências"/follow-ups) e no
`plan.md` da feature `referencia-estruturada`; a coluna `chunk.reference` já existe e está populada
justamente para viabilizar isto.

**Hipóteses técnicas a investigar na spec**:
- Detecção do padrão na query (ex.: "pergunta/item/questão N" ou número isolado) — onde: no rewrite
  (Haiku já roda antes do retrieval) ou por regex determinística no `RetrievalService`? A regex é
  mais barata e previsível; a spec decide.
- Consulta direta `WHERE reference = :n OR reference` cobre intervalo (`"158–159"` contém 158 —
  atenção ao formato de intervalo com en dash, ver `Chunker.groupReference`).
- Fusão com o ranking híbrido: resultado exato entra com prioridade máxima no top-k ou substitui a
  busca? Decidir na spec (provável: merge com boost, para não cegar a busca semântica quando o
  número detectado for falso positivo).
- Escopo de livro: lookup exato só faz sentido em livros `NUMBERED_ITEM` — filtrar por
  `book_version.reference_type` ou `chunk.reference_type`.

**Dependências**: Frentes 1+2 primeiro — sem extração limpa e golden set com casos de lookup, não dá
para medir o ganho desta frente com confiança.

**Gates**: mudança em retrieval → `rag-evaluator` obrigatório. Possível ADR novo (ou amenda ao
ADR-0013) se a spec concluir que a fusão exata+híbrida é decisão arquitetural — o
`android-architect` avalia no passo 3 do `/spec-feature`.

**Spec sugerida**: `specs/busca-exata-item/`. **Modelo**: Sonnet.

## Frente 4 — Prompt/histórico (resposta refutando premissa inexistente) — **prioridade MÉDIA**

**Problema**: na conversa real, a resposta sobre o item 158 tratou a pergunta como se o usuário
tivesse afirmado um conteúdo errado ("Não, essa não é a pergunta 158...") — o usuário só perguntou
"qual a pergunte 158 do livro dos espíritos?". Hipótese: o histórico da conversa (turnos sobre o
item 157) entrou no prompt de um jeito que o modelo interpretou como premissa do usuário; ou o
rewrite da query embutiu contexto do turno anterior na pergunta reescrita.

**Investigar primeiro, mudar depois**: reproduzir com logging do prompt final (o que exatamente
chegou ao Claude — `buildUserPrompt` + histórico + query reescrita) antes de mexer em qualquer
texto. Pode ser ajuste no `ANSWER_SYSTEM_PROMPT` ("responda à pergunta atual diretamente; o
histórico é contexto, não premissa a confirmar/refutar"), no formato do histórico, ou no prompt de
rewrite.

**Gates**: mudança em prompt de geração → `rag-evaluator` obrigatório. O golden set expandido
(Frente 2) deve incluir casos multi-turno para cobrir isto.

**Spec sugerida**: `specs/prompt-historico/` (ou absorvida por outra frente se a investigação
mostrar que a causa é o rewrite). **Modelo**: Sonnet para investigar; a mudança em si pode ser
Haiku se for puramente textual com plano fechado.

## Frente 5 — Calibração de parâmetros de retrieval — **prioridade CONTÍNUA/BAIXA**

**Problema**: `min-cosine-similarity` (0.5), `top-k` (8), `token-budget` (3000),
`vector-candidates`/`lexical-candidates` (50) estão nos defaults "a calibrar" desde
`specs/retrieval/plan.md` — nunca foram calibrados contra dados reais.

**Método**: com o golden set expandido (Frente 2), rodar `RetrievalDebugCommand` variando
parâmetros e comparar recall@k — trabalho empírico, sem mudança de código além de config
(`application.yml`). Só vira spec se a calibração revelar necessidade de mudança estrutural.

**Dependências**: Frente 2 (régua) e idealmente Frente 1 (senão calibra contra embeddings de texto
defeituoso). **Gates**: mudança de config de retrieval → rodar `rag-evaluator` para registrar o
antes/depois. **Modelo**: Haiku/Sonnet (mecânica de experimento).

## Ordem recomendada e dependências

```
Frente 1 (extração) ──┬─► re-ingestão real ─► Frente 3 (busca exata) ─► Frente 5 (calibração)
Frente 2 (golden set) ┘                       Frente 4 (prompt/histórico — independente,
                                              pode correr em paralelo à Frente 3)
```

1. **Agora**: `/spec-feature extracao-espacos-pdf` (Frente 1, com a Frente 2 embutida ou logo
   atrás).
2. **Depois da re-ingestão limpa**: Frente 3 (o ganho é medível) e Frente 4 (investigação pode
   começar antes, é independente da extração).
3. **Contínua**: Frente 5, sempre que o golden set crescer ou o acervo mudar.

## Registro e processo

- Este documento é atualizado (nota datada, mesmo padrão dos ADRs) quando uma frente vira spec,
  muda de escopo ou é concluída — para o histórico não se perder entre sessões.
- Cada frente: `/spec-feature` → spec/plan/tasks revisados pelo usuário → implementação task a task
  (`kotlin-implementer` + `code-reviewer` + `/commit`) → `/pr` → merge sempre do usuário.
- Gates de `rag-evaluator` que exigirem ingestão/API reais seguem o roteiro operacional documentado
  em `DESAFIOS.md` (entrada 2026-07-21) — credenciais e PDF ficam na máquina do usuário, nunca no
  repo/sandbox.
