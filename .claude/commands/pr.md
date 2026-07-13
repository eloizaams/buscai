---
description: Passo final da implementação — revisa a branch inteira, faz push e abre o PR para main. Nunca faz merge.
---

Rode ao concluir a última task da implementação (todos os itens do `tasks.md` commitados via
`/commit`).

1. `git diff main...HEAD` — se a branch não tem commits à frente da `main`, diga isso e pare.
2. Delegue ao subagent `code-reviewer` a revisão do **diff completo da branch** (não só o último
   commit). Ele já lê `CLAUDE.md`, `constitution.md` e `docs/adr/` por conta própria.
3. Tratamento do parecer:
   - Só **Atenção**/**Sugestão** → siga em frente.
   - **Crítico** → corrija os mecânicos e re-revise. Se um Crítico exigir decisão do usuário,
     **pare e pergunte antes de abrir o PR** — não abra PR com Crítico em aberto (é pré-requisito
     de merge no `CLAUDE.md`).
4. Se a mudança tocou chunking, embeddings, retrieval ou prompt de geração, rode antes o subagent
   `rag-evaluator` (regra do `CLAUDE.md`) e inclua o resultado no corpo do PR.
5. `git push -u origin <branch-atual>`.
6. `gh pr create --base main` com título no padrão de commit do projeto e corpo contendo: resumo
   do que a feature entrega + o parecer resumido do `code-reviewer` (e do `rag-evaluator`, se
   aplicável). Reporte a URL do PR ao usuário.

**Nunca faça merge** (`gh pr merge` proibido) — o merge na `main` é sempre do usuário.

`$ARGUMENTS`: se fornecido, título/descrição para o PR. Se vazio, derive dos commits da branch.
