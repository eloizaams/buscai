# Spec — Limite de item numerado sem linha em branco entre itens + golden set expandido

## Contexto

Nasce do roadmap `docs/planejamento-melhoria-qualidade-rag.md` (Frente 1), mas **muda de alvo**
depois de um diagnóstico contra o PDF real: a hipótese original (palavras coladas por config do
`PDFTextStripper`) não se confirmou. Rodei a extração crua (config atual e duas variações) e o
**pipeline completo de produção sem nenhuma modificação** (`PdfTextExtractor` → `TextCleaner` →
`Chunker`) contra *O Livro dos Espíritos* real: nenhuma das 8 palavras coladas catalogadas na
sessão de eval de 2026-07-21 ("aalma", "Deacordo", "emparte", "desi", "paraenfim", "quenão",
"ascoisas", "tesentirás") aparece em nenhum dos 320 chunks finais gerados. O texto extraído hoje
está correto quanto a espaçamento.

**Problema real encontrado no mesmo diagnóstico** (mais sério que o original): **todos os 320
chunks saem com `reference = null`** — o `ReferenceAnnotator` (ADR-0013) nunca detecta a abertura
de um item numerado neste livro. Causa raiz: *O Livro dos Espíritos* não tem linha em branco entre
itens consecutivos (o item 157 começa na linha seguinte ao fim do item 156, sem separador) — só
capítulos/seções têm separação por linha em branco. `Chunker.splitIntoParagraphs` só reconhece
linha em branco (`PARAGRAPH_BREAK = \n\s*\n`) como limite de parágrafo, então dezenas de itens
consecutivos ficam fundidos num único "parágrafo" gigante antes do chunking. A regex de abertura de
item (`NUMBERED_ITEM_OPENING_REGEX = ^\s*(\d+)\.\s`, ancorada ao início do parágrafo) só teria
chance de casar no primeiro item de cada bloco fundido — e mesmo esse primeiro item normalmente já
vem colado ao fim do conteúdo anterior, então na prática **nenhum** item deste livro é detectado.

Isso contradiz o que a avaliação real de 2026-07-21 relatou ("todas [as respostas] citaram o
número do item corretamente") — hipótese mais provável: o modelo lia o número do item **como texto
corrido dentro do chunk** (que contém literalmente "157. No momento da morte...") e o citava na
resposta gerada, independente do campo estruturado `reference`, que muito provavelmente já estava
nulo naquela ingestão também. O defeito é silencioso: não aparece como erro, só como ausência do
campo estruturado — e o `event: sources` (ADR-0013) mostraria toda citação nível-livro, sem número
de item algum, o que a sessão de eval não chegou a inspecionar diretamente no payload.

**Validação da hipótese de correção** (protótipo isolado, fora de `src/main`, descartado depois de
confirmado): dividir parágrafo também numa linha que abre um item numerado — não só em linha em
branco — recupera **1019 números de item distintos, sequenciais, sem nenhum furo** (1 a 1019) e os
itens 157/158 saem corretos e isolados, batendo com o conteúdo real verificado na sessão anterior.
Risco conhecido e aceito: listas numeradas *dentro* de uma resposta em prosa (ex. "1. Por que
mostra a alma aptidões tão diversas...", um caso real encontrado no diagnóstico) geram falso
positivo pontual nos números baixos (1-6 no livro real) — item já citado como risco aceito pelo
próprio ADR-0013 para a abordagem baseada em regex (sem dicionário).

Esta spec ainda embute a Frente 2 do roadmap (golden set expandido de 3 para 30-50 casos) —
continua fazendo sentido: agora que a numeração real 1-1019 está confirmada e confiável, dá para
montar casos de golden set com `reference` real verificável, cobrindo a régua que falta para medir
esta e as próximas frentes.

## Ator

- **O usuário final**, através do cliente web (`web/`) ou qualquer cliente que fale `POST /chat`:
  ao perguntar por um item numerado, recebe a citação estruturada (`event: sources`) com o número
  do item correto — não só o nível de livro.
- **O desenvolvedor/operador do backend**, que reingere o livro (`--reindex`, ADR-0008) e pode
  verificar por amostra que a referência estruturada bate com a numeração real do livro.
- **Quem mantém `specs/eval/golden-set.json`** (o desenvolvedor com a sua participação verificando
  conteúdo real) — régua de qualidade para esta e as próximas frentes do roadmap.

## O que o ator consegue fazer

1. **Perguntar por um item numerado específico e receber a citação estruturada correta**: o campo
   `reference` do(s) chunk(s) recuperado(s) no `event: sources` traz o número do item (ou um
   intervalo que o contenha, ADR-0013 seção 4) — não `null` — para um livro `numbered-item` cujo
   texto extraído não tem linha em branco entre itens.
2. **(Operador) Reingerir um livro já indexado e ver a referência estruturada corrigida**: rodando
   `--reindex --reference-style=numbered-item` com o código corrigido, a nova versão do livro tem
   a referência estruturada preenchida onde antes era nula.
3. **(Operador) Confiar que a numeração detectada é coerente com o livro real**: por amostragem
   (ex. os itens 157/158 já verificados, mais os do golden set expandido), a referência capturada
   bate com a numeração impressa no livro.
4. **(Quem mantém o golden set) Consultar um golden set com 30-50 casos verificados contra o livro
   real**, incluindo `expected_sources.reference` com números de item reais e verificáveis — mesmo
   escopo já descrito no roadmap (Frente 2): lookup direto por número, pergunta semântica, com/sem
   nome do livro, pergunta sem resposta no acervo, pergunta multi-chunk.

## Critérios de aceite (observáveis)

- **CA1**: reingerindo *O Livro dos Espíritos* com `--reference-style=numbered-item` usando o
  código corrigido, pelo menos 950 números de item distintos e não-nulos aparecem entre os chunks
  gerados (a numeração real vai de 1 a 1019, confirmada no diagnóstico; o piso de 950 dá margem
  para agrupamento de itens curtos em intervalo — ADR-0013 seção 4 — sem exigir zero
  falso-positivo, ver CA5).
- **CA2**: os chunks correspondentes aos itens 157 e 158 (já usados no golden set,
  `espiritos-001`/`espiritos-002`) saem com `reference` igual a `"157"` e `"158"` (ou um intervalo
  que os contenha, conforme ADR-0013 seção 4) — verificável via `RetrievalDebugCommand` ou consulta
  direta ao banco após a reingestão.
- **CA3**: nenhuma regressão nos testes já existentes de `Chunker`/`ReferenceAnnotator`/
  `ChunkValidator` — em especial o caminho `CHAPTER` (não tocado por esta spec) e qualquer caso já
  coberto de `NUMBERED_ITEM` com linha em branco entre itens (se existir teste assim, o
  comportamento não pode mudar).
- **CA4**: a reingestão completa de *O Livro dos Espíritos* com o código corrigido termina com
  sucesso (`IngestionOutcome.Completed`), passando pelo `ChunkValidator` sem violação — em
  particular a checagem de overlap entre chunks vizinhos (10-20%, ADR-0002/0008), que nunca foi de
  fato exercitada por itens atômicos curtos até hoje (nenhum item era detectado antes desta
  correção). Se a reingestão falhar por causa disso, é uma decisão de design a resolver no
  `plan.md` antes do merge, não um detalhe a descobrir em produção.
- **CA5** (não-objetivo, não testável — declaração de escopo): ocasionalmente uma lista numerada
  *dentro* de uma resposta em prosa (não uma abertura real de item do catecismo) pode receber uma
  referência de item espúria — risco conhecido, confirmado no diagnóstico nos números baixos (1-6)
  do livro real, e que **vaza para a citação estruturada mostrada ao usuário** (`event: sources`)
  quando isso ocorre. Esta spec aceita esse ruído (mesma categoria de risco que o ADR-0013 já
  assumiu para detecção por regex sem dicionário) e não tenta eliminá-lo; CA1 mede a cobertura
  agregada, não exige zero falso-positivo.
- **CA6**: o `rag-evaluator` rodado contra o golden set (existente + expandido) não mostra
  regressão de recall/groundedness em relação à linha de base — gate obrigatório da
  constitution.md seção 4 para mudança de chunking. A linha de base é medida nos casos já
  existentes antes da expansão (CA7) — a expansão em si não tem "antes" (mesma nota metodológica
  já registrada na revisão da spec anterior).
- **CA7**: `specs/eval/golden-set.json` passa a ter entre 30 e 50 casos, cada um com
  `expected_answer_gist` verificado contra o conteúdo real do livro e, quando aplicável,
  `expected_sources.reference` com o número real do item (agora confiável, ver diagnóstico).
  Cobre pelo menos um caso de cada categoria do roadmap (Frente 2): lookup direto por número,
  pergunta semântica em linguagem natural, pergunta com nome do livro, pergunta sem nome do livro,
  pergunta sem resposta no acervo (`NoRelevantContext` esperado), pergunta multi-chunk.
- **CA8**: `--reindex` força reprocessamento de *O Livro dos Espíritos* mesmo com o mesmo arquivo/
  modelo de embedding (achado independente da sessão anterior, ver `plan.md` — pré-requisito para
  CA1/CA2 serem verificáveis via reingestão real, já que o PDF em si não muda, só o código).

## Fora de escopo (explicitamente)

- **Correção de palavras coladas por espaçamento** (hipótese original desta spec) — diagnosticada e
  descartada: não reproduz contra o código e o PDF atuais. Se reaparecer (outro livro, ou uma
  edição diferente do PDF), é investigação própria, não parte desta spec.
- **Eliminar 100% do falso-positivo de listas numeradas dentro de prosa** (CA5) — aceito como
  ruído conhecido e limitado (confirmado nos números 1-6 do livro real), não bloqueia o merge.
- **Validação de continuidade/monotonicidade da numeração detectada** — decisão deliberada de não
  adicionar (YAGNI/frágil: numeração pode reiniciar por parte/capítulo em outros livros, viraria
  fonte nova de falso-negativo); registrada como nota datada no ADR-0013 (`plan.md`).
- Mudança no caminho `CHAPTER` do `ReferenceAnnotator`/`Chunker` — nenhum defeito equivalente
  diagnosticado ali; fora de escopo.
- Busca estruturada exata por número de item (Frente 3 do roadmap) — depende desta spec estar
  concluída (referência confiável) para ser medida com confiança, mas é spec própria.
- Investigação do prompt/histórico (Frente 4) e calibração de retrieval (Frente 5) — independentes,
  specs próprias.
- Re-ingestão em massa automática do acervo inteiro — cada livro é reingerido manualmente pelo
  operador (`--reindex`); esta feature não dispara isso.
