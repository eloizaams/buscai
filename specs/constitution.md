# Constitution — buscai

Princípios inegociáveis do projeto. Toda spec (`spec.md`), plano (`plan.md`) e task deve ser
validada contra este documento. Se uma feature exigir violar um princípio, o caminho é propor
um ADR revisando a decisão — nunca violar em silêncio.

> Arquitetura vigente: backend completo (ADR-0001) — app Android é cliente fino de chat;
> ingestão, embeddings, retrieval e geração rodam no backend. Detalhes em `docs/adr/`.

## 1. Segurança e segredos

- Nenhuma API key de provedor de IA (Anthropic, Voyage) existe no cliente Android, em código,
  em recurso ou em log — só no servidor, via variável de ambiente (ADR-0004).
- Todo endpoint do backend que gasta crédito em API paga valida `X-Api-Key` **antes** de
  qualquer chamada externa (ADR-0005).
- Nenhum segredo real é commitado: placeholders em `BuildConfig`/`application.yml`, valores
  reais só via env var ou override local não versionado.

## 2. Arquitetura em camadas

- **Android:** MVVM. `@Composable` não contém lógica de negócio — só lê `StateFlow<UiState>`
  de um `ViewModel`. Toda rede passa pelo cliente HTTP do backend; o app nunca fala com
  Claude/Voyage diretamente.
- **Backend:** controllers finos; acesso a repositórios/pgvector só pela camada de serviço.
  Ingestão (CLI) e chat compartilham o mesmo modelo/versão de embedding (ADR-0002/0003).
- Decisão de arquitetura nova (dependência, módulo, provedor, hospedagem) exige ADR antes de
  código.

## 3. Spec-Driven Development

- Nenhuma feature é implementada sem `specs/<feature>/spec.md` → `plan.md` → `tasks.md`
  aprovados. Exceção única: scaffolding de infraestrutura já coberto por ADR.
- Cada task é pequena, testável e ordenada — cabe em uma sessão de implementação.
- O fluxo é: `/spec-feature` → revisão do `android-architect` → implementação
  (`kotlin-implementer`) → `/review` (`code-reviewer`) → commit/PR.

## 4. Qualidade e testes

- Toda função pública nova nasce com teste unitário na mesma task.
- `ktlintFormat` roda antes de qualquer tarefa ser considerada concluída; CI (build + lint +
  testes) precisa estar verde para merge.
- A resposta do chat **sempre** cita livro e página, e declara explicitamente quando o
  conteúdo não está nos livros indexados — resposta sem fundamento no contexto recuperado é
  bug, não detalhe.
- Mudança em chunking, embedding, retrieval ou prompt de geração só mergeia após o
  `rag-evaluator` rodar o golden set (`specs/eval/golden-set.json`) sem regressão.

## 5. Git flow

- `main` é protegida na prática: depois da Fase 1, todo trabalho nasce em branch curta
  (`feature/<spec>`, `fix/<assunto>`) e entra via PR com CI verde + parecer do
  `code-reviewer`.
- Commits pequenos, no imperativo, em pt-BR, explicando o porquê quando não for óbvio
  (padrão atual: `Fase N: <o que>` para infra; `<área>: <o que>` para features).
- Nunca commitar artefato de build, IDE ou segredo (os `.gitignore` já cobrem; não
  contorná-los com `git add -f`).

## 6. Custo e operação

- Free tier consciente (ADR-0006): o app trata cold start do backend como estado esperado
  (loading com timeout generoso), não como erro.
- Rate limiting no backend protege contra abuso da chave (ADR-0005); teto de gasto
  acompanhado nos dashboards dos provedores até existir corte automático.
