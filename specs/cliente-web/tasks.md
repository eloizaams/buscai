# Tasks — Cliente web fino (Fase 6)

Ordem sequencial — cada item cabe numa sessão de implementação. T1-T2 em `backend/`
(subagent `kotlin-implementer`, `ktlintFormat` + testes do módulo ao final de cada uma). T3-T5 em
`web/` (JS puro, sem `kotlin-implementer` — não é código Kotlin; sem suíte automatizada, verificação
manual via `/run` no navegador ao final de cada uma, registrando o que foi testado). T6 fecha com
verificação end-to-end de todos os CAs + `/review`.

- [x] **T1 — Backend: `GET /books`**
  Pacote `com.buscai.backend.catalog`: `BookService` novo (`listAvailable(): List<Book>` —
  combina `BookRepository`+`BookVersionRepository`, filtra por versão ativa com
  `BookVersionStatus.READY`, ordena por título; ver `plan.md`, seção "Contratos entre camadas",
  para a implementação de referência). Pacote `com.buscai.backend.catalog.web`: `BookController`
  (`GET /books`, injeta só `BookService`) e `BookResponse` (`id`, `title`). Sem mudança em
  `ApiSecurityPaths` nesta task — `/books` entra protegido por `ApiKeyFilter`/`RateLimitFilter`
  automaticamente (`addUrlPatterns("/*")`, sem exceção registrada), igual `ChatController`/
  `ConversationController`. Teste: `BookServiceTest` (unit — livro sem versão ativa fica de fora;
  versão ativa `INGESTING`/`FAILED` fica de fora; só `READY` aparece; ordenação por título) e
  `BookControllerTest` (`MockMvc` — sem `X-Api-Key` válido, `401`; com chave válida, lista os
  livros disponíveis no formato esperado).

- [x] **T2 — Backend: servir `web/` same-origin**
  `backend/build.gradle.kts`: task `copyWebStatic` (`Copy`, de `rootDir.resolve("../web")` para
  `layout.buildDirectory.dir("resources/main/static")`), `processResources` depende dela (ver
  `plan.md`, seção "Arquivos estáticos"). Nesta task `web/` ainda não existe de verdade — criar só
  um `web/index.html` placeholder mínimo (`<!doctype html><title>buscai</title>`) para o build
  Gradle ter o que copiar e o teste desta task ter o que verificar; T3 substitui o conteúdo.
  `ApiSecurityPaths`: adicionar `/`, `/index.html`, `/app.js`, `/styles.css` à lista de isentos
  (mesmo padrão de `/actuator/health`). Teste: integração (`MockMvc`/`TestRestTemplate`) —
  `GET /` sem `X-Api-Key` devolve `200` com o conteúdo do `index.html`; `GET /chat`/`GET /books`
  sem `X-Api-Key` continuam `401` (a isenção não vazou para as rotas de API).

- [ ] **T3 — Web: shell + gate de chave de acesso**
  `web/index.html` (estrutura da página: cabeçalho, área de gate/overlay, área de chat — vazia
  ainda, populada em T4/T5 —, `<script src="app.js" defer>`), `web/styles.css` (visual simples,
  responsivo — legível em desktop e mobile, CA10), `web/app.js` (substitui o placeholder de T2):
  bootstrap do estado (lê `buscai_api_key`/`buscai_device_id` do `localStorage`; gera
  `buscai_device_id` com `crypto.randomUUID()` se ausente e persiste — nunca regenerar depois);
  gate modal pedindo a chave quando `buscai_api_key` ausente (CA6); ao submeter, salva no
  `localStorage` e fecha o gate — validação real da chave (round-trip com o backend) só chega em
  T4 (primeira chamada real). Sem chamada de rede nesta task ainda. Verificação manual via `/run`:
  primeira visita (localStorage limpo) mostra o gate; preencher e submeter fecha o gate; recarregar
  a página não mostra o gate de novo (CA6).

- [ ] **T4 — Web: catálogo de livros + lista de conversas**
  `web/app.js`: ao fechar o gate (ou já ter chave salva), dispara `GET /books` e
  `GET /conversations` em paralelo, ambos com `X-Api-Key`+`X-Device-Id`. `401`/`403` em qualquer
  uma reabre o gate com uma mensagem de chave inválida (CA7) e limpa `buscai_api_key` do
  `localStorage`. Renderiza: seletor de escopo (checkbox "todos os livros" default marcado +
  lista de livros do `GET /books`, desmarcável individualmente — desmarcar "todos" habilita a
  seleção específica, CA3) e a barra lateral de conversas (`GET /conversations`, rótulo por data
  já que o backend não devolve título — nota já registrada no parecer do `android-architect`),
  com um botão "nova conversa" que zera `currentConversationId`/`messages` (CA5). Verificação
  manual via `/run`: com backend rodando localmente e ao menos um livro `READY` (fixture do
  retrieval), o seletor lista o(s) livro(s); chave inválida no gate mostra o erro e reabre o gate
  (CA7); lista de conversas aparece vazia num device novo.

- [ ] **T5 — Web: chat com streaming, citações e reabertura de conversa**
  `web/app.js`: envio de pergunta monta `ChatRequest` (`conversationId` atual, `query`, `bookIds`
  do seletor ou `null`) e chama `fetch('/chat', {...})`; consome `response.body.getReader()`
  fazendo parsing manual de `event:`/`data:` (`plan.md`, seção "Como roda", passo 6) — nunca
  `EventSource` (ADR-0012). Renderiza os 4 eventos: `conversation` (seta id atual, re-busca
  `GET /conversations`), `token` (concatena no balão de resposta em construção, visível
  progressivamente — CA1; o texto inclui a citação/disclaimer inline vindos do backend, exibido
  como texto normal — CA2, sem parsing estruturado), `done` (encerra o estado de "respondendo"),
  `error` (mostra a mensagem genérica recebida, encerra o estado de streaming — CA9, nunca mostra
  detalhe cru). Estado "aguardando resposta" visível assim que a pergunta é enviada, antes do
  primeiro token chegar (cobre cold start, CA8 — sem timeout próprio no cliente). Clicar numa
  conversa da barra lateral chama `GET /conversations/{id}` e renderiza o histórico completo em
  ordem cronológica (CA4), tornando-a a conversa atual para a próxima pergunta. Verificação manual
  via `/run`, ponta a ponta: pergunta nova → streaming visível → resposta com citação; segunda
  pergunta na mesma conversa; reabrir a conversa depois de recarregar a página mostra o histórico;
  simular erro (ex. desligar o backend a meio de uma pergunta) mostra a mensagem genérica sem
  travar a UI.

- [ ] **T6 — Verificação end-to-end (CA1-CA11) + `/review`**
  Rodar o app completo via `/run` (backend local + `web/` servido same-origin, conforme T2)
  cobrindo cada CA da `spec.md` em sequência (CA1 a CA11) e registrar o resultado nas notas
  datadas da `spec.md`. Rodar `/review` (`code-reviewer`) sobre o diff completo de `backend/` e
  `web/` antes de qualquer commit/PR final, conforme `CLAUDE.md`. Sem `rag-evaluator` nesta
  feature — nenhuma mudança em chunking/embedding/retrieval/prompt de geração (constitution.md
  §4 só exige o gate nesses casos).

Depois de T1–T6: `/pr` (revisa a branch inteira, push, abre o PR para `main`) — nunca merge
automático, conforme `CLAUDE.md`.
