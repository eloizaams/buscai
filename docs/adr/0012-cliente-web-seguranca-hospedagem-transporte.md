# ADR-0012: Cliente web — chave de acesso, hospedagem same-origin e transporte do streaming

## Status
Aceito — 2026-07-20

## Contexto
[ADR-0011](0011-cliente-web-fino-primeiro-android-adiado.md) decidiu que o primeiro cliente do
backend buscai é um web app fino (HTML/CSS/JS puro), mas deixou explicitamente em aberto como
resolver a exposição da chave estática `X-Api-Key` ([ADR-0005](0005-autenticacao-e-limites.md))
numa página web — diferente do Android, extrair a chave de uma página web é abrir o DevTools,
fricção praticamente zero. A spec da Fase 6 (`specs/cliente-web/spec.md`) foi escrita e revisada
pelo subagent `android-architect`, cuja revisão identificou duas decisões técnicas adicionais
inseparáveis da primeira: hospedagem do estático e transporte do streaming SSE. As três são
registradas juntas aqui porque a escolha de hospedagem determina se há ou não mudança de backend
(CORS), o que por sua vez afeta a superfície de código que o ADR-0011 pediu para minimizar.

## Decisão

### 1. Chave de acesso: nunca commitada, informada pelo usuário, guardada no navegador
A chave (`X-Api-Key`, ADR-0005) não é embutida no bundle `web/` de forma alguma — nem em código,
nem em arquivo de config versionado. Na primeira visita, o app pede a chave ao usuário e a guarda
em `localStorage` do navegador; visitas seguintes reusam a chave salva sem pedir de novo. Enquanto
a chave for inválida (401/403), o app volta a pedir.

Isso separa duas coisas que a opção "(b)" original do ADR-0011 misturava: o **link** do app deixa
de ser sensível (pode circular livremente, cumprindo a promessa de "distribuição trivial" do
ADR-0011) — só a **chave** continua sendo o segredo, exatamente como já é hoje no Android
(`BuildConfig`, também extraível, também aceito pelo ADR-0005 sob a premissa "poucos usuários
conhecidos, cada um de posse da própria chave").

Duas consequências aceitas, sem mudar a decisão:
- A chave é estática e **compartilhada entre todos os usuários** — o bucket de rate limit
  (ADR-0005) é global, não por pessoa. Já era verdade no Android; um link público só torna
  concorrência real mais provável. Sem ação nesta ADR — cabe revisitar se o rate limit global se
  mostrar insuficiente na prática.
- `localStorage` é legível por DevTools/XSS no navegador de quem já tem a chave — não eleva risco
  acima do que esse usuário já tem acesso (é a própria chave dele, na própria máquina dele).

### 2. Hospedagem: same-origin, servida pelo próprio backend Spring Boot
O `web/` (arquivos estáticos: HTML/CSS/JS) é servido pelo mesmo backend que expõe `/chat` e
`/conversations` — não por um host estático dedicado (Cloudflare Pages/GitHub Pages). Os arquivos
estáticos ficam isentos do `ApiKeyFilter`/`RateLimitFilter` (mesmo padrão já usado para
`/actuator/health` em `ApiSecurityPaths`), já que servir HTML/JS/CSS não gasta crédito em API
paga — só as chamadas subsequentes a `/chat`/`/conversations`/`/books` feitas pelo JS carregado
é que continuam exigindo `X-Api-Key`.

Consequência aceita: o carregamento da própria página sofre o cold start do free tier
(ADR-0006) — tratado como o mesmo "estado esperado, não erro" que a constitution já exige para
qualquer chamada ao backend (CA8 da spec).

### 3. Transporte do streaming: `fetch` + `ReadableStream`, não `EventSource`
A API `EventSource` não permite enviar headers customizados — não há como mandar `X-Api-Key` nem
`X-Device-Id` por ela, ambos obrigatórios em `POST /chat`. O consumo do SSE é feito via `fetch`
com leitura manual do `ReadableStream` da resposta, parseando o protocolo `text/event-stream`
(`event:`/`data:`) na mão. `EventSource` fica descartado — não é escolha de estilo, é restrição
de contrato (a mesma dependência de headers customizados que motivou este ADR no item 1).

## Alternativas consideradas
- **(a) Same-origin com autorização server-side (sessão/cookie), chave nunca no navegador.**
  Eliminaria a exposição por completo, mas introduz mecanismo de sessão novo no backend —
  arquitetura nova que o ADR-0011 já pediu para evitar nesta fase. Registrada como caminho futuro
  se o produto crescer a ponto de exigir conta por usuário (mesmo gatilho que o ADR-0005 já prevê).
- **(b) original — chave estática embutida, link fora de distribuição pública.** Descartada: o
  link precisar continuar secreto anula a vantagem central do ADR-0011 ("distribuição trivial").
- **(c) Revisitar ADR-0005 para autenticação por sessão/usuário.** Correta a longo prazo, mas
  desproporcional para a Fase 6 de um projeto pessoal em estágio inicial com poucos usuários
  conhecidos — mesma premissa que já sustenta o ADR-0005 hoje.
- **Hospedagem em host estático dedicado (Cloudflare Pages/GitHub Pages).** Mais alinhada à
  "distribuição trivial" em teoria, mas exige CORS: `X-Api-Key`/`X-Device-Id` são headers
  customizados, o que dispara preflight `OPTIONS` — que hoje bateria no `ApiKeyFilter` sem chave e
  seria barrado, exigindo isentar o preflight nos filtros e configurar CORS por origem. É mudança
  de backend real, foi subestimada pelo ADR-0011 ("nenhuma mudança de backend decorre desta ADR",
  válido só no cenário same-origin). Descartada por ora pela superfície de código adicional; fica
  registrada como caminho se o produto precisar de hospedagem estática dedicada no futuro.

## Consequências
- Nenhuma mudança em `ApiSecurityPaths`/`ApiKeyFilter` além de isentar os arquivos estáticos do
  `web/` (mesmo padrão de `/actuator/health`) — os endpoints de API continuam 100% protegidos.
- Novo endpoint `GET /books` (read-only, atrás de `ApiKeyFilter`) é necessário para CA3 da spec
  (seleção de escopo por livro) — o backend não expunha antes nenhuma forma de listar livros
  indexados; `bookId` era um slug opaco só conhecido de quem rodou a ingestão via CLI.
- Se o `web/` precisar migrar para hospedagem estática dedicada no futuro, este ADR precisa ser
  revisitado (CORS deixa de ser opcional).
- Se a distribuição do link crescer a ponto de "poucos usuários conhecidos" deixar de valer, tanto
  este ADR quanto o ADR-0005 precisam ser revisitados (autenticação por sessão/usuário).
