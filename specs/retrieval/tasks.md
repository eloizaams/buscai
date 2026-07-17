# Tasks — Busca e retrieval sobre o acervo ingerido

Ordem sequencial — cada item depende do(s) anterior(es) e cabe numa sessão do
`kotlin-implementer`. Todos em `backend/`. Rodar `./gradlew ktlintFormat` e os testes do módulo ao
final de cada task (regra já fixada em `.claude/agents/kotlin-implementer.md`).

- [x] **T1 — ADR-0010: pacote `embedding/` compartilhado + `input_type`**
  Mover `EmbeddingClient`, `EmbeddingClientException`, `VoyageEmbeddingClient`,
  `VoyageClientConfig`, `VoyageProperties` de `ingestion.embedding` para o pacote novo
  `com.buscai.backend.embedding` (move mecânico — imports/package, sem mudar lógica existente).
  Adicionar `EmbeddingInputType` (enum `DOCUMENT`/`QUERY`) no mesmo pacote; estender
  `EmbeddingClient.embed` para `embed(texts: List<String>, inputType: EmbeddingInputType):
  List<FloatArray>`; `VoyageEmbeddingRequest` ganha o campo `input_type` (mapeado do enum,
  minúsculo — confirmar valor exato aceito pela Voyage via Context7/doc antes de fixar).
  Atualizar o único chamador existente, `IngestionService`, para passar
  `EmbeddingInputType.DOCUMENT`. Teste: toda a suíte de `ingestion` continua passando após o move
  (rede de segurança); teste novo/atualizado do `VoyageEmbeddingClient` confirma que o corpo do
  request inclui `"input_type": "document"` quando chamado com `EmbeddingInputType.DOCUMENT`.

- [x] **T2 — Migration V2: coluna de full-text search**
  `V2__chunk_text_search.sql`: `ALTER TABLE chunk ADD COLUMN text_search tsvector GENERATED
  ALWAYS AS (to_tsvector('portuguese', text)) STORED;` + `CREATE INDEX idx_chunk_text_search_gin
  ON chunk USING gin (text_search);`. Não mapear a coluna em `Chunk.kt` (`catalog`) — só existe
  para leitura via SQL nativo (T3). Teste via Testcontainers: inserir um chunk com texto conhecido
  e confirmar, por query nativa, que `text_search @@ plainto_tsquery('portuguese', '<termo
  presente no texto>')` retorna a linha (e um termo ausente não retorna).

- [x] **T3 — `HybridSearchDao`: ramo vetorial + ramo léxico + fusão RRF**
  Query SQL nativa única (`plan.md`, seção "Contratos entre camadas"): top-N vetorial
  (`embedding <=> :queryVector`, índice HNSW) + top-N léxico (`ts_rank` sobre `text_search`,
  índice GIN de T2), ambos restritos a um conjunto de `bookVersionId` recebido como parâmetro,
  fundidos por RRF (`Σ 1/(k + rank)` por ramo). Devolve `List<HybridSearchRow>` com `chunkId`,
  `bookVersionId`, `page`, `chapter`, `text`, `tokenCount`, `cosineSimilarity` (bruta do ramo
  vetorial) e `rrfScore` (ordenação final) — os dois scores são campos distintos, não o mesmo
  número (ver `plan.md`). Risco técnico conhecido a resolver aqui: binding do parâmetro de vetor
  numa query nativa/JDBC (o projeto usa `hibernate-vector` para o mapeamento JPA de
  `Chunk.embedding`, não a classe `com.pgvector.PGvector` diretamente — confirmar, via Context7 ou
  teste exploratório, a forma correta de passar um `FloatArray` como parâmetro `vector` numa query
  nativa do Spring Data/JDBC antes de escrever os testes). Teste via Testcontainers: dado um
  pequeno conjunto de chunks conhecidos (texto e embeddings de fixture, não a Voyage real),
  confirmar que (a) um chunk com match léxico exato de um termo aparece no resultado mesmo com
  embedding propositalmente distante do vetor da query (CA3); (b) `bookVersionId` fora do conjunto
  passado nunca aparece no resultado (base de CA2/CA6); (c) `cosineSimilarity` devolvida bate com
  o cálculo manual esperado para o par de vetores de fixture.

- [x] **T4 — `RetrievalService`: resolução de escopo + embedding da query + busca**
  `RetrievalScope` (sealed: `AllBooks`/`Books(bookIds: Set<String>)`, não vazio), `RetrievedChunk`,
  `RetrievalResult` (sealed: `Found`/`NoRelevantContext` — `NoRelevantContext` só com o caminho
  "nenhuma versão elegível" por enquanto, o caminho CA7 completo é T6). `RetrievalService.search(query: String,
  scope: RetrievalScope): RetrievalResult`: resolve o conjunto de `bookVersionId` elegíveis
  (`Books(bookIds)` → `activeVersionId` de cada `Book` do conjunto que existir e estiver `READY`
  — `bookId` inexistente/sem versão `READY` não contribui, não é erro; `AllBooks` →
  `activeVersionId` de todo `Book` não nulo, filtrado por `embeddingModel`/`embeddingModelVersion`
  iguais aos de `VoyageProperties` atual — `plan.md`), embedda a query
  (`EmbeddingClient.embed(listOf(query), EmbeddingInputType.QUERY)` — T1), chama
  `HybridSearchDao.search` (T3) e devolve `Found` com os `HybridSearchRow` convertidos em
  `RetrievedChunk` (resolvendo `bookTitle` via `BookRepository`/`BookVersionRepository`,
  `catalog`), sem dedup/orçamento ainda (T5) nem o sinal de "sem contexto" completo (T6). Conjunto
  de versões elegíveis vazio pula a chamada ao `HybridSearchDao` e devolve `NoRelevantContext`
  direto. Teste via Testcontainers (fixture pequena com 2+ livros, `EmbeddingClient` fake
  determinístico): escopo `Books(setOf(bookId))` (subconjunto de tamanho 1) nunca traz chunk de
  outro livro (CA2); escopo `Books` com 2 dos 3 livros da fixture traz chunks dos dois e nunca do
  terceiro; um livro com versão `INGESTING`/`FAILED` mas sem `activeVersionId` `READY` não aparece
  em `AllBooks` nem é encontrado em `Books` contendo aquele `bookId`; escopo `AllBooks` com todos
  os livros na mesma versão de embedding não filtra nada indevidamente.

- [x] **T5 — `ContextAssembler`: dedup de vizinhos + orçamento de tokens**
  `ContextAssembler.assemble(rows: List<HybridSearchRow>, tokenBudget: Int):
  List<HybridSearchRow>` (opera antes da conversão para `RetrievedChunk` — integrar em
  `RetrievalService` nesta task): ordena por `rrfScore` desc; descarta candidatos da mesma
  `bookVersionId` cuja janela de `charOffset` se sobrepõe significativamente a um candidato já
  mantido de score maior (limiar `neighbor-dedup-min-overlap-chars`, `RetrievalProperties` — T7
  cria o binding, usar valor literal ou constante por enquanto se T7 ainda não rodou); acumula
  `tokenCount` na ordem resultante até `tokenBudget`, cortando o restante. Teste unitário puro
  (sem Spring context, sem banco): dois candidatos com janelas de `charOffset` sobrepostas mantêm
  só o de maior score (CA4); uma lista cujo somatório de `tokenCount` ultrapassa o orçamento é
  cortada no ponto certo, preservando a ordem por relevância (CA5); lista vazia devolve lista
  vazia sem erro.

- [x] **T6 — `RetrievalService`: sinal de "sem contexto relevante" (CA7)**
  Após `ContextAssembler` (T5), compara a maior `cosineSimilarity` entre os candidatos restantes
  contra `RetrievalProperties.minCosineSimilarity` (T7 cria o binding — valor literal por
  enquanto, se T7 ainda não rodou); abaixo do limiar, `RetrievalService.search` devolve
  `RetrievalResult.NoRelevantContext` mesmo que `HybridSearchDao`/`ContextAssembler` tenham
  produzido candidatos. Teste: candidatos todos com `cosineSimilarity` abaixo do limiar
  configurado produzem `NoRelevantContext`; ao menos um acima do limiar produz `Found` com os
  candidatos esperados.

- [x] **T7 — Config (`buscai.retrieval.*`) + `RetrievalDebugCommand`**
  `RetrievalProperties` (binding de `buscai.retrieval.*` — `vector-candidates`,
  `lexical-candidates`, `top-k`, `rrf-k`, `token-budget`, `min-cosine-similarity`,
  `neighbor-dedup-min-overlap-chars`, defaults de `plan.md`) adicionado a `application.yml`;
  conectar os valores literais deixados em T3/T5/T6 a esta config. `RetrievalDebugCommand`
  (`CommandLineRunner` sob profile/flag dedicado, mesmo padrão de `IngestCommand`): parseia
  `--query` (obrigatório) e `--books` (opcional, lista separada por vírgula — define
  `RetrievalScope.Books`; ausente = `AllBooks`), roda
  `RetrievalService.search` e imprime cada `RetrievedChunk` (bookId, página, capítulo, score,
  trecho do texto) ou a mensagem de "sem contexto relevante" no console. Teste: formatação de
  cada variante de `RetrievalResult` produz a saída esperada (teste unitário, sem subir o Spring
  context) — mesmo padrão de T10 de `specs/ingestao-pdf/tasks.md`.

- [x] **T8 — Teste de aceite de latência (CA8 — obrigatório, não opcional)**
  ~50 mil chunks é a escala-alvo do critério de aceite, não um caso extremo — mirror do T11 de
  `specs/ingestao-pdf/tasks.md` (teste de volume). Gera candidatos sintéticos (fixture
  determinística — vetores e textos gerados, não a Voyage real) e persiste via Testcontainers;
  mede o tempo de `HybridSearchDao.search` (e, se possível, de `RetrievalService.search` fim a
  fim) sobre esse volume, em execução "quente" (descartar a primeira chamada do teste, que paga
  custo de warm-up/plano de query, antes de medir — CA8 já qualifica a meta como "estado
  estável"). Documentar no teste o resultado medido e os parâmetros do ambiente (é uma meta "a
  validar", conforme ADR-0003 — se não bater, registrar como débito técnico explícito em vez de
  silenciosamente relaxar o teste).

- [x] **T9 — Atualizar `docs/adr/` e `specs/eval/`**
  Confirmar que nenhuma decisão tomada durante a implementação (ex.: valor exato de `input_type`
  aceito pela Voyage, forma de binding do parâmetro `vector` em query nativa, defaults finais de
  `RetrievalProperties`) diverge do que está registrado em ADR-0003/ADR-0010/`plan.md`; se
  divergir, atualizar o documento correspondente antes de considerar a feature concluída.
  `specs/eval/golden-set.json` continua vazio até haver livros reais ingeridos — não é bloqueante
  desta feature, mas `RetrievalDebugCommand` (T7) já é o ponto de entrada que o `rag-evaluator`
  vai usar assim que o golden set for preenchido.

Depois de T1–T9: rodar `/review` (subagent `code-reviewer`) sobre o diff completo antes de
qualquer commit/PR, conforme `CLAUDE.md`. Mudança em retrieval — rodar também o `rag-evaluator`
contra `specs/eval/golden-set.json` antes de mergear (constitution.md, seção 4); enquanto o golden
set estiver vazio, o `rag-evaluator` deve reportar isso em vez de aprovar a mudança sem medir nada.
