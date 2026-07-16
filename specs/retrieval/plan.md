# Plan — Busca e retrieval sobre o acervo ingerido

Depende de `spec.md` (critérios de aceite) e do parecer do `android-architect` (registrado em
[ADR-0010](../../docs/adr/0010-pacote-compartilhado-embedding.md)). Segue as decisões já fixadas
em ADR-0003 (pgvector, Voyage AI, busca híbrida com RRF), ADR-0004 (retrieval é passo interno do
futuro `/chat`, não endpoint próprio), ADR-0008 (swap atômico — só a versão `READY` ativa é
buscável) e ADR-0009 (organização por feature vertical).

## Módulo e localização

Mesmo módulo Gradle único `backend/`. Dois pacotes novos, mais um pacote existente movido
(ADR-0010):

```
backend/src/main/kotlin/com/buscai/backend/
  embedding/                        — NOVO, compartilhado (ADR-0010), movido de ingestion/embedding/
    EmbeddingClient.kt              — porta: embed(texts, inputType): List<FloatArray>
    EmbeddingInputType.kt           — enum DOCUMENT / QUERY (mapeia para "input_type" da Voyage)
    VoyageEmbeddingClient.kt        — adapter HTTP (mesmo contrato de hoje + campo input_type)
    VoyageClientConfig.kt
    VoyageProperties.kt
  ingestion/
    IngestionService.kt             — TOCADO: embeddingClient.embed(texts, EmbeddingInputType.DOCUMENT)
                                       (única mudança de comportamento nesta feature sobre código
                                       existente — resto do pipeline de ingestão intocado)
  retrieval/                        — NOVO
    RetrievalService.kt             — orquestração (aplicação): único ponto de entrada da lógica
    RetrievalProperties.kt          — binding de buscai.retrieval.*
    RetrievalResult.kt              — sealed class: Found(chunks) / NoRelevantContext
    RetrievedChunk.kt               — chunkId, bookId, bookTitle, page, chapter, text, score
    RetrievalScope.kt               — sealed class: AllBooks / SingleBook(bookId)
    search/
      HybridSearchDao.kt            — query nativa (tsvector + pgvector + fusão RRF numa query só)
      HybridSearchRow.kt            — projeção crua da query (chunkId, cosineSimilarity, rrfScore)
    context/
      ContextAssembler.kt           — dedup de chunks vizinhos + orçamento de tokens (dono desta
                                       responsabilidade, não a geração — ver nota no ADR-0004)
    cli/
      RetrievalDebugCommand.kt      — CommandLineRunner de debug (ver "Como roda" abaixo)
```

`catalog/` não é tocado por esta feature (nenhuma entidade nova, nenhum método novo em
`ChunkRepository`/`BookRepository`/`BookVersionRepository` — a query híbrida vive em
`retrieval/search/`, não em `catalog`, conforme ADR-0010).

## Como roda (sem endpoint HTTP — ADR-0004)

`RetrievalService` é chamado em processo. Hoje, sem a feature de geração (Fase 5) ainda
especificada, o único consumidor é `RetrievalDebugCommand`: um `CommandLineRunner` sob profile
dedicado (`@Profile("retrieval-debug")`), espelhando exatamente o padrão de `IngestCommand`
(`specs/ingestao-pdf/plan.md` — confirmado em `TESTE_BACKEND.md`, ativação via
`SPRING_PROFILES_ACTIVE`, não argumento posicional):

```
SPRING_PROFILES_ACTIVE=retrieval-debug ./gradlew bootRun --args="--query='qual o nome do protagonista' --book=dom-casmurro"
SPRING_PROFILES_ACTIVE=retrieval-debug ./gradlew bootRun --args="--query='qual o nome do protagonista'"   # todos os livros
```

Imprime os chunks retornados (`bookId`, página, capítulo, score, trecho do texto) em formato
legível — é o que o `rag-evaluator` roda contra `specs/eval/golden-set.json` (campo
`expected_sources`) para medir recall@k antes de a geração existir. Quando a Fase 5 for
especificada, ela chama `RetrievalService` diretamente do handler de `POST /chat`; nenhuma
reescrita aqui.

## Schema (Flyway V2)

`chunk` ganha uma coluna de full-text search **gerada** (não mapeada no JPA — `Chunk.kt`,
`catalog`, não muda; a leitura é só via SQL nativo em `HybridSearchDao`):

```sql
ALTER TABLE chunk
    ADD COLUMN text_search tsvector
        GENERATED ALWAYS AS (to_tsvector('portuguese', text)) STORED;

CREATE INDEX idx_chunk_text_search_gin ON chunk USING gin (text_search);
```

Config de idioma fixada em `portuguese` (acervo majoritariamente PT-BR, ADR-0003) — premissa
registrada aqui; um livro em outro idioma fica com o ramo léxico subótimo (aceitável, não é
critério de aceite desta feature).

## Contratos entre camadas

- `RetrievalDebugCommand` (I/O de linha de comando) → `RetrievalService` (única porta de entrada
  da lógica, mesmo padrão de `IngestionService`) → `EmbeddingClient.embed(listOf(query),
  EmbeddingInputType.QUERY)` → `HybridSearchDao.search(...)` → `ContextAssembler.assemble(...)` →
  `RetrievalResult`.
- **Resolução de escopo → versões ativas buscáveis (CA2/CA6):** antes de chamar
  `HybridSearchDao`, `RetrievalService` resolve o conjunto de `BookVersion.id` elegíveis:
  - `RetrievalScope.SingleBook(bookId)`: só o `activeVersionId` daquele `Book`, se existir e
    estiver `READY`; senão, conjunto vazio (busca não encontra nada — não é erro, ver CA7).
  - `RetrievalScope.AllBooks`: `activeVersionId` de todo `Book` com `activeVersionId != null`,
    **filtrado** por `embeddingModel`/`embeddingModelVersion` iguais aos configurados em
    `VoyageProperties` atual (premissa explícita, apontada pelo `android-architect`: buscar no
    acervo inteiro assume que todo livro ativo compartilha o mesmo espaço vetorial da query
    recém-embeddada; hoje é sempre verdade por não haver mistura de modelo, mas o filtro evita
    misturar espaços vetoriais incompatíveis caso um livro seja reindexado com modelo novo e
    outro não). Conjunto vazio (nenhum livro pronto) é um resultado válido, não erro.
  - Conjunto vazio de versões elegíveis pula a query híbrida e devolve `NoRelevantContext`
    diretamente — não é necessário perguntar ao banco.
- **`HybridSearchDao.search`:** dado o vetor da query, o texto da query e o conjunto de
  `bookVersionId` elegíveis, roda internamente dois ramos e funde por RRF **numa única query
  SQL nativa** (ADR-0003):
  1. Ramo vetorial: top-N por `embedding <=> :queryVector` (cosine distance, índice HNSW),
     restrito às versões elegíveis.
  2. Ramo léxico: top-N por `ts_rank(text_search, plainto_tsquery('portuguese', :queryText))`
     onde `text_search @@ plainto_tsquery(...)`, mesmo filtro de versões.
  3. Fusão RRF (`score = Σ 1 / (k + rank)` por ramo em que o chunk aparece, `k` configurável via
     `RetrievalProperties`) ordena o resultado combinado.
  Devolve, por chunk candidato, **tanto** o `rrfScore` (ordenação final) **quanto** a
  `cosineSimilarity` bruta do ramo vetorial (não é o mesmo número — RRF é por rank, não por
  distância; ver próximo ponto).
- **Sinal de "sem contexto relevante" (CA7):** calculado sobre a **melhor `cosineSimilarity`**
  entre os candidatos retornados, não sobre o `rrfScore` (RRF sempre produz um topo com *algum*
  rank, mesmo quando nada é de fato relevante — score de rank não é limiar de relevância).
  `RetrievalService` compara contra `RetrievalProperties.minCosineSimilarity`; abaixo do limiar,
  devolve `RetrievalResult.NoRelevantContext` mesmo que `HybridSearchDao` tenha devolvido linhas.
- **`ContextAssembler.assemble` (dono de dedup + orçamento — nota no ADR-0004):**
  1. Ordena candidatos por `rrfScore` desc.
  2. Dedup de vizinhos: dois chunks da mesma `bookVersionId` cujas janelas de `charOffset` se
     sobrepõem significativamente (limiar configurável) são tratados como redundantes — mantém
     só o de maior `rrfScore`, descarta o outro (CA4).
  3. Orçamento de tokens: acumula `chunk.tokenCount` (já persistido por chunk na ingestão — não
     retokeniza nada aqui) na ordem de relevância até `RetrievalProperties.tokenBudget`; corta o
     restante (CA5).
  4. Devolve `List<RetrievedChunk>` já no formato final (com `bookTitle` resolvido via `catalog`
     para citação), ou lista vazia se não sobrou nada — que `RetrievalService` também trata como
     `NoRelevantContext`.

## Modelos de dado (resumo)

| Tipo | Campos-chave | Observação |
|---|---|---|
| `RetrievalScope` (sealed) | `AllBooks` / `SingleBook(bookId: String)` | entrada de `RetrievalService.search` |
| `HybridSearchRow` | `chunkId`, `bookVersionId`, `page`, `chapter`, `text`, `tokenCount`, `cosineSimilarity`, `rrfScore` | projeção crua de `HybridSearchDao`, não persistida |
| `RetrievedChunk` | `chunkId`, `bookId`, `bookTitle`, `page`, `chapter`, `text`, `score` | saída final, formato de citação (livro + página) |
| `RetrievalResult` (sealed) | `Found(chunks: List<RetrievedChunk>)` / `NoRelevantContext` | devolvido por `RetrievalService.search` |
| `EmbeddingInputType` (enum, pacote `embedding`) | `DOCUMENT` / `QUERY` | ADR-0010; ingestão usa `DOCUMENT`, retrieval usa `QUERY` |

`chunk.text_search` (Flyway V2) é a única mudança de schema — não vira campo em `Chunk.kt`
(`catalog`), só é lido via SQL nativo em `HybridSearchDao`.

## Config nova (`application.yml`, `buscai.retrieval.*`)

| Propriedade | Default sugerido | Uso |
|---|---|---|
| `vector-candidates` | 50 | top-N do ramo vetorial antes da fusão |
| `lexical-candidates` | 50 | top-N do ramo léxico antes da fusão |
| `top-k` | 8 | quantos candidatos pós-fusão entram no `ContextAssembler` |
| `rrf-k` | 60 | constante `k` da fórmula RRF (valor usual na literatura) |
| `token-budget` | 3000 | orçamento de tokens do contexto final (CA5) |
| `min-cosine-similarity` | 0.5 | limiar do sinal "sem contexto relevante" (CA7) — a calibrar |
| `neighbor-dedup-min-overlap-chars` | metade do overlap mínimo do chunking (ADR-0002) | limiar de dedup de vizinhos (CA4) |

Defaults marcados "a calibrar" (`min-cosine-similarity` principalmente) são um risco conhecido:
ficam explícitos em `tasks.md` como algo a validar contra o golden set assim que ele tiver
perguntas reais, não um número definitivo fixado por esta spec.

## Fora do plano desta feature

- Endpoint HTTP (`/chat` ou `/search`) — feature futura (Fase 5, ADR-0004); este plano só expõe
  `RetrievalService` in-process + o comando de debug.
- Re-ranking via cross-encoder/LLM sobre os candidatos pós-fusão — v2 (planejamento original).
- Query rewriting multi-turno — responsabilidade da geração (ADR-0007/ADR-0004), que tem acesso
  ao histórico; `RetrievalService.search` recebe sempre uma pergunta já "final".
- Ajuste fino automático de `min-cosine-similarity`/pesos RRF — fica manual via `application.yml`
  até existir um processo de calibração baseado no golden set preenchido.
