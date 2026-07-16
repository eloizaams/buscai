# ADR-0010: Pacote compartilhado para o cliente de embeddings, com `input_type`

## Status
Aceito — 2026-07-16

## Contexto
Ao especificar a feature de retrieval (`specs/retrieval/spec.md`), o `android-architect` apontou
que a busca também precisa embeddar texto (a pergunta do usuário) com o mesmo modelo/versão
usado pela ingestão (consistência já exigida pelo [ADR-0003](0003-vector-db-e-embeddings.md)).
Hoje `EmbeddingClient` (porta) e `VoyageEmbeddingClient`/`VoyageClientConfig`/`VoyageProperties`
(adapter) vivem em `com.buscai.backend.ingestion.embedding`.

O [ADR-0009](0009-organizacao-de-pacotes-do-backend.md) fixa `catalog` como o pacote de *modelo de
domínio compartilhado* (entidades e os repositórios que as gravam) e diz que cada feature é um
pacote vertical que não se espalha por pacotes técnicos globais — mas não cobre o caso de uma
*porta de infraestrutura* (não uma entidade) precisar ser compartilhada por mais de uma feature.
Duas opções óbvias, ambas erradas:

- Mover o client para `catalog`: mistura infraestrutura (HTTP client, config de provedor externo,
  tratamento de erro de rede) no pacote que o ADR-0009 quer manter restrito a domínio/persistência.
- Fazer `retrieval` depender de `ingestion`: inverte o grafo de dependências — `ingestion` é uma
  feature-folha (tem CLI própria, PDFBox, chunking), não uma base para outras features.

Separadamente, o port atual (`embed(texts): List<FloatArray>`) não distingue o tipo de texto
embeddado. A Voyage AI (`voyage-3`) suporta embeddings assimétricos via parâmetro `input_type`
(`document` para o conteúdo indexado, `query` para a pergunta de busca) — usar o tipo certo
melhora recall de forma mensurável. Reusar o método sem distinção embeddaria a pergunta como se
fosse um documento, degradando recall silenciosamente (sem erro, sem teste que capture isso a
não ser o eval de RAG).

## Decisão
- **Novo pacote técnico compartilhado `com.buscai.backend.embedding`**, irmão de `catalog` sob
  `com.buscai.backend` — não uma feature, não domínio: só a porta e o adapter de embeddings.
  Move mecânico de `ingestion.embedding` para lá: `EmbeddingClient`, `EmbeddingClientException`,
  `VoyageEmbeddingClient`, `VoyageClientConfig`, `VoyageProperties`. `ingestion` e `retrieval`
  dependem de `embedding`; `embedding` não depende de nenhuma feature. `catalog` permanece restrito
  a entidades/repositórios, sem tocar nesta mudança.
- **Regra geral (fecha a lacuna do ADR-0009):** capacidade de infraestrutura compartilhada entre
  duas ou mais features (cliente HTTP, adapter de provedor externo) vive em pacote técnico
  compartilhado próprio sob `com.buscai.backend`, nunca em `catalog` (reservado a domínio) nem
  dentro de uma feature-folha.
- **Porta estendida com `input_type`:** `embed(texts: List<String>, inputType: EmbeddingInputType)`,
  com `enum EmbeddingInputType { DOCUMENT, QUERY }` mapeado 1:1 para o parâmetro `input_type` da
  Voyage API. A ingestão passa `DOCUMENT` (comportamento equivalente ao atual); o retrieval passa
  `QUERY`. Isso não quebra a consistência de modelo/versão do ADR-0003 — `input_type` não é modelo
  nem versão, é um parâmetro por chamada; o `embeddingModelVersion` gravado por chunk continua
  inalterado.
- **Query nativa de busca híbrida (tsvector/pgvector/RRF) não entra em `embedding` nem em
  `catalog`**: mora num repositório/DAO local da feature `retrieval`, mantendo `ChunkRepository`
  (em `catalog`) restrito ao CRUD que a ingestão já usa.

## Alternativas consideradas
- **Mover para `catalog`.** Descartada: mistura infraestrutura (WebClient, config de provedor) no
  pacote que o ADR-0009 definiu como domínio/persistência puro; arrastaria dependências de rede
  para o núcleo mais estável do backend.
- **`retrieval` depender de `ingestion`.** Descartada: acopla o ciclo de vida de retrieval ao de
  uma feature-folha com responsabilidades não relacionadas (CLI, PDFBox); contradiz o desenho
  vertical do ADR-0009.

## Consequências
- Move mecânico (renomear pacote, ajustar imports), sem alterar lógica — mesmo padrão de risco
  baixo do ADR-0009, validado por `./gradlew test`. Não altera schema, migrations nem
  comportamento em runtime do que já existe.
- Assinatura de `EmbeddingClient.embed` muda (parâmetro novo obrigatório): todo chamador existente
  (`IngestionService`) precisa ser atualizado para passar `EmbeddingInputType.DOCUMENT`
  explicitamente — é código de produção sendo tocado, não só reorganização de pastas; entra como
  task própria no `plan.md` da feature de retrieval, com os testes existentes de ingestão como
  rede de segurança.
- Fecha a lacuna do ADR-0009 para features futuras (geração, se um dia precisar de outro client
  compartilhado): a regra "infra compartilhada vai em pacote técnico próprio, nunca em `catalog`
  nem em feature-folha" passa a valer para qualquer decisão parecida, sem precisar de uma ADR nova
  a cada caso.
