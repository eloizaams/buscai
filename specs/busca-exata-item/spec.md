# Spec — busca-exata-item (R5: busca exata por número de item)

Origem: `docs/analise-qualidade-rag-2026-07-22.md`, achado **A5** (antiga Frente 3 do roadmap);
follow-up explicitamente registrado no ADR-0013 (seção 4, "Fora do escopo deste ADR"). Validada
contra `specs/constitution.md`.

## Problema

Perguntas de **lookup literal** — "qual a pergunta 25 do Livro dos Espíritos?", "o que diz o item
700?" — não são bem servidas pela busca híbrida atual (vetorial + léxica): um número isolado não
tem semântica para o embedding, e o ramo léxico trata "25" como token qualquer. Resultado
observado em sessão real (2026-07-22): perguntando "qual a pergunta 25", o sistema não alucinou
(correto), mas também não recuperou o item 25 — que existe, está indexado e tem
`chunk.reference` populada — e devolveu apenas ruído semântico. Mesma classe de falha do caso
`espiritos-013` do golden set ("pergunta 700").

O pré-requisito já foi entregue (specs/referencia-estruturada + specs/limite-item-numerado +
specs/conteudo-paginas-overlap): a coluna `chunk.reference` está populada e confiável no corpo do
livro, com `referenceType = NUMBERED_ITEM`, valor "N" (item único) ou faixa "N–M" (itens curtos
agrupados), e o texto do chunk corresponde exatamente à faixa declarada.

**Pré-condição (parecer do `android-architect`, 2026-07-23):** esta feature pressupõe o índice
pós R3/R4 (`specs/conteudo-paginas-overlap`, PR #19) — sem os ranges-lixo do achado A3
("5–224", "222–5") e sem overlap contaminando chunks `NUMBERED_ITEM`. Satisfeita: reingestão
feita e gate T5 aprovado (`specs/eval/history.md`).

## O que o usuário consegue fazer

1. **Lookup direto por número de item**: perguntar "qual a pergunta 25?", "o que diz o item 700?",
   "questão 157 do Livro dos Espíritos" e receber resposta fundamentada no **item real** — o chunk
   cuja referência é o número pedido ou uma faixa que o contém — com a citação correta
   ("Pergunta 25"), nunca um vizinho semântico.
2. **Pergunta mista (número + assunto)**: "a pergunta 700 fala da igualdade entre os sexos?"
   recupera o item 700 **e** continua se beneficiando da busca semântica para o restante do
   contexto — o lookup exato soma-se à busca híbrida, não a substitui.
3. **Pergunta puramente semântica continua como hoje**: perguntas sem menção a número de item não
   mudam de comportamento em nada.

## Requisitos funcionais

- **RF1 — Detecção da intenção de lookup**: a pergunta é tratada como lookup de item apenas quando
  menciona um número acompanhado de um marcador de item ("pergunta N", "questão N", "item N" e
  variações naturais em pt-BR). Um número solto sem marcador (ex.: ano "1857", "os 10
  mandamentos") **não** dispara lookup — falso positivo de lookup injetaria contexto errado.
- **RF2 — Resolução exata contra a referência estruturada**: o lookup encontra o chunk certo tanto
  quando `reference` é o número exato ("25") quanto quando é uma faixa que o contém ("160–164"
  contém 162). Match é sobre o valor estruturado de `chunk.reference`, nunca sobre o texto livre.
- **RF3 — Fusão com boost, não substituição**: o(s) chunk(s) resolvidos por lookup exato entram no
  conjunto de contexto com prioridade suficiente para não serem cortados pelo top-k/orçamento,
  mas os demais resultados da busca híbrida continuam preenchendo o restante do contexto.
- **RF4 — Escopo respeitado**: o lookup respeita o `RetrievalScope` da conversa (livro específico
  ou acervo inteiro) e só considera livros de estilo item numerado. Com mais de um livro numerado
  no escopo e pergunta sem obra explícita, todos os matches entram (a ambiguidade é do usuário,
  não do sistema esconder resultado).
- **RF5 — Número inexistente sem falso positivo**: pedir um item que não existe no acervo indexado
  não pode fabricar match; o comportamento degrada para o da busca híbrida atual (incluindo o
  ramo `NoRelevantContext`/declarar que não encontrou — groundedness preservada).
- **RF6 — Citação**: a resposta e o `event: sources` citam o item pelo número ("Pergunta 25"),
  conforme ADR-0013/constitution §4 — nada muda no contrato SSE nem no cliente web.

## Critérios de aceite (observáveis)

- **CA1**: "qual a pergunta 25 do Livro dos Espíritos?" → `event: sources` inclui o chunk cuja
  `reference` é "25" ou faixa contendo 25, e a resposta é fundamentada nele.
- **CA2**: caso `espiritos-013` do golden set ("pergunta 700") passa a recuperar a fonte esperada
  (`reference: "700"`) — hoje é MISMATCH.
- **CA3**: lookup de número dentro de faixa: pergunta por item cuja `reference` no índice é faixa
  ("N–M") recupera esse chunk.
- **CA4**: "o que aconteceu em 1857?" (número sem marcador de item) **não** injeta lookup — o
  conjunto de fontes é o mesmo da busca híbrida pura.
- **CA5**: pergunta por item inexistente (ex.: "pergunta 99999") não traz chunk errado como se
  fosse o item; resposta não alucina o item.
- **CA6**: os demais casos do golden set (semânticos) não regridem — **gate `rag-evaluator`**
  contra `specs/eval/golden-set.json` antes do merge (constitution §4; régua: bloco
  "Resultado: Gate T5" em `specs/eval/history.md`).

## Fora de escopo

- Lookup por **capítulo** (`referenceType = CHAPTER`, "capítulo XII") — só itens numerados nesta
  feature; capítulo entra se/quando a avaliação mostrar demanda.
- Reingestão, mudança de chunking ou de schema de `chunk.reference` — a feature consome o índice
  como está (análise A5: "Reingestão? Não").
- Interpretação de múltiplos números na mesma pergunta como faixa ("das perguntas 20 a 30") —
  cada número marcado resolve individualmente; sintaxe de intervalo na *pergunta* é YAGNI até
  aparecer uso real.
- Mudança no cliente web (`web/`) — contrato SSE inalterado (RF6).
- Validação de continuidade/monotonicidade da numeração — já decidido fora (ADR-0013, nota
  2026-07-22).
