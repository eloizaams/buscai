# Desafios e pontos de fricção

Registro de dificuldades encontradas durante o desenvolvimento que não são corrigíveis via ajuste
de skill/agent — ver histórico de `.claude/agents/*.md` para os itens que já foram corrigidos.

## 2026-07-21 — gate do rag-evaluator exige infra real que a sessão sandbox não tem

Fechando `referencia-estruturada` (T8), o gate obrigatório do `rag-evaluator` (constitution.md seção
4) não pôde ser satisfeito dentro do sandbox: sem `VOYAGE_API_KEY`/`ANTHROPIC_API_KEY` no ambiente,
sem Postgres acessível (nem local nem Neon configurado), e o PDF real do livro só existe na máquina
do usuário. O hook `block-secrets.py` corretamente bloqueia qualquer `Read`/`Bash` meu que referencie
`backend/.env` — certo, é a proteção funcionando. Mas isso significa que **nenhuma ingestão real nem
avaliação RAG de fato pode rodar por mim** quando depende de segredos do `.env`; só o dono do repo,
rodando comandos no próprio terminal (via prefixo `!`), pode.

Fricção adicional: o prefixo `!` roda no shell do usuário, não no meu — e (a) o estado do shell (env
vars exportadas) não persiste entre uma chamada `!` e minhas chamadas de `Bash` seguintes (script
inteiro precisa ser um bloco único, do `source .env` até o uso), e (b) comandos multi-linha com `\`
de continuação quebraram ao serem colados pelo usuário (cada linha rodou separada, ex. `-H:` virou
"comando não encontrado") — só funcionou depois de eu reescrever tudo em linha única com `&&`.
Mitigação já aplicada nesta sessão (adotar daqui pra frente): quando pedir para o usuário rodar algo
via `!` que dependa de segredo/infra que eu não alcanço, **sempre mandar comando de linha única**
(nunca com `\` de continuação), e considerar de antemão se a infra (Postgres alcançável, PDF local)
já está disponível antes de montar o roteiro — perguntar objetivamente em vez de assumir.

Não corrigível via skill (é limitação de ambiente + boundary de segurança correto), mas documentado
para a próxima vez que este gate precisar rodar de verdade fora do sandbox: o roteiro que funcionou
foi (1) usuário edita `.env` com `DATABASE_URL`/`DATABASE_USERNAME`/`DATABASE_PASSWORD` do Neon
(quebrados em três variáveis, não a connection string única do painel do Neon) + `VOYAGE_API_KEY`/
`ANTHROPIC_API_KEY`; (2) roda a ingestão real via `SPRING_PROFILES_ACTIVE=ingest ./gradlew bootRun
--args="..."`; (3) sobe `./gradlew bootRun` normal e testa perguntas pelo `web/` no navegador
(`http://localhost:8080`) — mais simples e confiável do que eu tentar montar `curl` com SSE bruto
para o usuário colar de volta.

## 2026-07-16 — duas sessões editando a mesma branch em paralelo

Enquanto uma sessão discutia/aprovava a generalização do escopo de retrieval
(`RetrievalScope.Books(bookIds)`), outra sessão implementava T4 na mesma branch — edições no
`specs/retrieval/tasks.md` falharam duas vezes por modificação concorrente, e a generalização quase
foi feita em duplicidade (a outra sessão aplicou a mesma mudança por conta própria). Convergiu bem
desta vez, mas foi sorte de as decisões coincidirem. Não é corrigível por skill: é operacional.
Mitigação ao trabalhar com sessões paralelas: (1) uma sessão por branch sempre que possível;
(2) antes de editar spec/código em sessão "de análise", checar `git log` + mtime dos arquivos-alvo;
(3) decisões aprovadas numa sessão devem ser comunicadas à sessão implementadora (ou registradas
em spec commitada) antes de ela chegar na task afetada.

## 2026-07-13 — custo de rodadas review→fix→re-review em cadeia

Ao fechar T8-T12 da feature de ingestão (`specs/ingestao-pdf/`), o gate final de `/pr` (revisão do
diff completo antes do PR) levou **3 rodadas** de `code-reviewer` → `kotlin-implementer` →
`code-reviewer` até não sobrar item Crítico — cada rodada de subagent levando de 3 a 15 minutos.
O problema de fundo (tratamento de erro incompleto num pipeline de várias etapas) já foi corrigido
nos agentes (ver `.claude/agents/code-reviewer.md` e `.claude/agents/kotlin-implementer.md`), então
a expectativa é que da próxima vez isso se resolva em 1 rodada — mas o padrão em si (revisão final
pode encontrar Crítico, exigindo voltar ao implementer e revisar de novo) é inerente ao fluxo
"nunca abrir PR com Crítico em aberto" do `CLAUDE.md`, não algo a eliminar. Se o tempo total do
fluxo continuar alto mesmo depois do ajuste dos agents, vale considerar: o `code-reviewer` fazer uma
autoavaliação de "isto que estou apontando é um padrão que se repete no arquivo inteiro, ou é
isolado?" antes de reportar, para reduzir a chance de uma 2ª rodada encontrar mais do mesmo tipo de
problema.
