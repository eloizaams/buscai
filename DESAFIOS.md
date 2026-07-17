# Desafios e pontos de fricção

Registro de dificuldades encontradas durante o desenvolvimento que não são corrigíveis via ajuste
de skill/agent — ver histórico de `.claude/agents/*.md` para os itens que já foram corrigidos.

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
