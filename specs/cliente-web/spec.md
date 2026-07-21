# Spec — cliente-web (Fase 6)

## Contexto

Primeiro cliente do backend buscai a ir ao ar, conforme [ADR-0011](../../docs/adr/0011-cliente-web-fino-primeiro-android-adiado.md):
web app fino (HTML/CSS/JS puro, sem framework/build step), substituindo o app Android como
prioridade de Fase 6 (`android/` continua pausado, intocado). Consome os endpoints já entregues
na Fase 5 (PR #10): `POST /chat` (streaming SSE) e `GET /conversations`/`GET /conversations/{id}`.

## O que o usuário consegue fazer

1. **Conversar com os livros indexados.** Digitar uma pergunta e ver a resposta chegando em
   streaming (token a token), com o texto renderizado progressivamente enquanto chega.
2. **Ver a fonte de cada resposta.** Toda resposta cita livro e página; quando o backend declarar
   que a informação não está nos livros, essa declaração aparece de forma clara na conversa (nunca
   escondida ou parafraseada a ponto de parecer uma resposta comum). Citação e disclaimer chegam
   como **texto** dentro do próprio stream de resposta (o backend não expõe metadado estruturado
   de citação) — a UI renderiza esse texto, não constrói chips/estrutura a partir de um campo à
   parte.
3. **Restringir a busca a um subconjunto de livros.** Escolher, antes de perguntar, se a busca
   roda em todo o acervo ou só nos livros selecionados, a partir de uma lista com os títulos
   disponíveis (não IDs/slugs digitados de cor — exige um novo endpoint de leitura no backend,
   `GET /books`, ver "Contrato de backend novo" abaixo).
4. **Retomar uma conversa.** Ver a lista de conversas anteriores (do mesmo navegador/dispositivo)
   e reabrir qualquer uma delas com o histórico completo, continuando a partir dali.
5. **Iniciar uma conversa nova** a qualquer momento, sem perder acesso às anteriores.
6. **Acessar o app com um link só.** Não há instalação, loja ou build — abrir a URL no navegador
   (desktop ou celular, qualquer plataforma) já é suficiente para usar o produto.
7. **Autorizar o próprio acesso uma única vez.** Na primeira visita, o usuário informa a chave de
   acesso que lhe foi entregue pelo desenvolvedor; nas visitas seguintes, o navegador já lembra e
   não pede de novo. Ver "Decisão de segurança" abaixo — este comportamento não é um detalhe de
   implementação, é requisito de produto: a chave nunca pode vir embutida no código do app, ou o
   requisito "poucos usuários conhecidos" (mesma premissa do ADR-0005) deixa de valer no instante
   em que o link é compartilhado.

## Decisão de segurança (resolve a pendência aberta pelo ADR-0011)

O ADR-0011 registrou explicitamente que a chave estática (`X-Api-Key`, ADR-0005) fica exposta a
qualquer pessoa com acesso ao DevTools do navegador — risco diferente do Android (que exige
decompilar um APK) — e listou três caminhos possíveis para esta spec resolver: (a) servir o web
same-origin com autorização server-side, chave nunca no bundle; (b) manter a chave estática, mas
manter o link fora de distribuição pública; (c) revisitar ADR-0005 para autenticação por
sessão/usuário.

**Decisão: variante de (b).** A chave nunca é commitada no repositório nem embutida no bundle
JS/HTML — o usuário a informa manualmente na primeira visita e o navegador a guarda localmente
(mesmo padrão de "poucos usuários conhecidos, cada um de posse da própria chave" que já é o
threat model aceito pelo ADR-0005 hoje, inclusive para o Android). Isso resolve a diferença real
entre web e Android (repositório público vs. APK): sem essa mudança, qualquer visitante do link
—ou do próprio código-fonte no GitHub— obteria a chave sem nenhum esforço; com ela, só quem já
recebeu a chave do desenvolvedor consegue usar o app, exatamente como acontece hoje.

Prós/contras avaliados (registro da decisão técnica, conforme convenção do projeto):
- **(a) same-origin + autorização server-side:** eliminaria a exposição da chave ao
  navegador por completo, mas exige introduzir um mecanismo novo de sessão/cookie no backend —
  arquitetura nova, escopo maior, e ADR-0011 já registra preferência por "menor superfície nova de
  código" para esta fase. Descartada por ora (não descartada para sempre — fica registrada como
  caminho se o produto crescer a ponto de exigir autenticação por usuário de verdade).
- **(b) manter chave estática, sem distribuição pública do link:** simples, zero mudança de
  backend, mas contradiz a própria motivação do ADR-0011 ("distribuição trivial — um link"): se o
  link precisa continuar secreto, a vantagem central do cliente web não se realiza.
- **(b) refinada — escolhida:** separa as duas coisas que (b) originalmente juntava. O **link**
  pode ser compartilhado livremente (ele não é mais o segredo); a **chave**, sim, continua
  sensível e é ela — não o link — que preserva o threat model do ADR-0005. Zero mudança de
  backend, zero arquitetura nova, resolve o risco real (chave em texto plano num repositório
  público) sem introduzir sessão/cookie.
- **(c) revisitar ADR-0005 (sessão/usuário):** correta a longo prazo se o produto ganhar múltiplos
  usuários com necessidades distintas (histórico por conta, não por dispositivo), mas
  desproporcional para a Fase 6 de um projeto pessoal em estágio inicial. Fica registrada como
  gatilho futuro, mesma ressalva que o próprio ADR-0005 já faz.

Esta decisão foi validada pelo `android-architect` e está registrada formalmente em
[ADR-0012](../../docs/adr/0012-cliente-web-seguranca-hospedagem-transporte.md), junto com duas
outras decisões técnicas que a revisão apontou como inseparáveis desta (hospedagem same-origin e
transporte do streaming) — ver seção seguinte.

## Contrato de backend novo

A revisão do `android-architect` identificou que **CA3 é inimplementável com o contrato atual**:
o backend não expõe nenhum endpoint que liste os livros disponíveis (`bookIds` do `POST /chat` é
um slug opaco definido na ingestão via CLI, ADR-0008). Esta spec adota a adição de:

- **`GET /books`** — read-only, atrás do `ApiKeyFilter` como qualquer outro endpoint, devolve
  `id` + `title` dos livros com ingestão completa (`READY`). Sem paginação/filtro nesta fase
  (acervo pequeno). Detalhe de contrato (schema de resposta) fica no `plan.md`.

Isso é a única mudança de contrato HTTP que esta feature exige do `backend/` além de servir os
arquivos estáticos do `web/` (ver ADR-0012).

## Critérios de aceite

- **CA1:** ao enviar uma pergunta, o texto da resposta aparece progressivamente na tela (não só
  de uma vez ao final) — reflete o streaming SSE do backend.
- **CA2:** toda resposta exibida inclui, de forma visível, as citações de livro/página devolvidas
  pelo backend; se o backend declarar que a informação não está nos livros, essa mensagem aparece
  sem ser confundida com uma resposta afirmativa comum.
- **CA3:** existe uma forma de escolher, antes de perguntar, entre "todos os livros" e um
  subconjunto específico — a pergunta seguinte respeita essa escolha.
- **CA4:** existe uma tela/lista com as conversas anteriores do dispositivo atual; abrir uma
  delas mostra o histórico completo, em ordem cronológica.
- **CA5:** existe uma ação clara para começar uma conversa nova.
- **CA6:** na primeira visita sem uma chave salva, o app pede a chave de acesso antes de permitir
  qualquer pergunta; em visitas seguintes (mesmo navegador), não pede de novo.
- **CA7:** se a chave informada for inválida (backend responde 401/403), o app comunica isso
  claramente e permite corrigir a chave — nunca falha silenciosamente nem trava a tela.
- **CA8:** se o backend estiver "frio" (cold start do free tier, ADR-0006) e demorar para
  responder, o app comunica que está carregando/aguardando — nunca parece travado ou quebrado
  antes de um tempo generoso se esgotar.
- **CA9:** se o backend retornar erro (evento `error` do SSE, ou HTTP de erro fora do fluxo de
  chat), o app exibe uma mensagem de erro genérica ao usuário — nunca o detalhe cru devolvido
  pelo backend.
- **CA10:** o app funciona em pelo menos um navegador desktop e um mobile recentes, sem exigir
  instalação nem passo de build.
- **CA11:** nenhuma chave de API (nem a de acesso ao buscai, nem — óbvio, ela nunca chega aqui —
  Anthropic/Voyage) é commitada no repositório em nenhum arquivo do `web/`.

## Fora de escopo (Fase 6)

- Edição/exclusão de conversas ou mensagens (o backend, Fase 5, não expõe esses endpoints).
- Múltiplos usuários por dispositivo, contas, login com senha (ver "Decisão de segurança" — fica
  para uma eventual revisão futura do ADR-0005).
- Modo offline (já é consequência aceita desde o ADR-0001, independente do cliente).
- Upload/ingestão de PDF pelo cliente (fora do produto desde o ADR-0001/ADR-0002).
- Tela de "detalhe do livro" com reindexação (não existe no backend atual).
- Build step, framework ou bundler (decisão do ADR-0011, não revisitada aqui).
- Hospedagem do `web/` (fica para o `plan.md`, é decisão técnica, não requisito de produto).

## Notas datadas

- [2026-07-20] Spec criada. Decisão de segurança tomada de forma autônoma (variante refinada de
  (b) do ADR-0011), pendente validação do `android-architect` antes de virar ADR-0012 formal.
- [2026-07-21] T6 — verificação end-to-end de CA1-CA11 (T3/T4/T5 via Playwright contra backend
  local + Postgres com "Livro dos Espíritos" real ingerido via CLI; parte contra um mock local
  para não gastar crédito das APIs pagas em cenários de erro/rede; parte confirmada manualmente
  pelo usuário no navegador com `ANTHROPIC_API_KEY`/`VOYAGE_API_KEY` reais):
  - CA1 (streaming progressivo), CA3 (seletor todos/subconjunto), CA4 (reabrir conversa com
    histórico completo), CA5 (nova conversa), CA6 (gate só na primeira visita), CA7 (chave
    inválida reabre o gate com mensagem), CA8 ("aguardando resposta" antes do 1º token), CA10
    (sem overflow horizontal em viewport mobile 390px), CA11 (nenhuma chave hardcoded em `web/`,
    checado via grep) — todos OK.
  - CA2 (citação/disclaimer como texto na resposta): mecanismo de exibição OK (texto do stream
    renderizado sem parsing estruturado); qualidade do conteúdo em si (retrieval/geração, Fase 5)
    ficou abaixo do esperado do usuário num caso pontual — fora do escopo desta feature
    (constitution.md §4 exige `rag-evaluator` para mudança nessa camada); registrado aqui para
    investigação futura, não bloqueia esta feature.
  - CA9 (erro genérico, nunca detalhe cru): dois bugs achados e corrigidos durante a verificação,
    ambos em `web/app.js` — (1) quando a conexão cai a meio do streaming (não um evento `error`
    limpo do backend, mas socket destruído), a mensagem genérica de erro não aparecia, deixando o
    balão só com o texto parcial sem indicar falha; corrigido para sempre anexar a mensagem
    genérica ao conteúdo já recebido. (2) o parsing SSE usava `.trim()` na linha `data:` inteira,
    comendo espaços à direita que fazem parte do próprio delta de texto do token — concatenar
    "Resposta de teste " + "com citação..." virava "Resposta de testecom citação..."; corrigido
    para remover só o único espaço delimitador logo após `data:`, por spec do protocolo SSE.
  - Um terceiro achado foi falso-positivo de teste (não bug de produto): checagem de CA8 falhou
    numa rodada porque o mock local respondia rápido demais (sem latência de rede real), fazendo
    o teste correr depois que o token já tinha chegado — não reflete o comportamento real contra
    o backend (que tem latência de rede/geração de verdade). Confirmado reintroduzindo um atraso
    artificial no mock.
