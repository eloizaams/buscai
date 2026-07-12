# Tasks — Pipeline de ingestão de livros em PDF

Ordem sequencial — cada item depende do(s) anterior(es) e cabe numa sessão do
`kotlin-implementer`. Todos em `backend/`. Rodar `./gradlew ktlintFormat` e os testes do módulo
ao final de cada task (regra já fixada em `.claude/agents/kotlin-implementer.md`).

- [x] **T1 — Dependências e schema (Flyway)**
  Adicionar `org.flywaydb:flyway-core`, `org.flywaydb:flyway-database-postgresql`, Apache PDFBox
  (`org.apache.pdfbox:pdfbox`) e Testcontainers (`org.testcontainers:postgresql`,
  `org.testcontainers:junit-jupiter`, escopo teste) ao `build.gradle.kts`. Criar
  `src/main/resources/db/migration/V1__book_bookversion_chunk.sql` com `CREATE EXTENSION IF NOT
  EXISTS vector;` e as tabelas `book`, `book_version`, `chunk` (schema de `plan.md`), incluindo
  índice HNSW em `chunk.embedding`. Teste de aceite: `contextLoads` sobe rodando a migration
  contra um Postgres real via Testcontainers (não H2 — H2 não suporta pgvector/HNSW; ajustar
  `src/test/resources/application.yml` ou criar um profile de teste com Testcontainers só para
  este contexto, mantendo o `contextLoads` H2 existente intacto para os testes que não precisam
  de Postgres real).

- [x] **T2 — Entidades JPA e repositórios**
  Implementar `Book`, `BookVersion`, `Chunk` (pacote `book/`) mapeando o schema de T1, e os
  repositórios Spring Data (`BookRepository`, `BookVersionRepository`, `ChunkRepository`),
  incluindo o finder usado pela chave de gatilho do ADR-0008:
  `findByBookIdAndFileHashAndEmbeddingModelAndEmbeddingModelVersion`. Teste: salvar e recuperar
  cada entidade via Testcontainers, incluindo a coluna `embedding` (vetor de dimensão fixa da
  Voyage AI — confirmar dimensão do modelo `voyage-3` na doc via Context7 antes de fixar no
  schema).

- [x] **T3 — Extração de texto (PDFBox) + detecção de PDF sem texto**
  `PdfTextExtractor` (texto por página) e `ScannedPdfDetector` (regra do ADR-0008: página "sem
  texto" = <20 caracteres úteis extraídos; livro sinalizado quando >90% das páginas se
  enquadram). Testes unitários com PDFs de fixture pequenos (um com texto, um "escaneado"
  simulado com página em branco/só espaços) — sem rede, sem Testcontainers.

- [x] **T4 — Limpeza e normalização de texto**
  `TextCleaner`: remove hifenização de quebra de linha, headers/footers repetidos entre páginas,
  normaliza espaços. Testes unitários cobrindo cada regra isoladamente e o caminho feliz
  combinado (prioridade 2 do `test-writer`).

- [ ] **T5 — Chunking + validação estrutural**
  `Chunker` (300–800 tokens, overlap 10–20%, respeita parágrafo) e `ChunkValidator` (proxy do
  CA8/ADR-0008: rejeita chunk vazio, fora da faixa de tokens, ou com overlap medido fora de
  10–20%). Testes unitários: fronteiras de parágrafo, overlap, texto vazio/curto demais (menor
  que o mínimo de um chunk) — prioridade 1 do `test-writer`.

- [ ] **T5b — Corrigir agrupamento do `Chunker`: grupo pode fechar abaixo do mínimo antes do fim do livro**
  Bug encontrado na revisão da T5 (não é caso de borda raro): `groupUnits` fecha o grupo atual
  assim que o **próximo** parágrafo não cabe mais no teto `MAX_OWN_CONTENT_TOKENS`, mesmo que o
  grupo atual ainda esteja abaixo de `MIN_CHUNK_TOKENS` (300). Isso acontece sempre que um
  parágrafo curto é seguido de um parágrafo grande — comum em prosa real (diálogo curto → parágrafo
  descritivo longo) — não só no último grupo do texto. Como `ChunkValidator` rejeita a ingestão
  inteira ao encontrar qualquer chunk abaixo de 300 tokens (ADR-0008), isso quebraria CA1 (caminho
  feliz) em praticamente qualquer livro real com parágrafos de tamanho irregular.

  Corrigir `groupUnits` para uma estratégia **MIN-first**: continue crescendo o grupo atual até
  atingir `MIN_CHUNK_TOKENS` antes de permitir fechá-lo por causa de um parágrafo que não coube.
  Quando o próximo parágrafo não cabe inteiro E o grupo ainda está abaixo do mínimo, **corte esse
  parágrafo** na fronteira de token necessária para completar o grupo até `MAX_OWN_CONTENT_TOKENS`
  (reaproveitando a lógica de corte já usada por `splitOversizedParagraph` para parágrafos
  individualmente grandes demais) e devolva o restante do parágrafo para compor o próximo grupo.
  Depois que um grupo atinge `MIN_CHUNK_TOKENS`, o comportamento já existente (fechar assim que o
  próximo parágrafo inteiro não couber mais) continua valendo — só o caminho "abaixo do mínimo"
  muda. O único grupo que ainda pode ficar abaixo de `MIN_CHUNK_TOKENS` é o **último** do texto
  inteiro (quando os parágrafos acabam antes de atingir o mínimo) — isso é o comportamento já
  documentado no ADR-0008/`ChunkValidator` para o fim real do livro (ou um livro curto demais), não
  muda.

  Teste de regressão obrigatório: um texto onde um parágrafo curto (bem abaixo de 300 tokens) é
  seguido por um parágrafo que, somado ao primeiro, ultrapassaria `MAX_OWN_CONTENT_TOKENS` —
  confirmar que o primeiro grupo resultante tem pelo menos `MIN_CHUNK_TOKENS`, e que nenhum
  parágrafo foi cortado desnecessariamente (só quando realmente preciso para atingir o mínimo).
  Rodar a suíte completa de `ChunkerTest`/`ChunkValidatorTest` (T5) para confirmar que nada
  regrediu.

- [ ] **T6 — Cliente de embeddings (Voyage AI)**
  Interface `EmbeddingClient` + implementação HTTP (ADR-0003, modelo `voyage-3`, key só via env
  var `VOYAGE_API_KEY` — nunca hardcode, CLAUDE.md). Cobrir erro de rede/timeout e resposta com
  status de erro da API sem derrubar o processo sem mensagem clara (CA7). Teste unitário com
  fake/mock do client HTTP — sem chamar a Voyage real.

- [ ] **T7 — IngestionService: caminho feliz (livro novo), lotes de chunks (não de páginas)**
  Orquestra T3→T6 (ver `plan.md`, seção "Processamento incremental" — revisada: o "lote" se
  aplica à extração — já é por página/faixa desde T3 — e à embedding+persistência, não ao
  chunking, que roda sobre o texto do livro inteiro de uma vez, já corrigido em T5b):
  1. Cria `BookVersion` (`INGESTING`) numa transação curta.
  2. Extrai o texto do livro inteiro (usando `PdfTextExtractor` por página/faixa internamente,
     sem abrir o documento inteiro de uma vez — T3), limpa (`TextCleaner` — T4) e chunka
     (`Chunker` sobre o texto completo — T5/T5b) o livro inteiro em memória (texto limpo de um
     livro cabe folgado em memória, ver `plan.md`).
  3. Valida os chunks resultantes com `ChunkValidator` (T5) — se inválido, `BookVersion` vira
     `FAILED` com a lista de violações, sem chamar `EmbeddingClient`.
  4. Processa os chunks válidos em **lotes de chunks** (tamanho configurável, ex. 30-50 por
     lote): chama `EmbeddingClient` em batch **fora** de transação, e persiste os `Chunk`s do lote
     numa transação curta própria.
  5. Ao final de todos os lotes, marca `BookVersion` `READY`. Se `Book` não existir para o
     `bookId`, cria e aponta `activeVersionId` para a versão recém-criada (isso é parte do swap —
     ver T9, que também cobre o caso "primeira versão").
  Teste via Testcontainers: ingerir um PDF de fixture pequeno de ponta a ponta (com
  `EmbeddingClient` fake determinístico, simulando múltiplos lotes de chunks mesmo com poucos
  chunks — ex. tamanho de lote pequeno na config de teste) e verificar book/version/chunks
  persistidos (CA1). Verificar explicitamente que nenhuma transação de banco fica aberta durante a
  chamada ao `EmbeddingClient` fake (ex.: o fake grava o estado da transação corrente no momento da
  chamada e o teste assere que não há uma ativa).

- [ ] **T8 — IngestionService: idempotência e bloqueio de reindexação implícita**
  Implementa a chave de gatilho do ADR-0008: mesma `(bookId, fileHash, embeddingModel,
  embeddingModelVersion)` já `READY` → `IngestionOutcome.Skipped` sem reprocessar; `bookId`
  existente mas hash/versão diferentes e sem `--reindex` → `IngestionOutcome.ReindexRequired`
  (não chama `EmbeddingClient`, não gasta API paga). Testes: CA4 nos dois sub-casos (skip e
  bloqueio), garantindo zero chamadas ao `EmbeddingClient` fake em ambos.

- [ ] **T9 — IngestionService: reindexação com swap atômico**
  Com `--reindex`, roda o pipeline de T7 (chunking do livro inteiro + lotes de chunks para
  embedding/persistência) criando uma `BookVersion` nova; só troca `Book.activeVersionId` e
  remove a versão+chunks antigos após a nova terminar `READY`, numa única transação curta (sem
  I/O de rede). Simular falha no meio da nova ingestão (ex.: `EmbeddingClient` fake lança erro num
  lote de chunks no meio do livro) e verificar que `activeVersionId` permanece apontando para a
  versão antiga, que continua íntegra e servível (CA5, CA7) — mesmo com parte dos lotes da versão
  nova já persistidos no banco (são órfãos inofensivos, não visíveis para a busca).

- [ ] **T10 — IngestCommand: CLI e mensagens ao operador**
  `CommandLineRunner` sob profile/flag dedicado, parseia `--book-id`, `--file`, `--reindex`
  (`--title` opcional). Formata as saídas de `IngestionOutcome` em mensagens claras no console
  (CA3, CA4, CA6, CA7), incluindo progresso incremental durante o processamento (CA6 — ex.: log a
  cada N páginas/chunks). Teste: cada variante de `IngestionOutcome` produz a mensagem esperada
  (teste unitário de formatação, sem subir o Spring context).

- [ ] **T11 — Teste de aceite de volume (CA2 — obrigatório, não opcional)**
  Livros com mais de 300 páginas são realidade do acervo, não caso extremo — este teste faz parte
  do critério de aceite da feature, roda no CI (não fica marcado `@Tag("slow")`/excluído). Gera um
  PDF sintético de ~600–800 páginas (fixture determinística, não baixar arquivo externo), ingere
  de ponta a ponta com `EmbeddingClient` fake, e confirma: (a) ausência de OOM sob um heap limitado
  explicitamente no teste (ex.: `-Xmx` restrito na task Gradle de teste, para o teste falhar de
  forma clara se alguém voltar a carregar o livro inteiro em memória); (b) o número de páginas
  extraídas simultaneamente em memória não cresce com o tamanho do livro (assert indireto: contar
  quantas páginas o `PdfTextExtractor`/fake reporta ter em memória no pico, via um extrator de
  teste instrumentado); (c) o resultado persistido (contagem de chunks, `pageCount`) bate com o
  esperado. Documentar no teste os parâmetros de heap usados, para reproduzir depois.

- [ ] **T12 — Atualizar `docs/adr/` e `specs/eval/`**
  Confirmar que nenhuma decisão tomada durante a implementação (ex.: dimensão do vetor Voyage,
  detalhe do índice HNSW) diverge do que está registrado nos ADRs; se divergir, atualizar o ADR
  correspondente antes de considerar a feature concluída. `specs/eval/golden-set.json` continua
  vazio até haver livros reais ingeridos — não é bloqueante desta feature.

Depois de T1–T12: rodar `/review` (subagent `code-reviewer`) sobre o diff completo antes de
qualquer commit/PR, conforme `CLAUDE.md`.
