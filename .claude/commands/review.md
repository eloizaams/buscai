---
description: Roda o subagent code-reviewer sobre o diff atual (staged + unstaged) antes de commit/PR.
---

Rode `git status` e `git diff` (staged e unstaged) para ver o que mudou em `android/` e/ou
`backend/`. Se não houver nada alterado, diga isso e pare.

Caso contrário, delegue ao subagent `code-reviewer` a revisão desse diff — ele já sabe ler
`CLAUDE.md`, a `constitution.md` e `docs/adr/` por conta própria. Não corrija nada você mesmo
nesta etapa: apresente o parecer do subagent (Crítico / Atenção / Sugestão) ao usuário e pergunte
se quer que os pontos sejam corrigidos antes de seguir para commit.

Argumentos opcionais em `$ARGUMENTS`: um caminho ou módulo específico para focar a revisão
(ex.: `backend/src/main/kotlin/.../ChatController.kt`). Se vazio, revise o diff inteiro.
