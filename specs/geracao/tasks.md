# Tasks — Geração de resposta via Claude (RAG), streaming SSE

Ordem sequencial — cada item depende do(s) anterior(es) e cabe numa sessão do
`kotlin-implementer`. Todos em `backend/`. Rodar `./gradlew ktlintFormat` e os testes do módulo ao
final de cada task (regra já fixada em `.claude/agents/kotlin-implementer.md`).

- [x] **T1 — `ClaudeClient`: porta + adapter sobre o SDK oficial (`com.anthropic:anthropic-java`)**
  Adicionar `implementation("com.anthropic:anthropic-java:2.34.0")` a `backend/build.gradle.kts`.
  Pacote novo `com.buscai.backend.generation.claude`: `ClaudeClient` (interface —
  `rewriteQuery(query: String, history: List<HistoryTurn>): String` e
  `generate(systemPrompt: String, userPrompt: String, onToken: (String) -> Unit)` — a segunda
  consome o stream do SDK oficial (`client.messages().createStreaming(...)`, ver
  `specs/geracao/plan.md`, seção "Modelos e parâmetros") e invoca `onToken` a cada delta de texto,
  bloqueante, sem devolver nada até o stream terminar — decisão de manter a interface simples e
  síncrona, coerente com o resto do backend, que não usa coroutines/WebFlux); `AnthropicClaudeClient`
  (adapter, `AnthropicOkHttpClient.fromEnv()` — `ANTHROPIC_API_KEY` só via variável de ambiente,
  constitution.md seção 1, nunca hardcoded nem logado); `ClaudeProperties` (`buscai.claude.*` —
  `rewriteModel` default `claude-haiku-4-5`, `answerModel` default `claude-sonnet-5`, ver plan.md
  "Config nova"). `HistoryTurn` é um tipo simples (role + texto) que a T4 (`GenerationService`) vai
  popular a partir de `Message`. Teste: `AnthropicClaudeClientTest` com o SDK real contra um
  servidor HTTP fake (WireMock ou similar, sem chamar a Anthropic de verdade) confirmando que (a)
  `rewriteQuery` monta o request com o modelo/prompt esperados e devolve o texto da resposta; (b)
  `generate` invoca `onToken` uma vez por delta de texto recebido no SSE simulado, na ordem certa;
  (c) uma falha de rede/HTTP na chamada propaga uma exceção (não engole silenciosamente).

- [x] **T2 — Migration V3: `Conversation`/`Message` + repositórios**
  `V3__conversation_message.sql` (Flyway): tabelas `conversation` e `message` exatamente como
  `specs/geracao/plan.md`, seção "Schema", especifica (incluindo os dois índices). Pacote novo
  `com.buscai.backend.generation.conversation`: entidades JPA `Conversation` (`id`, `deviceId`,
  `createdAt`, `updatedAt`) e `Message` (`id`, `conversationId`, `role` — enum `MessageRole` em
  arquivo próprio, `USER`/`ASSISTANT` —, `content`, `createdAt`), `ConversationRepository`
  (Spring Data — precisa de um finder por `deviceId`, ordenado por `updatedAt` desc, para o futuro
  `GET /conversations`) e `MessageRepository` (finder por `conversationId`, ordenado por
  `createdAt` asc, para reconstruir histórico). Teste via Testcontainers: persistir uma
  `Conversation` + duas `Message`s (uma de cada `role`) e confirmar que os finders devolvem a
  ordem esperada; uma `Message` referenciando um `conversationId` inexistente viola a FK (teste
  negativo simples, confirma a constraint do schema).

- [x] **T3 — `config/`: `ApiKeyFilter` + `RateLimitFilter` + `WebConfig` (ADR-0005, primeira
  implementação em código)**
  Pacote novo `com.buscai.backend.config`. `ApiSecurityProperties` (`buscai.api-key` — sem default,
  só via env `BUSCAI_API_KEY`; `buscai.rate-limit.requests-per-minute`, default 30 — "a calibrar",
  mesma ressalva de `min-cosine-similarity` no retrieval). `ApiKeyFilter` (`jakarta.servlet.Filter`):
  compara o header `X-Api-Key` do request contra `ApiSecurityProperties.apiKey`; ausente ou
  divergente → `401` direto do filtro (corpo de erro simples, sem detalhe interno), sem deixar a
  requisição chegar a nenhum controller. `RateLimitFilter` (outro `Filter`, registrado depois do
  `ApiKeyFilter` na cadeia): contador em memória por IP (`ConcurrentHashMap<String, ...>`, janela
  fixa de um minuto) — chave do IP é o primeiro valor de `X-Forwarded-For` quando presente (proxy
  do Render/Fly, ADR-0006), caindo para `request.remoteAddr` só na ausência do header (documentar
  esse motivo no KDoc); acima do limite → `429` com corpo claro (CA8). Uma tarefa `@Scheduled`
  (Spring, sem dependência nova) remove do mapa entradas mais antigas que algumas janelas, evitando
  vazamento de memória sob spray de IPs distintos (ADR-0006, free tier de RAM). `WebConfig`
  registra os dois filtros na ordem `ApiKeyFilter` → `RateLimitFilter`. Teste: `ApiKeyFilterTest`/
  `RateLimitFilterTest` unitários (sem subir o Spring context inteiro, só o filtro com um
  `MockHttpServletRequest`/`Response` e uma `FilterChain` fake) — chave ausente/errada barra antes
  da chain seguir; chave certa deixa passar; N+1 requisições na mesma janela do mesmo IP barram a
  N+1-ésima com 429; IPs diferentes têm contadores independentes; `X-Forwarded-For` presente usa
  esse valor como chave, não o `remoteAddr`.

- [x] **T4 — `GenerationService`: orquestração completa**
  `com.buscai.backend.generation.GenerationService`, seguindo a ordem de persistência fixada em
  `specs/geracao/plan.md` ("Execução assíncrona, transação e persistência"): resolve/cria a
  `Conversation` (por `deviceId` + `conversationId` opcional); busca as últimas
  `GenerationProperties.historyTurns` mensagens já existentes (antes do turno atual); **persiste a
  pergunta do usuário imediatamente**, antes de rewrite/retrieval/geração; roda `ClaudeClient.
  rewriteQuery` só se havia histórico (mesmo `HistoryTurn` de T1); chama `RetrievalService.search`
  (T do retrieval, já existente) com a pergunta final e o `RetrievalScope` do request; se
  `NoRelevantContext`, monta a mensagem fixa (texto a definir nesta task) e persiste como resposta
  do assistente; se `Found`, monta o prompt (sistema fixo com as instruções de CA1/CA2 do
  `spec.md` + turno do usuário com os `RetrievedChunk` formatados + pergunta final) e chama
  `ClaudeClient.generate`, acumulando os deltas recebidos via callback num buffer; ao terminar com
  sucesso, persiste a resposta acumulada como `Message` do assistente; em caso de exceção durante
  `generate`, **não persiste nenhuma linha de assistente** e relança (ou devolve um resultado de
  erro tipado — decisão da task) para o chamador (T5) tratar. `GenerationProperties`
  (`buscai.generation.*` — `maxTokens` default 2048, `historyTurns` default 6). **Critério
  explícito de conclusão desta task (apontado no review da T1):** remover a constante
  `ANSWER_MAX_TOKENS` de `AnthropicClaudeClient` e conectar `ClaudeClient.generate` ao valor de
  `GenerationProperties.maxTokens` (seja adicionando um parâmetro `maxTokens` à assinatura, seja
  outro canal) — não deixar a constante sobreviver em paralelo à config nova. Transações: os
  métodos que leem histórico e gravam `Message` usam `@Transactional` próprios (não dependem da
  sessão do request — esta classe já é pensada para rodar fora do thread do Tomcat, ver T5). Teste
  via Testcontainers com um `ClaudeClient` fake determinístico (sem chamar a Anthropic real):
  conversa nova não chama rewrite; conversa com histórico chama rewrite antes do retrieval;
  `NoRelevantContext` do retrieval produz a mensagem fixa sem chamar `generate`; `Found` monta o
  prompt esperado (asserção sobre o que foi passado ao `ClaudeClient` fake) e persiste a resposta
  completa acumulada dos deltas; uma exceção do `ClaudeClient` fake durante `generate` não deixa
  nenhuma `Message` de assistente persistida, mas a pergunta do usuário já está persistida antes
  disso (CA7/CA10). Ao gravar qualquer `Message` nova (pergunta ou resposta), atualizar
  `Conversation.updatedAt` — o finder `findByDeviceIdOrderByUpdatedAtDesc` (T2) só ordena
  corretamente `GET /conversations` (T6) se esse campo refletir a última atividade real.

- [x] **T5 — `ChatController`: `POST /chat` via `SseEmitter`**
  Pacote `com.buscai.backend.generation.web`. `ChatRequest` (DTO: `conversationId: UUID?`,
  `query: String`, `bookIds: Set<String>?`) e o contrato de eventos SSE (`event: conversation` com
  o id — só quando uma conversa nova foi criada —, `event: token` por delta de texto, `event: done`
  ao final, `event: error` com mensagem genérica em qualquer falha — nunca o detalhe cru da
  exceção, CA9). `ChatController.chat` lê e valida `X-Device-Id` (ausente → `400` antes de tocar
  `GenerationService`), devolve um `SseEmitter` com timeout explícito (valor generoso o bastante
  para cobrir cold start do ADR-0006 + latência da Claude — documentar o número escolhido e a
  razão no código) e delega o trabalho bloqueante (chamada a `GenerationService`) a um `Executor`
  dedicado, *bounded*, com fila pequena e uma política de rejeição própria que completa o
  `SseEmitter` com `event: error` e `503` quando saturado (nunca `CallerRunsPolicy`, que bloquearia
  uma thread do Tomcat). Teste via `MockMvc`/`TestRestTemplate` (Spring context, `ClaudeClient` fake
  no lugar do real): requisição sem `X-Api-Key` válido nunca chega ao controller (barrada por T3,
  `401`); sem `X-Device-Id` → `400`; conversa nova recebe `event: conversation` com um id antes dos
  `event: token`; os deltas chegam na ordem certa seguidos de `event: done`; conversa existente não
  recebe `event: conversation`; forçar uma falha no `ClaudeClient` fake produz `event: error` sem
  nenhum `event: token` incompleto sendo apresentado como final.

  > **Nota (2026-07-20, T5):** saturação do executor devolve `503` HTTP puro (sem `event: error`
  > via SSE) — ver nota equivalente em `plan.md`, seção "Execução assíncrona, transação e
  > persistência". Além disso, `GenerationService.answer` ganhou um parâmetro
  > `onConversationResolved: (conversationId, isNew) -> Unit`, chamado antes de qualquer rewrite/
  > retrieval/geração: é assim que `ChatController` sabe emitir `event: conversation` no momento
  > certo sem chamar `ConversationStore` diretamente (o que romperia `GenerationService` como única
  > porta de entrada da lógica, apontado no code-review desta task).

- [x] **T6 — `ConversationController`: `GET /conversations`, `GET /conversations/{id}`**
  Mesmo pacote `generation.web`. `GET /conversations` (requer `X-Device-Id`, `400` se ausente):
  lista as conversas daquele device, ordenadas por `updatedAt` desc. `GET /conversations/{id}`:
  devolve as mensagens da conversa, ordenadas por `createdAt` asc, só se ela pertence ao `deviceId`
  do header — `404` (não `403`, para não confirmar a existência do id a quem não é dono) se a
  conversa não existe ou é de outro device. Teste via `MockMvc`: listar devolve só as conversas do
  device do header; reabrir uma conversa existente do mesmo device devolve as mensagens na ordem
  certa (CA7); reabrir uma conversa de outro device (ou um id inexistente) devolve `404`;
  requisição sem `X-Device-Id` em qualquer um dos dois endpoints devolve `400`.

  > **Nota (2026-07-20, T6, correção pós-review):** `ConversationController` depende só de
  > `ConversationStore` (mesmo padrão de `ChatController` dependendo só de `GenerationService`) —
  > nunca injeta `ConversationRepository`/`MessageRepository` diretamente (CLAUDE.md, "sem acesso
  > direto a repositório fora da camada de serviço"). `ConversationStore` ganhou dois métodos de
  > leitura para isso: `listByDevice` (delega a `ConversationRepository.
  > findByDeviceIdOrderByUpdatedAtDesc`, T2) e `findDetail` (busca a conversa por id, confere o
  > `deviceId` e, se pertence, busca as mensagens via o mesmo finder que `recentHistory` já usa —
  > `null` tanto para "não existe" quanto para "é de outro device", a distinção de status HTTP
  > continua no controller).

- [x] **T7 — `GenerationDebugCommand`: CLI de debug**
  `com.buscai.backend.generation.cli.GenerationDebugCommand`, mesmo padrão de
  `RetrievalDebugCommand` (`specs/retrieval/`): `CommandLineRunner` sob `@Profile("generation-debug")`,
  parseia `--query` (obrigatório), `--books` (opcional) e `--conversation-id` (opcional — sem ele,
  cada invocação é uma conversa nova), roda `GenerationService` diretamente (sem HTTP, sem os
  filtros de T3 — é um consumidor interno, mesmo espírito de `RetrievalDebugCommand`) e imprime os
  tokens no console conforme chegam, seguidos da mensagem final ou de erro. Teste: formatação de
  cada variante de resultado (sucesso, `NoRelevantContext`, erro) produz a saída esperada — teste
  unitário, sem subir o Spring context, mesmo padrão de T7 do retrieval.

- [x] **T8 — Teste de aceite de ponta a ponta via HTTP (CA1-CA11)**
  Teste de integração (Testcontainers + `MockMvc`/`TestRestTemplate`, `ClaudeClient` fake
  determinístico) cobrindo o pipeline completo por HTTP, não unidade por unidade: pergunta com
  contexto relevante produz resposta citando livro/página (CA1); pergunta sem contexto produz a
  mensagem fixa de "sem fundamento" (CA2); resposta chega em streaming, não só no final (CA3);
  segunda pergunta na mesma conversa aciona rewrite e usa o histórico (CA4); requisição sem
  `X-Api-Key` é barrada antes de qualquer chamada ao `ClaudeClient` fake (CA5); escopo de livros é
  respeitado (CA6, reaproveitando fixture do retrieval); reabrir conversa recupera o histórico na
  ordem certa (CA7); N+1 requisições na mesma janela recebem `429` (CA8); nenhuma chave aparece em
  nenhuma resposta de erro (CA9); falha simulada no meio do stream produz `event: error` sem
  persistir resposta parcial (CA10); pergunta sem `conversationId` inicia conversa nova, com
  `conversationId` continua a existente (CA11).

- [ ] **T9 — `rag-evaluator` + sincronizar `docs/adr`/`specs/eval`**
  Rodar o subagent `rag-evaluator` contra `specs/eval/golden-set.json` (constitution.md, seção 4)
  — além de recall@k (herdado do retrieval), mede groundedness da resposta gerada, métrica nova
  que esta feature introduz; golden set vazio continua não-bloqueante (mesmo padrão de T9 do
  retrieval), mas precisa ser reportado, não escondido. Confirmar que nenhuma decisão tomada
  durante a implementação (ex.: valor final do timeout do `SseEmitter`, tamanho do `Executor`,
  texto exato da mensagem fixa de `NoRelevantContext`, IDs de modelo usados) diverge do que está
  registrado em ADR-0004/ADR-0005/ADR-0007/`plan.md`; atualizar o documento correspondente com uma
  nota datada onde divergir, mesmo padrão já usado no fechamento da feature de retrieval.

Depois de T1–T9: rodar `/review` (subagent `code-reviewer`) sobre o diff completo antes de
qualquer commit/PR, conforme `CLAUDE.md`. Mudança em prompt de geração — rodar também o
`rag-evaluator` (T9 já cobre isso) antes de mergear.
