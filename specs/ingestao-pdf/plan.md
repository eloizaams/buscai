# Plan — Pipeline de ingestão de livros em PDF

Depende de `spec.md` (critérios de aceite) e do [ADR-0008](../../docs/adr/0008-identidade-e-versionamento-de-livros-ingeridos.md)
(identidade de livro, chave de idempotência, swap atômico de versão). Segue as decisões já
fixadas em ADR-0002 (CLI, PDFBox, chunking) e ADR-0003 (pgvector, Voyage AI).

## Módulo e localização

Fica dentro do módulo Gradle único `backend/` (não cria subprojeto Gradle novo — o `módulo
ingestion` citado no ADR-0002 é separação por **pacote**, não por build separado; introduzir
multi-projeto Gradle seria uma decisão de arquitetura não coberta por ADR). Pacotes novos:

```
backend/src/main/kotlin/com/buscai/backend/
  book/                     — entidades e repositórios compartilhados (ingestão E busca/chat usam)
    Book.kt                 — entidade: bookId (slug, PK), title, activeVersionId, timestamps
    BookVersion.kt          — entidade: id (UUID), bookId (FK), fileHash, embeddingModel,
                               embeddingModelVersion, status, pageCount, chunkCount, timestamps
    Chunk.kt                — entidade: id, bookVersionId (FK), page, chapter, charOffset,
                               tokenCount, text, embedding (pgvector), createdAt
    BookRepository.kt       — Spring Data JPA
    BookVersionRepository.kt
    ChunkRepository.kt
  ingestion/
    IngestCommand.kt        — entrypoint da CLI (ver "Como roda" abaixo), parseia argumentos
    IngestionService.kt     — orquestra o pipeline (camada de serviço; único ponto que grava
                               book/bookVersion/chunk — nada mais acessa os repositórios direto,
                               por CLAUDE.md)
    PdfTextExtractor.kt     — Apache PDFBox: extrai texto por página, sob demanda por lote
                               (nunca abre todas as páginas do documento em memória de uma vez —
                               CA2)
    ScannedPdfDetector.kt   — critério mensurável do ADR-0008 (>90% páginas com <20 chars úteis)
    TextCleaner.kt          — hifenização, headers/footers repetidos, normalização de espaços
    Chunker.kt              — 300–800 tokens, overlap 10–20%, respeita parágrafo (ADR-0002)
    ChunkValidator.kt       — proxy estrutural do CA8 (ADR-0008): tamanho/overlap/não-vazio
    EmbeddingClient.kt      — chama Voyage AI (ADR-0003) em batch por lote de chunks; interface +
                               implementação HTTP
    IngestionOutcome.kt     — resultado (sealed class): Skipped / ReindexRequired / Completed /
                               Failed, com dados suficientes para a CLI formatar a mensagem
  build.gradle.kts          — dependências novas: Apache PDFBox, cliente HTTP para Voyage
                               (WebClient, já trazido por spring-boot-starter-webmvc/reactive
                               ou um client simples), Flyway (ver "Schema" abaixo)
```

## Como roda (sem endpoint HTTP — ADR-0002)

`IngestCommand` é um `CommandLineRunner` do Spring Boot ativado só sob um profile/flag dedicado
(ex.: `--spring.profiles.active=ingest` ou checagem do primeiro argumento), para não rodar
automaticamentequando o backend sobe como servidor de chat. Invocação:

```
./gradlew bootRun --args="ingest --book-id=dom-casmurro --file=/caminho/livro.pdf"
./gradlew bootRun --args="ingest --book-id=dom-casmurro --file=/caminho/livro-corrigido.pdf --reindex"
```

Justificativa de reaproveitar o mesmo processo Spring Boot (em vez de main class solta): a
ingestão precisa da mesma configuração de datasource (`application.yml`, ADR-0006) e das mesmas
entidades JPA que o backend de chat vai ler — reaproveitar o context de Spring evita duplicar
configuração de conexão com o Neon.

## Schema (Flyway)

`ddl-auto: validate` já está configurado (`application.yml`) — o schema precisa ser criado por
migration, não pelo Hibernate. Não há Flyway no `build.gradle.kts` ainda. Adicionar
`org.flywaydb:flyway-core` + `org.flywaydb:flyway-database-postgresql`, com migrations em
`src/main/resources/db/migration/V1__book_bookversion_chunk.sql` criando as tabelas (incluindo a
extensão `vector` do pgvector e o índice HNSW sobre `chunk.embedding`, conforme ADR-0003).

## Contratos entre camadas

- `IngestCommand` (I/O de linha de comando) → `IngestionService` (única porta de entrada da
  lógica) → `PdfTextExtractor` → `ScannedPdfDetector` (aborta cedo se detectar sem texto) →
  `TextCleaner` → `Chunker` → `ChunkValidator` (aborta se violar limites) → `EmbeddingClient`
  (em batch) → grava `BookVersion` (status `INGESTING` → `READY`/`FAILED`) e `Chunk`s associados
  a essa versão.
- Decisão de skip/reindex-necessário/prosseguir (ADR-0008) é responsabilidade de
  `IngestionService`, consultando `BookRepository`/`BookVersionRepository` — nenhuma outra classe
  decide isso.
- **Processamento incremental (CA2 — livro grande é o caso comum, não exceção):** o "lote" se
  aplica em dois pontos diferentes do pipeline, cada um pela sua própria razão de memória — **não**
  ao chunking, que roda sobre o texto do livro inteiro de uma vez (ver justificativa abaixo):
  1. **Extração (T3):** `PdfTextExtractor` lê o PDF por página/faixa de páginas, nunca o
     documento inteiro — o risco de memória aqui é o grafo de objetos do PDFBox (fontes, imagens,
     árvore de páginas), não o texto puro extraído.
  2. **Limpeza + chunking (T4/T5), livro inteiro de uma vez:** o texto já extraído e limpo de um
     livro, mesmo de milhares de páginas, ocupa tipicamente poucos MB como `String` — trivial para
     o heap do backend. Rodar `Chunker` sobre o livro inteiro (em vez de por lote de páginas) evita
     uma costura artificial de "fronteira de lote" que não existe na estrutura real do texto (um
     parágrafo pode atravessar o que seria o fim de um lote de páginas) e simplifica o algoritmo de
     agrupamento do `Chunker` (ver `Chunker.kt` para o motivo pelo qual isso importa: garantir que
     todo grupo de parágrafos atinja o mínimo de 300 tokens já é não-trivial só dentro de *um*
     texto contínuo — encadear isso entre chamadas por lote adicionaria complexidade sem ganho real
     de memória).
  3. Cria a `BookVersion` (status `INGESTING`) numa transação curta, chunka o livro inteiro (passo
     acima, em memória, sem tocar banco), e só então processa os **chunks resultantes** em lotes
     (tamanho configurável, ex. 30-50 chunks por lote) para embedding + persistência.
  4. Por lote de chunks: chama `EmbeddingClient` em batch **fora** de transação, e persiste os
     `Chunk`s do lote numa transação curta própria. Nenhuma transação de banco fica aberta durante
     a chamada de rede à Voyage — abrir uma transação e esperar uma API HTTP externa dentro dela é
     o motivo mais comum de exaustão de pool de conexão em ingestões longas.
  5. Se um lote falhar, a `BookVersion` é marcada `FAILED` numa transação curta; os lotes já
     persistidos daquela versão ficam no banco mas são irrelevantes, pois `activeVersionId` do
     `Book` nunca apontou para essa versão (não afeta CA5/CA7).
  6. Só depois que **todos** os lotes de chunks terminam com sucesso e a `BookVersion` vira
     `READY`, o swap abaixo acontece.
  Esse desenho é o que torna CA2 (livro de qualquer tamanho, memória limitada) compatível com
  CA5/CA7 (nunca um estado parcial visível) sem precisar manter tudo em memória até o fim, e sem
  a complexidade de encadear estado de chunking entre lotes de páginas.
- **Swap atômico:** só após a nova `BookVersion` chegar a `READY`, `IngestionService` atualiza
  `Book.activeVersionId` para a nova versão e remove a `BookVersion` (+ seus chunks) antiga numa
  única transação curta (`@Transactional`) — essa transação não faz I/O de rede, só escrita local,
  então pode ser atômica sem o problema do item acima.
- `EmbeddingClient` é uma interface cuja implementação HTTP aceita uma lista de textos e devolve
  os vetores correspondentes em batch (custo por chamada da Voyage é por requisição+tokens, não
  por chunk individual — batching também reduz custo, não só memória). A implementação concreta
  fica isolada para permitir fake determinístico nos testes (sem rede real, conforme convenção de
  test-writer).

## Modelos de dado (resumo)

| Entidade | Campos-chave | Observação |
|---|---|---|
| `Book` | `id` (slug, PK), `title`, `activeVersionId` (nullable), `createdAt`, `updatedAt` | `activeVersionId` nulo enquanto a primeira ingestão não termina |
| `BookVersion` | `id` (UUID, PK), `bookId` (FK), `fileHash`, `embeddingModel`, `embeddingModelVersion`, `status` (`INGESTING`/`READY`/`FAILED`), `pageCount`, `chunkCount`, `startedAt`, `completedAt` | chave de gatilho do ADR-0008 é `(bookId, fileHash, embeddingModel, embeddingModelVersion)` |
| `Chunk` | `id`, `bookVersionId` (FK), `page`, `chapter` (nullable), `charOffset`, `tokenCount`, `text`, `embedding` (`vector`, pgvector) | índice HNSW em `embedding`; usado pela feature de busca (fora de escopo aqui) |

## Fora do plano desta feature

- Endpoint de busca/chat (feature futura) — este plano só grava dados, não lê para responder
  perguntas.
- Multi-módulo Gradle — reavaliar só se o pacote `ingestion` crescer a ponto de justificar build
  independente.
- Limpeza automática de `BookVersion`s `FAILED` órfãs — registrar como débito técnico menor, não
  bloqueia os critérios de aceite (elas não afetam a versão ativa nem a busca).
