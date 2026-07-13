# ADR-0009: Organização de pacotes do backend — package-by-feature vertical

## Status
Aceito — 2026-07-13

## Contexto
Com a feature de ingestão implementada (`specs/ingestao-pdf/`), o backend passou a ter dois pacotes
sob `com.buscai.backend` organizados por **princípios diferentes**, o que dificulta a leitura:

- `book/` — organizado por **camada técnica**: agrupa o modelo de persistência (entidades JPA
  `Book`/`BookVersion`/`Chunk`, o enum `BookVersionStatus` e os repositórios Spring Data).
- `ingestion/` — organizado por **feature**: agrupa, achatados num único pacote, 13 arquivos de
  papéis distintos — orquestração de aplicação (`IngestionService`, `IngestionOutcome`), lógica de
  domínio (`Chunker`, `TextCleaner`, `ChunkValidator`), adapters de infraestrutura
  (`PdfTextExtractor`, `ScannedPdfDetector`, `VoyageEmbeddingClient` e sua config/properties),
  a porta `EmbeddingClient` e a entrada de linha de comando (`IngestCommand`).

Misturar "por dado" e "por funcionalidade" lado a lado, somado ao pacote `ingestion/` achatado com
papéis heterogêneos, faz o leitor não achar um padrão previsível — o código em si está coeso e bem
testado (ports/adapters já presentes onde importa, camada de serviço concentrando o acesso a
repositório conforme `CLAUDE.md`), mas a *organização física* está inconsistente.

O backend vai crescer **por feature** (retrieval, geração via proxy Claude — [ADR-0004](0004-geracao-via-proxy-backend.md), 
autenticação e rate limit — [ADR-0005](0005-autenticacao-e-limites.md), estado de conversa — [ADR-0007](0007-estado-de-conversa.md)), 
não por camada técnica. Isso torna a escolha do princípio de organização uma decisão de arquitetura que precisa ser fixada antes de a
inconsistência se multiplicar entre features futuras.

## Decisão
Adotar **package-by-feature vertical consistente**, com sub-divisão interna leve por papel apenas
quando o pacote de uma feature ganha peso. Regras:

- **Cada feature é um pacote vertical** sob `com.buscai.backend` (`ingestion`, `retrieval`,
  `generation`, ...). A feature contém sua orquestração de aplicação, seu domínio, seus adapters de
  infraestrutura e seus pontos de entrada — não se espalha por pacotes técnicos globais.
- **Modelo de domínio compartilhado entre features** vive num pacote de domínio explícito,
  `catalog` (renomeação do atual `book`): entidades `Book`/`BookVersion`/`Chunk`, o enum de status e
  os repositórios que as gravam. O nome deixa claro que é o modelo do acervo compartilhado, não um
  pacote "por dado" isolado. Ingestão, retrieval e geração dependem de `catalog`; `catalog` não
  depende de nenhuma feature.
- **Split interno por papel dentro de uma feature**, criado só quando ganha densidade (evita
  pastas de um arquivo só). Para `ingestion`, o alvo é:
  - raiz da feature: `IngestionService`, `IngestionOutcome`, `IngestionProperties` (aplicação);
  - `chunking/`: `Chunker`, `TextCleaner`, `ChunkValidator`;
  - `pdf/`: `PdfTextExtractor`, `ScannedPdfDetector`;
  - `embedding/`: `EmbeddingClient` (porta) + `VoyageEmbeddingClient`/`VoyageClientConfig`/`VoyageProperties` (adapter);
  - `cli/`: `IngestCommand`.
- **Ports & adapters permanecem como já estão** (interface no domínio da feature, implementação
  concreta ao lado): esta ADR reorganiza pacotes, não introduz uma camada nova nem mappers
  domínio↔entidade.
- **Config transversal** (não pertencendo a uma única feature) vai num pacote `config`.

A reorganização é mecânica: mover arquivos e ajustar `package`/imports, sem alterar lógica; o build
(`./gradlew test`) é a rede de segurança.

## Alternativas consideradas
- **Camadas técnicas clássicas** (`domain/`, `application/`, `infrastructure/`, `interfaces/`
  globais). Descartada: como o backend cresce por feature, entender uma feature exigiria saltar
  entre quatro pacotes e o `application/` global viraria um "god package" que incha a cada feature.
- **Hexagonal/Clean "full"** (módulos Gradle separados por camada, mappers domínio↔entidade
  explícitos). Descartada por over-engineering na escala atual (~1.600 linhas, um operador): o
  boilerplate de mappers e a multiplicação de módulos custam mais do que entregam agora. Pode ser
  revisitada se o domínio e o time crescerem.

## Consequências
- **Positivo:** organização previsível e consistente entre features; cada papel dentro de uma
  feature tem um lar óbvio; onboarding e navegação mais rápidos; preserva os ports/adapters e a
  camada de serviço já existentes; refactor de baixo risco (move de arquivos, sem reescrita).
- **Custo pontual:** renomear `book` → `catalog` e mover os arquivos de `ingestion/` para os
  sub-pacotes toca imports em várias classes e nos testes — um commit mecânico dedicado, validado
  por `./gradlew test`. Não altera schema, migrations nem comportamento em runtime.
- **Compromisso a manter:** features futuras (retrieval, geração, auth, estado de conversa) nascem
  como pacote vertical próprio dependendo de `catalog`; nova entidade compartilhada entre features
  entra em `catalog`, não num pacote "por dado" avulso. Divergir disso reabre a inconsistência que
  esta ADR fecha.
