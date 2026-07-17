# ADR-0011: Cliente web fino como primeiro cliente; app Android adiado

## Status
Aceito — 2026-07-17

## Contexto
O [ADR-0001](0001-arquitetura-geral-backend-completo.md) decidiu backend completo (Kotlin/Spring
Boot) com o app Android como cliente fino de chat. Essa decisão sobre onde mora a lógica do
produto (ingestão, embeddings, retrieval, geração) continua correta e não é revisitada aqui — as
Fases 1-4 (harness, specs, ingestão, retrieval) são 100% backend e não dependem de qual cliente as
consome, exatamente como o ADR-0001 já previa como consequência positiva.

O que muda é *qual cliente é construído primeiro*. `android/` hoje é só scaffold (`MainActivity`,
tema, nenhuma tela real — Fase 6 do planejamento original nunca começou). Reavaliando a
distribuição de um app Android nativo:

- Play Store exige conta de desenvolvedor paga, processo de review, e (para conta pessoal nova)
  um requisito de ~20 testadores antes de publicar para o público geral;
- fora da loja, a alternativa é sideload de APK — atrito alto para qualquer usuário que não seja o
  próprio desenvolvedor;
- exclui iOS e desktop nativamente;
- cada mudança de UI exige gerar e distribuir um novo APK.

Um cliente web fino atinge o mesmo objetivo funcional (chat, streaming da resposta, citações
livro/página) com distribuição trivial (um link) e alcance multiplataforma (qualquer navegador,
incluindo iPhone e desktop), sem exigir nenhuma mudança de contrato do backend: SSE e JSON, tal
como definidos em [ADR-0004](0004-geracao-via-proxy-backend.md)/[ADR-0005](0005-autenticacao-e-limites.md),
já são consumíveis nativamente por `fetch`/`EventSource` do navegador — não há acoplamento a
Android/Kotlin em lugar nenhum do contrato de API.

Opções de stack para o cliente web foram avaliadas com o usuário (prós/contras + recomendação,
conforme convenção de decisão técnica do projeto): HTML/CSS/JS puro sem build, Vite+TypeScript+
Preact, e um framework completo (React/Next.js ou SvelteKit). Escolhido HTML/CSS/JS puro, sem
bundler nem framework — é a opção mais alinhada à filosofia "cliente fino" que motivou o
ADR-0001 (nenhuma lógica de produto no cliente, só chat) e evita introduzir tooling/dependências
novas para uma UI que hoje é só 2-3 telas (chat, seleção de escopo, talvez configurações).

## Decisão
- O **primeiro cliente** do backend buscai passa a ser um **web app fino** (diretório `web/`,
  paralelo a `android/` e `backend/` — terceiro build independente na raiz do repo), não o app
  Android.
- **Stack: HTML/CSS/JavaScript puro, sem framework, sem bundler/build step.** Consome o backend
  via `fetch` (JSON) e streaming SSE (`EventSource` ou `fetch` com leitura de stream) diretamente
  do navegador — mesmo contrato de API já definido para "qualquer cliente fino" nos ADRs 0001,
  0004 e 0005; nenhuma mudança de backend decorre desta ADR.
- **Hospedagem** do `web/`: decisão específica (estático no mesmo host do backend vs. hospedagem
  estática dedicada tipo Cloudflare Pages/GitHub Pages) fica para quando a feature web nascer via
  SDD (`specs/`) — esta ADR registra só a escolha de arquitetura/prioridade de cliente, não
  implementa nem especifica o cliente web em si.
- **`android/` é mantido como está** (scaffold intocado — nenhum arquivo removido) e **pausado**:
  nenhum trabalho de UI Android acontece até uma decisão explícita de retomar (ex.: demanda real
  por app nativo ou distribuição fora do navegador). Isso não é reversão do ADR-0001 quanto à
  arquitetura de backend — só quanto a qual cliente é construído primeiro.
- Implementação do cliente web (código) só começa depois de nascer sua própria spec
  (`/spec-feature`, conforme `specs/constitution.md` — nenhuma feature sem spec), e depende do
  backend ter o endpoint de geração/streaming (Fase 5, `ADR-0004` — ainda não implementado nesta
  branch/roadmap). Esta ADR é só a decisão de arquitetura/prioridade; não há código novo neste
  commit.

## Alternativas consideradas
- **Manter o app Android como prioridade.** Descartada como *primeiro* cliente (não descartada
  para sempre): o custo de distribuição (loja paga, review, requisito de testadores) supera o
  ganho de UX nativa para a fase atual do projeto, onde o objetivo é validar o produto (RAG sobre
  os livros) com o menor atrito de acesso possível.
- **Bot (Telegram/WhatsApp).** Descartada: sem controle de UX para citações (livro/página) — a
  constitution exige sempre citar livro e página, e a formatação de um bot de terceiros não dá
  esse controle; dependência de política de plataforma externa.
- **KMP/Compose Multiplatform.** Descartada: complexidade de build/toolchain que não se justifica
  para um cliente deste tamanho — contradiz a filosofia de "cliente fino" do próprio ADR-0001.
- **Vite+TypeScript+Preact / framework completo (React, SvelteKit) para o web app.** Descartadas
  por ora: tooling e dependências adicionais sem ganho proporcional para uma UI de 2-3 telas; pode
  ser revisitado em ADR próprio se a UI crescer a ponto de justificar (ex.: necessidade real de
  componentização, roteamento client-side).

## Consequências
- **Positivo:** distribuição trivial (link, sem loja/review); alcance multiplataforma real
  (Android, iOS, desktop) com um único cliente; zero retrabalho de backend, já que o contrato
  SSE/JSON de ADR-0004/ADR-0005 sempre foi cliente-agnóstico; menor superfície nova de código
  (sem framework/bundler) para manter.
- **Negativo:** UX um degrau abaixo de nativo (sem notificação push nativa, integração de teclado/
  gestos mais pobre que Compose); sem modo offline (já era uma consequência aceita desde o
  ADR-0001, independente do cliente).
- **Negativo, não fechado por esta ADR — herdado como pergunta em aberto para a spec do web:** a
  chave estática do header `X-Api-Key` ([ADR-0005](0005-autenticacao-e-limites.md)) ficaria
  visível no código-fonte de uma página web, mas **não é o mesmo risco** que num APK — extrair a
  chave de um APK exige decompilar; extrair de uma página web é abrir o DevTools, fricção
  praticamente zero. Mais grave: o ADR-0005 aceita a chave estática *porque* o cenário é "poucos
  usuários conhecidos, não distribuição pública ampla", e já declara textualmente que, se a
  distribuição crescer, "a chave estática deixa de ser suficiente e este ADR precisa ser
  revisitado" — e esta própria ADR-0011 vende como vantagem justamente "distribuição trivial (um
  link)" e "alcance multiplataforma real", empurrando o produto na direção desse gatilho. Isto
  **não é resolvido aqui**: a spec do cliente web (`/spec-feature`, quando priorizada) precisa
  decidir explicitamente uma de (a) servir o web same-origin pelo próprio backend, com a
  autorização feita server-side e a chave nunca no bundle; (b) manter a chave estática mas manter
  o link fora de distribuição pública, preservando o threat model original do ADR-0005; ou (c)
  revisitar o ADR-0005 (autenticação por sessão/usuário) antes do web ir ao ar. Até essa decisão,
  tratar a exposição da chave como "já aceita" seria assumir algo que o ADR-0005 explicitamente não
  cobre para este cenário. (Ponto positivo lateral: ao contrário do APK, rotacionar a chave do web é
  trivial — um redeploy do estático — o que facilita a opção (b) enquanto (a)/(c) não forem feitas.)
- **Impacto no planejamento:** a Fase 6 de `docs/planejamento-app-rag-android.md` (hoje "App
  Android — telas") passa a valer para um cliente web equivalente; o documento original é anotado
  (não reescrito) apontando para esta ADR, no mesmo padrão da nota já existente sobre o ADR-0001 na
  Fase 0. O app Android (Fase 6 original) fica **adiado, não cancelado** — nada do trabalho de
  backend (Fases 3-5) é perdido ou precisa mudar para viabilizar isso, dado o ADR-0001.
- Nenhuma mudança em `CLAUDE.md`/estrutura de repositório acontece além de uma nota apontando para
  esta ADR — a seção `web/` completa (stack, comandos, convenções) só é escrita quando o módulo de
  fato existir, para não documentar convenção de código que ainda não tem nenhum arquivo para
  seguir.
