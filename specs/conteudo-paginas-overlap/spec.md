# Spec: Delimitação de conteúdo e remoção de overlap (R3+R4)

## Contexto

Diagnóstico pós-reingestão de "O Livro dos Espíritos" (2026-07-22, `docs/analise-qualidade-rag-2026-07-22.md`) expôs dois problemas estruturais na ingestão:

**R3 — Material de apoio ingerido como conteúdo (A3, impacto ALTO):** o pipeline ingere o PDF inteiro — sumário/TOC, listas de tópicos de abertura de capítulo, índice remissivo alfabético do final — como conteúdo real. Dados reais: ~27% das 113 fontes recuperadas na avaliação contêm ruído (sumário, índice, bullets de abertura). Consequência: orçamento de contexto (3000 tokens) desperdiçado com ruído em vez de conteúdo, chunks de referência falsa (ex. `reference: "1"` herdada por arrasto de índice remissivo).

**R4 — Overlap contamina citação de itens numerados (A4, impacto MÉDIO):** para itens numerados, o chunk recebe um prefixo de overlap do vizinho (~15%, útil para continuidade quando o corte de parágrafo é arbitrário — premissa que não vale quando o corte é sempre fronteira deliberada de item). Resultado: chunk rotulado `"160–164"` começa com conteúdo dos itens 157–159, tornando `text` inconsistente com `reference`. Citação exibida ao usuário contradiz o texto recuperado.

Ambas são gargalos de qualidade já quantificados (recall/groundedness medidos em `specs/eval/history.md` T2/T7) e rastreados na análise com prioridade R3+R4 juntas (ordem recomendada: logo após `specs/limite-item-numerado/` fechar).

## Ator e Capacidades

**Ator:** Desenvolvedor/operador ingerindo um PDF novo via CLI do backend.

**Capacidade R3 — Delimitação de conteúdo:**
- Especificar o intervalo de páginas de conteúdo real via `--content-pages=<início>-<fim>` (1-indexed, inclusive, ex. `--content-pages=15-280`).
- Páginas fora desse intervalo não geram chunks.
- Validação de intervalo: verificação de que `início >= 1`, `fim >= início`, `fim <= total_pages` (relatado como erro se violar).

**Capacidade R4 — Sem overlap em itens numerados:**
- Para chunks classificados como `NUMBERED_ITEM` (referenceType), o `text` corresponde **exatamente** à faixa de referência declarada.
- Não há prefixo herdado de overlap contaminando o início do chunk.
- Internamente: overlap é removido (ou não inserido) especificamente para esse tipo de chunk; para `CHAPTER`, o overlap é mantido (serve para continuidade).

**Resultado percebido:** Reingestão com `--content-pages` e tipo `--reference-style=numbered-item` produz chunks com `reference` confiável e `text` correspondente — recall e groundedness melhoram, documentado via rag-evaluator.

## Critérios de Aceite

**CA1:** A CLI aceita `--content-pages=<início>-<fim>` (ex.: `--content-pages=15-280`) e rejeita formatos inválidos (`--content-pages=15`, `--content-pages=a-b`, `--content-pages=280-15`, `--content-pages=0-10`) com mensagem de erro descritiva, sem chegar ao pipeline de ingestão.

**CA2:** Ao ingerir um PDF com `--content-pages=15-280`, o índice cresce apenas com chunks gerados a partir de páginas 15-280 (verificável em logs, no count de chunks criados).

**CA3:** Reingerir o PDF real de "O Livro dos Espíritos" com `--reference-style=numbered-item --content-pages=<início>-<fim>` (intervalo do corpo real da obra, determinado pelo operador inspecionando o PDF) elimina o material de apoio do índice: nenhuma fonte recuperada nas perguntas do golden set vem de sumário/TOC ou índice remissivo (no diagnóstico de 2026-07-22, ~27% das 113 fontes recuperadas continham esse ruído), e desaparecem as faixas espúrias de `reference` (ex. `"5–224"`, `"222–5"`).

**CA4:** `--content-pages` nunca é aplicado silenciosamente a um livro já ingerido: reingerir o mesmo `bookId`/mesmo arquivo com intervalo novo e **sem** `--reindex` cai no skip já existente (a chave de gatilho do ADR-0008 não inclui o intervalo), e a mensagem de skip informa explicitamente que é preciso `--reindex` para reprocessar (ex.: para aplicar outro `--content-pages`). Com `--reindex`, o intervalo novo é aplicado via swap atômico de versão (ADR-0008).

**CA5:** Um chunk recuperado com `referenceType="NUMBERED_ITEM"` tem `text` que começa **diretamente** com o conteúdo do item (ex. "157. Que é a morte? ..."), sem prefixo de overlap do chunk anterior (verificável em JSON de debug, exemplo em `specs/limite-item-numerado/`).

**CA6:** Testes de unidade para parsing de `--content-pages` no `IngestCommandTest` e para remoção de overlap no `ChunkerTest`, ambos passando.

**CA7:** Rag-evaluator gate — **diff caso-a-caso** contra transcrição bruta de T7 (`specs/eval/history.md`):
- Rodar golden set contra índice reingerido com R3+R4 (backend real, `POST /chat`, análise manual vs. T7).
- Para cada caso: registrar mudança (melhora/regressão/neutra) por caso, não agregado.
- **Esperado:** `espiritos-029` (que em T7 regrediu para responder com índice remissivo) **reverte para `NoRelevantContext`** — isso é **melhora**, não regressão.
- **Threshold de aprovação:** regressão Real (resposta piorou, não é rejeição de ruído) em <= 2 casos vs. T7; tudo o mais é aprovado. Documentar na recomendação de `history.md` (sessão R3+R4 final).
- Groundedness = 33/33 mantida (nenhuma alucinação nova).

## Fora de Escopo

- **Heurísticas automáticas de detecção** de front/back matter (Opção B da análise, rejeitada — YAGNI; o operador decide o intervalo, mesmo modelo de responsabilidade do `bookId` no ADR-0008).
- **Limpeza de conteúdo dentro do intervalo** de páginas (ex.: remover bullets de abertura de capítulo — R3 assume que front/back matter é claramente delimitado por página; mitigação de ruído intra-intervalo fica como trabalho subsequente se métrica mostrar que pesa ainda).
- **Compressão de overlap para parágrafos de prosa** — apenas NUMBERED_ITEM recebe tratamento diferencial (ADR-0013 seção 4, ADR-0008 nota 2026-07-22).
- **Validação de continuidade de numeração** de itens (ex.: alertar se item 158 não for seguido por 159) — YAGNI, numeração pode reiniciar por capítulo/parte em outros livros, geraria falso-negativo.
- **Reingestão real em produção** — as mudanças entram em `main` só com CI verde + gate aprovado; reingestão no Neon é ação operacional (fora de código), executada manualmente pelo operador pós-merge.

## Registro de Arquitetura (notas de ADR)

Implementação desta spec requer notas datadas em ADRs já aceitos, complementando decisões anteriores:

- **ADR-0002 (Ingestão, chunking):** Nota 2026-07-22 registrando que overlap de 10-20% deixa de ser **universal** — passa a ser **condicional ao `referenceType`**. Para `NUMBERED_ITEM`, overlap é removido do `text` (R4) para garantir que referência corresponda exatamente ao conteúdo; para `CHAPTER`, overlap é mantido (continuidade em cortes arbitrários de parágrafo). Mesma estratégia que ADR-0013 seção 4 usou para amender o piso mínimo de 300 tokens.
- **ADR-0008 (Identidade de livro, reindexação):** Nota 2026-07-22 já cobre skip de validação de overlap para `NUMBERED_ITEM`. Complementar com nota sobre que `--content-pages` não entra na chave de gatilho (bookId, fileHash, modelVersion) — reingerir com intervalo novo exige `--reindex` (CA4).
- **ADR-0013 (Referência estruturada):** Já registra que `NUMBERED_ITEM` é atômico (não partir entre chunks). Remoção de overlap é consequência direta dessa decisão e complementa a nota existente de 2026-07-22.
