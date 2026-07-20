# Plan — Geração de resposta via Claude (RAG), streaming SSE

Depende de `spec.md` (critérios de aceite, revisado pelo `android-architect`) e das decisões já
fixadas em ADR-0004 (geração via proxy backend, streaming SSE — nota de 2026-07-16: dedup/orçamento
já são responsabilidade do retrieval, não desta feature), ADR-0005 (autenticação por `X-Api-Key` +
rate limit — **implementados aqui pela primeira vez**), ADR-0007 (estado de conversa no backend,
device-id) e ADR-0009 (organização por feature vertical). Consome `RetrievalService` (`specs/
retrieval/`) como está — nenhuma mudança em chunking/embedding/retrieval nesta feature.

## Decisão de biblioteca: SDK oficial da Anthropic (Java)

Chamadas à Claude usam o SDK oficial `com.anthropic:anthropic-java` (não uma chamada HTTP crua via
`WebClient`, ao contrário de `VoyageEmbeddingClient` — a Voyage não tem SDK Java oficial, a
Anthropic tem). Kotlin consome bibliotecas Java diretamente, sem wrapper adicional.

```kotlin
// build.gradle.kts — nova dependência
implementation("com.anthropic:anthropic-java:2.34.0")
```

`ANTHROPIC_API_KEY` só via variável de ambiente (`AnthropicOkHttpClient.fromEnv()`), nunca
hardcoded — constitution.md, seção 1.

## Modelos e parâmetros (config, não hardcode)

| Uso | Model ID | Por quê |
|---|---|---|
| Reescrita de pergunta (query rewriting) | `claude-haiku-4-5` (default, configurável) | Tarefa simples (reescrever 1 frase usando histórico curto) — não precisa do modelo mais caro. |
| Geração da resposta final | `claude-sonnet-5` (default, configurável) | Equilíbrio custo/qualidade para responder com base em contexto — ADR-0004 original já previa "Haiku por custo, Sonnet para qualidade, configurável"; mantém esse espírito com os IDs de modelo atuais. |

Sem `thinking` (adaptive ou não) nem `effort` explícito nesta primeira versão — é Q&A com contexto
curto já fornecido, não um problema de raciocínio longo; adicionar thinking aumentaria custo/
latência sem ganho claro para citar trechos já recuperados. Puramente uma escolha de custo/
simplicidade inicial, revisável depois com o `rag-evaluator`.

## Módulo e localização

Mesmo módulo Gradle único `backend/`. Pacotes novos, seguindo ADR-0009 (package-by-feature
vertical + pacote `config/` para o que é transversal):

```
backend/src/main/kotlin/com/buscai/backend/
  config/                              — NOVO, transversal (ADR-0009)
    ApiKeyFilter.kt                    — valida X-Api-Key (ADR-0005) antes de qualquer endpoint
    RateLimitFilter.kt                 — bucket simples por IP (ADR-0005)
    ApiSecurityProperties.kt           — buscai.api-key, buscai.rate-limit.*
    WebConfig.kt                       — registra os filtros (ordem: ApiKey antes de RateLimit)
  generation/                          — NOVO
    GenerationService.kt               — orquestração: rewrite (se houver histórico) → retrieval
                                          → montagem de prompt → Claude (streaming) → persistência
    GenerationProperties.kt            — buscai.generation.* (models, max-tokens, history-turns)
    conversation/
      Conversation.kt                  — entidade JPA: id, deviceId, createdAt, updatedAt
      Message.kt                       — entidade JPA: id, conversationId, role, content, createdAt
      MessageRole.kt                   — enum USER / ASSISTANT
      ConversationRepository.kt
      MessageRepository.kt
    claude/
      ClaudeClient.kt                  — porta: rewriteQuery(...): String,
                                          generate(...): Iterator<String> (streaming de deltas)
      AnthropicClaudeClient.kt         — adapter sobre com.anthropic:anthropic-java
      ClaudeProperties.kt              — buscai.claude.* (nomes de modelo — chave via env)
    web/
      ChatController.kt                — POST /chat (SSE, SseEmitter)
      ChatRequest.kt / ChatEvent.kt     — DTOs de request/eventos SSE
      ConversationController.kt        — GET /conversations, GET /conversations/{id}
      DeviceIdHeader.kt                — leitura/validação de X-Device-Id (ADR-0007)
    cli/
      GenerationDebugCommand.kt        — CommandLineRunner de debug (mesmo padrão de
                                          RetrievalDebugCommand), único consumidor além de testes
                                          até o cliente web (Fase 6/ADR-0011) existir
```

`catalog/` e `retrieval/` não são tocados por esta feature (`GenerationService` **consome**
`RetrievalService.search(query, scope)` como está — nenhuma mudança em `retrieval/`).

## Schema (Flyway V3)

```sql
CREATE TABLE conversation (
    id          UUID PRIMARY KEY,
    device_id   VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_conversation_device_id ON conversation (device_id);

CREATE TABLE message (
    id              UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversation (id),
    role            VARCHAR(20) NOT NULL,  -- USER / ASSISTANT
    content         TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_message_conversation_id ON message (conversation_id);
```

Sem tabela de citações estruturada: a resposta gerada cita livro+página **inline, em texto**
(instrução no prompt de sistema — CA1), não como metadado separado. Mais simples e suficiente para
o critério de aceite. **Dependência forward para a Fase 6:** o ADR-0004 prevê citação clicável que
"abre o trecho/página de origem" — com citação só em texto, isso exige que a spec do cliente web
inclua um endpoint futuro de recuperar chunk por id (não existe hoje); registrado aqui só para não
surpreender aquela spec depois, não é um gap desta feature.

## Contratos entre camadas

- **`ChatController` (I/O HTTP)** → `GenerationService` (única porta de entrada da lógica, mesmo
  padrão de `RetrievalService`/`IngestionService`) → `ClaudeClient` (rewrite + generate) +
  `RetrievalService.search` (retrieval) + `ConversationRepository`/`MessageRepository`
  (persistência).
- **Identificação de conversa (CA11, ADR-0007):** todo request carrega um header `X-Device-Id`
  (UUID gerado e persistido pelo cliente — hoje só `GenerationDebugCommand`/testes; no cliente web
  futuro, `localStorage`, nota já registrada em ADR-0007). `ChatRequest.conversationId` é opcional:
  ausente → `GenerationService` cria uma `Conversation` nova associada ao `deviceId` do header e
  devolve o id gerado como o primeiro evento SSE (`event: conversation`); presente → recupera o
  histórico daquela conversa (filtrando por `deviceId` — soft isolation, mesma ressalva já
  registrada no ADR-0007: não há garantia contra um device que descubra o id de outro).
  `GET /conversations/{id}` aplica o mesmo filtro por `deviceId` (404 se a conversa existe mas é de
  outro device — não 403, para não confirmar a existência do id a quem não é dono). Requisição sem
  `X-Device-Id` em qualquer um dos três endpoints é `400` explícito, antes de tocar
  `GenerationService`.
- **Query rewriting (CA4, ADR-0004 item 2):** só roda se a conversa já tem histórico (mensagens
  anteriores) — uma conversa nova pula a chamada de rewrite (economia de uma chamada Claude por
  pergunta, decisão de custo explícita). Quando roda, é uma chamada não-streaming, barata
  (`claude-haiku-4-5`), com um prompt de sistema dedicado ("reescreva a pergunta do usuário
  incorporando o contexto necessário do histórico; devolva só a pergunta reescrita, nada mais") e
  as últimas `GenerationProperties.historyTurns` mensagens como contexto.
- **Retrieval:** `GenerationService` chama `RetrievalService.search(query = perguntaFinal, scope)`
  — `perguntaFinal` é a reescrita se houve rewrite, a pergunta original caso contrário. `scope` vem
  do `ChatRequest` (`bookIds` opcional → `RetrievalScope.Books`; ausente → `AllBooks`), mesmo
  contrato já usado por `RetrievalDebugCommand`.
- **Sinal de "sem contexto relevante" (CA2) — decisão de custo:** se `RetrievalService.search`
  devolver `RetrievalResult.NoRelevantContext`, `GenerationService` **não chama a Claude para
  gerar** — devolve direto uma mensagem fixa declarando a ausência de fundamento no acervo
  (persistida como a mensagem do assistente, como qualquer outra). Evita pagar uma chamada de
  geração cujo resultado já é conhecido (constitution.md, seção 6 — custo consciente); o texto
  exato da mensagem fixa é responsabilidade da task que a implementa, não fixado aqui. Essa
  mensagem fixa passa pelo **mesmo contrato de eventos SSE** do caminho normal (`conversation` →
  `token` com o texto completo num único evento, já que não há streaming real a fazer aqui → `done`)
  — o cliente nunca precisa de um caminho de renderização especial para este caso, e CA3 (entrega
  incremental) não se aplica a uma resposta que não veio da Claude.
- **Montagem do prompt (CA1, CA6):** com `RetrievalResult.Found(chunks)`, o prompt de sistema fixo
  instrui: responder somente com base no contexto fornecido; citar livro e página **inline no
  texto** (ex. "conforme *Dom Casmurro*, p. 42, ..."); declarar explicitamente se o contexto não
  cobre algum aspecto da pergunta. O turno do usuário inclui os `RetrievedChunk` formatados (livro,
  página, capítulo, texto) seguidos da pergunta final.
- **Streaming (CA3):** `ClaudeClient.generate(...)` devolve os deltas de texto da Messages API
  (`client.messages().createStreaming(...)`, SDK oficial — ver seção "Modelos e parâmetros"),
  consumidos sincronamente (mesmo estilo blocking já usado em todo o backend — `JdbcTemplate`,
  `WebClient` bloqueante da Voyage — este projeto não usa coroutines/WebFlux, `ChatController` usa
  `org.springframework.web.servlet.mvc.method.annotation.SseEmitter`). O controller devolve o
  `SseEmitter` imediatamente e delega o trabalho bloqueante (rewrite → retrieval → geração →
  persistência) a uma thread de worker (`Executor` dedicado — não o thread pool padrão do
  Tomcat, para não competir com outras requisições); cada delta de texto vira um evento SSE
  `event: token`.
- **Persistência da resposta em streaming (decisão pendente do `android-architect` na revisão da
  spec, resolvida aqui):** a mensagem do assistente é acumulada em memória (`StringBuilder`)
  conforme os deltas chegam e só persistida (uma linha em `message`) **quando o stream da Claude
  termina normalmente** (evento `message_stop` do SDK). Se o stream falhar no meio (exceção do SDK,
  conexão caída), **nenhuma mensagem parcial é persistida** — o histórico nunca contém uma resposta
  truncada apresentada como completa (CA10); o cliente recebe um evento SSE `event: error` antes do
  emitter fechar.
- **Falha de I/O (CA10):** falha na chamada de rewrite, na chamada de retrieval (propagada crua por
  `RetrievalService`, decisão já registrada em `specs/retrieval/plan.md`), ou na chamada/streaming
  de geração — todas viram um evento SSE `event: error` com mensagem genérica ao cliente (nunca o
  detalhe cru da exceção, que poderia vazar informação de infraestrutura — CA9), sem persistir
  resposta parcial. `GenerationDebugCommand` (CLI) captura o mesmo jeito que
  `RetrievalDebugCommand` captura hoje.
- **Autenticação (CA5, CA9, ADR-0005) — implementada aqui pela primeira vez:** `ApiKeyFilter`
  (`jakarta.servlet.Filter`) valida o header `X-Api-Key` contra `ApiSecurityProperties.apiKey`
  (env var `BUSCAI_API_KEY`, constitution.md seção 1) **antes** de qualquer controller rodar —
  registrado com prioridade alta via `WebConfig`. Requisição sem chave válida recebe `401` direto
  do filtro, sem tocar `ChatController`/`GenerationService`/Claude/Voyage.
- **Rate limiting (CA8, ADR-0005):** `RateLimitFilter`, também um `jakarta.servlet.Filter` (depois
  do `ApiKeyFilter` na cadeia), contador simples em memória por IP remoto (`ConcurrentHashMap<String,
  ...>`, janela fixa de `ApiSecurityProperties.rateLimit.requestsPerMinute`) — sem biblioteca nova
  (ex. Bucket4j): ADR-0005 já descreve o requisito como "limite simples", e um contador de janela
  fixa em memória é suficiente para a escala atual (poucos usuários, um único processo backend,
  sem necessidade de estado compartilhado entre instâncias). Requisição acima do limite recebe
  `429` com corpo claro (CA8), sem chamar Claude/Voyage. Dois pontos operacionais fechados aqui
  (revisão do `android-architect`):
  - **IP real atrás do proxy (ADR-0006, Render/Fly):** a chave do rate limit é o primeiro valor de
    `X-Forwarded-For` quando presente (o proxy do provedor injeta o IP real do cliente ali),
    caindo para `request.remoteAddr` só se o header estiver ausente — sem isso, todo tráfego cairia
    num único balde (o IP do proxy) e o rate limit por-cliente não funcionaria de verdade.
  - **Eviction do mapa:** uma tarefa `@Scheduled` (já disponível via Spring, sem dependência nova)
    remove entradas mais antigas que algumas janelas, rodando a cada poucos minutos — sem isso, um
    spray de IPs distintos vaza memória indefinidamente, sério no free tier de RAM do ADR-0006.

## Execução assíncrona, transação e persistência (decisões da revisão do `android-architect`)

- **Transação na thread de worker:** a sessão JPA do request (open-session-in-view) não cobre a
  thread de worker que roda rewrite→retrieval→geração→persistência. `GenerationService` abre
  transações próprias ali (`@Transactional` em métodos privados/de serviço chamados de dentro do
  worker, não dependendo da transação do request) — uma para ler o histórico (se houver), outra
  para gravar cada `Message`. Sem isso, lazy-loading do histórico ou o insert final rodariam sem
  transação ou falhariam.
- **Ordem de persistência (fecha CA7/CA10):**
  1. Resolve/cria a `Conversation` (por `X-Device-Id` + `conversationId` opcional).
  2. Busca as últimas `historyTurns` mensagens **já existentes** (antes do turno atual) — é esse
     conjunto que alimenta o rewrite, nunca incluindo a pergunta que está sendo processada agora.
  3. Persiste a pergunta do usuário **imediatamente**, antes de chamar rewrite/retrieval/geração —
     a pergunta fica no histórico mesmo que a geração falhe depois (CA7: reabrir a conversa mostra
     a pergunta feita, mesmo sem resposta).
  4. Roda rewrite (se havia histórico) → retrieval → geração (ou mensagem fixa de
     `NoRelevantContext`).
  5. Persiste a resposta do assistente **só em caso de sucesso** (stream terminou normalmente, ou
     a mensagem fixa foi decidida) — em falha (passo 4), nenhuma linha de `Message` de assistente é
     gravada, coerente com "nunca uma resposta parcial como se fosse completa" (CA10).
- **`SseEmitter`: timeout e pool dedicado.** Timeout do `SseEmitter` configurado explicitamente
  (não o default do Spring) — generoso o bastante para cobrir cold start do backend (ADR-0006) somado
  à latência da Claude; valor exato e o tamanho do `Executor` dedicado (bounded, com fila pequena)
  ficam para a task que os implementa, com o número documentado e a razão (cold start + latência
  observada). O `Executor` tem política de rejeição própria: quando saturado, completa o
  `SseEmitter` com um evento `error` e HTTP `503`, em vez de bloquear uma thread do Tomcat
  (`CallerRunsPolicy` seria o oposto do que se quer aqui) ou enfileirar sem limite.

  > **Nota (2026-07-20, T5):** implementado como `503` HTTP puro (`ResponseStatusException`), sem
  > nunca criar/emitir nada pelo `SseEmitter` — não como um `event: error` dentro de um stream SSE já
  > aberto. `ChatController.chat` tenta submeter o trabalho ao executor **antes** de devolver o
  > `SseEmitter` ao container Servlet; se a fila está cheia, a rejeição vira a exceção síncrona que
  > produz o `503` diretamente, e o `SseEmitter` criado localmente nunca chega a ser retornado/
  > processado. Decisão confirmada no code-review da T5: mais simples, e semanticamente mais correto
  > para um cliente `EventSource` real, que nunca deveria receber um stream SSE parcialmente aberto
  > só para comunicar um erro que aconteceu *antes* do stream começar (saturação do executor, ainda
  > na thread do Tomcat) — diferente de uma falha que ocorre *durante* o pipeline (rewrite/retrieval/
  > geração), já iniciado dentro de um `SseEmitter` já devolvido, que aí sim produz `event: error`
  > (ver `ChatController.runChat`).

## Modelos de dado (resumo)

| Tipo | Campos-chave | Observação |
|---|---|---|
| `Conversation` (entidade, `generation/conversation`) | `id`, `deviceId`, `createdAt`, `updatedAt` | uma linha por conversa |
| `Message` (entidade) | `id`, `conversationId`, `role` (`MessageRole`), `content`, `createdAt` | uma linha por turno (pergunta OU resposta) |
| `ChatRequest` (DTO) | `conversationId: UUID?`, `query: String`, `bookIds: Set<String>?` | corpo de `POST /chat` |
| `ChatEvent` (sealed, SSE) | `Conversation(id)` / `Token(text)` / `Done` / `Error(message)` | eventos emitidos pelo `SseEmitter` |
| `ClaudeClient` (porta, `generation/claude`) | `rewriteQuery(query, history): String`, `generate(systemPrompt, userPrompt): Stream<String>` | interface; adapter usa o SDK oficial |

## Config nova (`application.yml`)

| Propriedade | Default | Observação |
|---|---|---|
| `buscai.claude.rewrite-model` | `claude-haiku-4-5` | modelo da reescrita de pergunta |
| `buscai.claude.answer-model` | `claude-sonnet-5` | modelo da geração final |
| `buscai.generation.max-tokens` | 2048 | `max_tokens` da chamada de geração |
| `buscai.generation.history-turns` | 6 | quantas mensagens recentes entram no rewrite/prompt |
| `buscai.api-key` | (sem default — via env `BUSCAI_API_KEY`) | ADR-0005 |
| `buscai.rate-limit.requests-per-minute` | 30 | ADR-0005, "a calibrar" — mesma ressalva de `min-cosine-similarity` no retrieval |

`ANTHROPIC_API_KEY` é lido pelo SDK oficial direto do ambiente (`AnthropicOkHttpClient.fromEnv()`),
não passa por `application.yml` — mesmo padrão de segredo só-env já usado para `VOYAGE_API_KEY`/
`BUSCAI_API_KEY` (constitution.md, seção 1).

## Como roda

`ChatController`/`ConversationController` expõem os três endpoints HTTP reais (`POST /chat`,
`GET /conversations`, `GET /conversations/{id}`) — a primeira feature do backend a fazer isso.
`GenerationDebugCommand` (CLI, `@Profile("generation-debug")`, mesmo padrão de
`RetrievalDebugCommand`) continua útil para testar o pipeline sem subir o servidor HTTP completo:

```
SPRING_PROFILES_ACTIVE=generation-debug ./gradlew bootRun --args="--query='...' --books=dom-casmurro"
```

## Gate de avaliação (constitution.md, seção 4)

Esta feature introduz o **prompt de geração** — mudança que a constitution.md (seção 4) e o
`CLAUDE.md` exigem passar pelo `rag-evaluator` contra `specs/eval/golden-set.json` antes do merge,
igual ao retrieval. Diferença aqui: além de recall@k (herdado do retrieval, inalterado), o
`rag-evaluator` também mede **groundedness** (a resposta é sustentada pelos trechos recuperados,
sem invenção) — a métrica nova que esta feature introduz. Enquanto o golden set estiver vazio
(nenhum livro real ingerido), o `rag-evaluator` reporta isso como não-bloqueante, mesmo padrão já
registrado em `specs/retrieval/tasks.md` (T9).

## Fora do plano desta feature

- Cliente real (web, Fase 6/ADR-0011) consumindo `POST /chat` — nasce como spec própria depois.
- Endpoint de exclusão de histórico — melhoria futura já registrada no ADR-0007.
- Corte automático de gasto por teto de tokens/mês — ADR-0005 já registra como melhoria futura.
- Autenticação por usuário/login — seria uma revisão do ADR-0005, fora de escopo aqui.
- Citação estruturada (metadado separado, não só texto inline) — se o cliente web precisar disso,
  é decisão da spec daquele cliente.
