---
description: Inicia o loop de SDD para uma feature nova — cria/atualiza specs/<feature>/spec.md e plan.md antes de qualquer código.
---

Feature (nome curto em kebab-case + descrição em uma frase, se não vier em `$ARGUMENTS`,
pergunte ao usuário): `$ARGUMENTS`

Siga o loop de SDD descrito em `docs/planejamento-app-rag-android.md` (Fase 2) e em `CLAUDE.md`:

1. Leia `specs/constitution.md` (se ainda não existir, avise o usuário e pare — constitution.md
   é pré-requisito de qualquer spec) e `docs/adr/` para não contradizer decisões já tomadas.
2. Crie (ou atualize) `specs/<feature>/spec.md` com os requisitos funcionais da feature: o que o
   usuário consegue fazer, critérios de aceite observáveis, o que fica explicitamente fora de
   escopo. Sem detalhe técnico de implementação aqui.
3. Delegue ao subagent `android-architect` a revisão do encaixe arquitetural antes de detalhar o
   plano técnico — ele valida contra os ADRs e a constitution, não decide arquitetura nova.
4. Com o parecer do arquiteto, escreva `specs/<feature>/plan.md`: módulos/arquivos afetados
   (`android/` e/ou `backend/`), contratos entre camadas, modelos de dado novos ou alterados.
5. Quebre o plano em `specs/<feature>/tasks.md`: itens pequenos, testáveis, ordenados — cada um
   deve caber numa sessão de implementação do subagent `kotlin-implementer`.
6. Pare aqui. Não implemente nada nesta etapa — apresente spec.md/plan.md/tasks.md para o usuário
   revisar antes de qualquer código ser escrito.
