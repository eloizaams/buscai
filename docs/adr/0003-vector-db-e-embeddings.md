# ADR-0003: Vector DB e modelo de embeddings (no backend)

## Status
Aceito — 2026-07-12

## Contexto
Com ingestão e retrieval movidos para o backend ([ADR-0001](0001-arquitetura-geral-backend-completo.md)),
as opções on-device do planejamento original (ObjectBox, ONNX Runtime Android) deixam de se aplicar.
Livros são majoritariamente em PT-BR — o modelo de embedding precisa ser multilíngue com boa
qualidade em português.

## Decisão
- **Vector DB:** **pgvector** (extensão do PostgreSQL), em vez de um serviço vetorial dedicado
  (Qdrant). Um único banco relacional guarda livros, chunks, vetores e (futuramente) histórico de
  conversas; permite busca híbrida (full-text nativo do Postgres `tsvector`/`ts_rank` + `pgvector`
  para cosine/HNSW) com fusão RRF em uma única query, sem operar infraestrutura extra. Reavaliar
  Qdrant só se o volume de chunks ou os requisitos de latência justificarem um serviço dedicado.
- **Embeddings:** **Voyage AI** (multilíngue, ex. `voyage-3` ou variante `-lite`) via API — é a
  recomendação oficial da Anthropic para embeddings (a Anthropic não tem API própria de
  embeddings). Evita hospedar/operar um modelo ONNX no servidor.
  - **Alternativa** (se custo por chamada ou a exigência de não enviar trechos dos livros a um
    terceiro para embedding forem restritivos): self-hosting de `multilingual-e5-small` ou
    `paraphrase-multilingual-MiniLM-L12-v2` via ONNX Runtime (Java) no próprio backend.
- O **mesmo modelo e versão de embedding** deve ser usado na ingestão (CLI) e na query (backend de
  chat). O nome/versão do modelo é gravado junto dos chunks para permitir detectar e forçar
  reindexação caso o modelo mude.

## Consequências
- Simplifica operação: um único Postgres gerido cobre dados relacionais + vetoriais.
- Depender de uma API externa de embeddings introduz custo por token e uma dependência de rede a
  mais durante a ingestão (aceitável, já que a ingestão é um processo offline/batch feito pelo
  desenvolvedor, não um caminho quente do usuário).
- Latência de busca (< 100 ms com ~50k chunks, meta da Fase 4 original) precisa ser validada com
  pgvector + HNSW no hardware real do backend antes de assumir que a meta se mantém.

## Detalhes fixados na implementação (feature de ingestão, `specs/ingestao-pdf/`)
Registrado aqui pela T12 (`specs/ingestao-pdf/tasks.md`) para não deixar decisões concretas só no
código:
- Modelo de embedding fixado como `voyage-3` (não a variante `-lite`), dimensão **1024** —
  `chunk.embedding vector(1024)` (`V1__book_bookversion_chunk.sql`), constante
  `EMBEDDING_DIMENSIONS` em `Chunk.kt`. Trocar de modelo/dimensão exige migration nova (o schema
  fixa a dimensão da coluna) e dispara reindexação de todo o acervo via a chave de gatilho do
  ADR-0008 (`embeddingModelVersion`).
- Índice HNSW usa `vector_cosine_ops` (busca por similaridade de cosseno, coerente com "cosine/HNSW"
  já citado acima) — `idx_chunk_embedding_hnsw` na mesma migration, parâmetros default do pgvector
  (não ajustados manualmente; revisar se a latência medida em produção não bater com a meta acima).

## Detalhes fixados na implementação (feature de retrieval, `specs/retrieval/`)
Registrado aqui pela T9 (`specs/retrieval/tasks.md`), 2026-07-17:
- Busca híbrida implementada exatamente como prevista acima: `HybridSearchDao` funde ramo léxico
  (`tsvector`/GIN, `ts_rank`) e ramo vetorial (`pgvector`/HNSW, cosine) por RRF numa única query SQL
  nativa (T3) — nenhuma divergência de arquitetura.
- Meta de latência (< 100 ms com ~50 mil chunks) passou a ser um **gate automatizado de CI**
  (`HybridSearchDaoVolumeTest`, T8), medida em execução "quente" (warm-up descartado) sobre
  Testcontainers/Postgres com `pgvector/pgvector:pg16`. Isso valida a meta no ambiente de teste,
  não ainda uma medição em hardware de produção real (Render/Fly, ADR-0006) — se a latência de
  produção divergir da meta, é débito técnico a registrar explicitamente em `tasks.md`, não motivo
  para relaxar o teste (mesmo critério já fixado na T8).
- A busca híbrida ganhou um **terceiro ramo estruturado exato** — a CTE `exact_rank` no mesmo
  `HybridSearchDao`, para lookup direto por número de item numerado (`busca-exata-item`, T4,
  2026-07-23). Racional completo na nota do ADR-0013 (seção 4).
