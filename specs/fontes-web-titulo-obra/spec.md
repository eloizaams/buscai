# Spec — Fontes no cliente web + título real da obra

Origem: R1 + R2 de `docs/analise-qualidade-rag-2026-07-22.md` (A1 e A2). Duas frentes leves,
sem gate de `rag-evaluator` (não tocam chunking/embedding/retrieval/prompt de geração).

## Contexto

O backend já emite, antes do primeiro token de cada resposta, um evento SSE `sources` com a
lista completa dos trechos que fundamentaram a resposta (livro, referência, texto integral). O
cliente web (`web/app.js`) recebe esse evento e descarta — o usuário nunca vê as citações que o
formato de resposta desejado ("lista de citações + resumo") depende.

Separadamente, o livro já ingerido em produção saiu sem `--title`, então toda citação e a lista
de livros mostram o slug técnico (`o-livro-dos-espiritos`) em vez do título real ("O Livro dos
Espíritos").

## Requisitos funcionais

### R1 — Lista de fontes no cliente web

- Ao receber uma resposta do assistente, o usuário vê, associada a ela, uma lista das fontes que
  a fundamentaram: para cada uma, o título do livro, a referência (capítulo ou item/pergunta,
  quando existir) e o trecho de texto correspondente.
- A lista nasce recolhida (só um resumo visível, ex. "N fontes") e o usuário pode expandir/
  recolher cada uma.
- Quando a resposta não teve fontes (backend não emite `sources` — sem contexto relevante), não
  aparece nenhuma lista de fontes, e a resposta segue exibida normalmente.

### R2 — Título real da obra

- Toda citação (lista de fontes de R1, texto corrido da resposta) e a lista de livros do cliente
  mostram o título real da obra, nunca o identificador técnico (`bookId`/slug).
- A ingestão de um livro **novo** exige um título explícito — não é mais aceitável ingerir sem
  título e herdar o slug.

## Critérios de aceite

- CA1: Uma pergunta com resposta fundamentada mostra, junto da resposta, uma lista recolhida de
  fontes; expandir uma fonte mostra livro + referência (quando houver) + trecho.
- CA2: Uma pergunta sem contexto relevante (resposta declara que não há fundamento nos livros)
  não mostra lista de fontes.
- CA3: Reabrir uma conversa antiga continua mostrando as mensagens normalmente — sem lista de
  fontes (o histórico persistido não guarda fontes; não é regressão, é o comportamento já
  existente hoje para o texto das mensagens).
- CA4: O comportamento atual de erro de streaming (mensagem genérica anexada) não muda.
- CA5: A lista de livros do cliente e toda citação exibida mostram "O Livro dos Espíritos" (não
  "o-livro-dos-espiritos") para o livro já ingerido em produção.
- CA6: Rodar a CLI de ingestão sem `--title` falha com uma mensagem clara ao operador, sem
  ingerir nada.

## Fora de escopo

- Persistir fontes junto do histórico de conversa (reabrir uma conversa antiga não ganha lista de
  fontes nesta spec — é mudança de modelo de dado do backend, não pedida aqui).
- Atualizar automaticamente o título de um livro já existente ao reingerir (correção do livro já
  em produção é uma ação pontual de operação, não uma feature da CLI).
- Qualquer mudança em chunking, embedding, retrieval ou prompt de geração (R3/R4/R5/R6 do
  roadmap, tratadas em specs futuras).
- Diversidade multi-obra no ranking (A7 do diagnóstico — não exercitável com uma obra só).
