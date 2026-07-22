# Análise de qualidade do RAG — 2026-07-22 (pós-reingestão com referência corrigida)

Diagnóstico fim-a-fim feito sobre dados reais: os 33 casos do golden set expandido
(`specs/eval/golden-set.json`), rodados contra o índice reingerido com o fix de
`specs/limite-item-numerado/` (113 fontes recuperadas analisadas, transcritos brutos da avaliação
T7). Objetivo declarado pelo usuário: pergunta semântica (ex. "o que o espiritismo fala sobre os
diversos mundos habitados?") deve devolver **uma lista de citações (obra + capítulo/item + trecho
da obra) e uma resposta-resumo contextualizada**, com acervo multi-obra.

Este documento substitui a priorização de `docs/planejamento-melhoria-qualidade-rag.md`
(2026-07-21) — nota datada lá aponta para cá.

## Diagnóstico — o que está acontecendo, com evidência

### A1. O cliente web nunca mostra as fontes (impacto ALTO, custo BAIXO)

`web/app.js` trata só os eventos SSE `conversation`, `token` e `error` — o evento `sources`
(emitido pelo backend antes do primeiro token, com `bookTitle`, `reference`, `referenceType` e o
`text` integral de cada chunk — `ChatEvent.kt`/`SourceItem`) é **descartado sem renderizar**.
O formato de resposta que o usuário quer (lista de citações + texto + resumo) já é exatamente o
contrato do backend; a lacuna é só de apresentação. Nenhuma mudança de backend necessária.

### A2. Título do livro é o slug (impacto ALTO na leitura, custo BAIXO)

`IngestCommand`: `--title` é opcional e o default é o próprio `bookId` — a ingestão real foi feita
sem `--title`, então `Book.title = "o-livro-dos-espiritos"`. Toda citação gerada sai "conforme
o-livro-dos-espiritos, item 157" em vez de "conforme O Livro dos Espíritos, item 157". Correção:
reingerir com `--title="O Livro dos Espíritos"` (ou `UPDATE book SET title = ...` — mais barato,
não regenera embeddings; o título não entra no vetor).

### A3. Material de apoio ingerido como conteúdo (impacto ALTO — o núcleo dos "chunks ruins")

O pipeline ingere o PDF inteiro como conteúdo: sumário/TOC, cabeçalhos decorativos (letras soltas
"M" viram linhas), listas de tópicos de abertura de capítulo ("• Deus e o infinito • Provas..."),
notas de rodapé e o **índice remissivo alfabético** do final. Medido nas 113 fontes recuperadas da
avaliação: ~27% contêm algum desses ruídos (4 com TOC, 4 com cara de índice remissivo, 23 com
bullets de abertura). Consequências observadas:

- Chunks de índice remissivo/rodapé recuperados no lugar de conteúdo real (espiritos-029/030/032)
  e carimbados com referência falsa (`reference: "1"` herdada por arrasto — o
  `ReferenceAnnotator` carrega a última referência vista, correto para nota de continuação de
  item, sem sentido depois do item 1019).
- Faixas absurdas: `"5–224"` (chunk de sumário/prosa introdutória onde números de TOC casam com a
  regex de item) e `"222–5"`.
- Orçamento de contexto (3000 tokens) desperdiçado com ruído em vez de conteúdo.

### A4. Overlap herdado contamina o texto e a citação de itens numerados (impacto MÉDIO)

O chunk rotulado `"160–164"` começa com o *prefixo de overlap* herdado do vizinho — que contém os
itens 157–159. Resultado: a busca por "pergunta 157" recupera o chunk "errado" (rótulo 160–164) e
a resposta acerta **por sorte** (o conteúdo de 157 está no prefixo); a citação exibida contradiz o
texto. Para item numerado o overlap não tem função: o corte é sempre fronteira deliberada de item
(mesmo raciocínio já aceito no ADR-0008, nota 2026-07-22, para pular a checagem de overlap).
Removê-lo faz `text` corresponder exatamente à faixa `reference`.

### A5. Busca literal por número continua sem suporte (impacto ALTO para o caso de uso)

Já diagnosticado (Frente 3 do roadmap): "qual a pergunta 157?" não é bem servida por busca
vetorial+lexical — número isolado não tem semântica. A coluna `chunk.reference` agora está
populada e confiável no corpo do livro (era o pré-requisito); a consulta exata
(`reference = N` ou faixa contendo N) é viável.

### A6. Palavras coladas: RESOLVIDO — Frente 1 original não é mais necessária

Varredura das 113 fontes: **zero** ocorrências dos padrões defeituosos ("aalma", "quenão",
"emparte"...) e zero pontuação sem espaço. O PDF usado na reingestão de 2026-07-22 extraiu limpo
com o PDFBox default. A Frente 1 (config de `PDFTextStripper`) sai do roadmap — se um livro futuro
apresentar o defeito, reabre-se com o diagnóstico da época.

### A7. Multi-obra: suportado no retrieval, não exercitável ainda

`RetrievalScope.AllBooks` já agrega todas as obras ativas; a fusão RRF não garante diversidade por
obra no top-k (com uma obra só, não medível). Vira critério de avaliação quando a 2ª obra for
ingerida — não é gargalo hoje.

### A8. Recall/groundedness medidos (baseline T2 → final T7)

Groundedness segue limpa (nenhuma alucinação em 33 respostas). Recall por resposta: 28/33 (os
mesmos 4 MISMATCH do baseline + espiritos-029, que regrediu de `NoRelevantContext` para responder
com material de apoio — sintoma direto de A3). Recall por fonte citada: 25/33, puxado para baixo
por A3+A4.

## Plano de refatoração — frentes repriorizadas

Cada frente nasce via `/spec-feature` (SDD); gates de `rag-evaluator` valem para R3/R4/R5.
Pré-requisito: fechar a spec `limite-item-numerado` atual (registro final + PR) — o fix dela é
fundação de tudo abaixo.

| # | Frente | Ataca | Custo | Reingestão? |
|---|--------|-------|-------|-------------|
| R1 | Renderizar `event: sources` no cliente web (lista de citações: obra + item/capítulo + trecho, colapsável) | A1 | Baixo (só `web/`) | Não |
| R2 | Título real da obra (`UPDATE book SET title` agora; `--title` obrigatório na CLI daqui pra frente) | A2 | Baixo | Não |
| R3 | Delimitação de conteúdo na ingestão — excluir front matter (capa/sumário) e back matter (índice remissivo) | A3 | Médio | Sim |
| R4 | Sem overlap para `NUMBERED_ITEM` (texto do chunk = exatamente os itens da faixa) | A4 | Baixo-médio | Sim (junto com R3) |
| R5 | Busca exata por número de item (fusão com a híbrida, com boost) — antiga Frente 3 | A5 | Médio | Não |
| R6 | Prompt de resposta-resumo (a resposta vira o resumo contextualizado; a lista de citações é papel do `sources`/R1) + histórico (antiga Frente 4) | formato | Baixo | Não |
| R7 | Calibração de parâmetros (antiga Frente 5) | contínua | Empírico | Não |

### R3 — opções técnicas (decisão do usuário)

- **Opção A — intervalo de páginas na CLI (`--content-pages=<início>-<fim>`)** *(recomendada)*:
  o operador (que já fornece o PDF e o `bookId`) informa onde o conteúdo real começa e termina;
  páginas fora do intervalo não geram chunks. Prós: determinístico, trivial de testar, zero risco
  de falso positivo, coerente com o modelo operacional já decidido (ADR-0008: operador decide o
  slug — decidir o intervalo é o mesmo tipo de responsabilidade). Contras: manual por livro
  (aceitável: poucos livros, um operador); não remove ruído *dentro* do intervalo (bullets de
  abertura de capítulo — mitigável depois, se a avaliação mostrar que ainda pesa).
- **Opção B — heurísticas de detecção (TOC/índice/rodapé)**: automático, mas cada heurística é um
  risco novo de descartar conteúdo real (mesmo trade-off que motivou aceitar o falso-positivo de
  listas no ADR-0013), custo de spec/teste muito maior, e livro espírita varia demais de
  diagramação para uma heurística única. Não recomendada agora (YAGNI).

### Ordem recomendada

```
fechar spec atual (PR) ─► R1 + R2 (visíveis imediatamente, sem reingestão)
                       ─► R3 + R4 numa spec só ─► reingestão ─► rag-evaluator (régua: T7)
                       ─► R5 (busca exata) ─► R6 (prompt/resumo) ─► R7 (contínua)
```

R1+R2 mudam a experiência percebida no mesmo dia; R3+R4 são a correção estrutural dos chunks;
R5 destrava o caso de uso de lookup; R6 fecha o formato de resposta desejado.

## O que esta análise NÃO recomenda

- Trocar embedding/vector DB/chunking de tamanho — groundedness 33/33 e recall semântico alto
  mostram que a base (ADR-0002/0003) está sólida; o problema é ruído na entrada e apresentação na
  saída, não a arquitetura.
- Heurísticas de limpeza dentro do texto (dicionário, decolagem de palavras) — o defeito que as
  motivaria (palavras coladas) não existe mais no índice atual.
