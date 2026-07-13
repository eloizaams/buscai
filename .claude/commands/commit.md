---
description: Commita a task concluída seguindo as convenções de commit do CLAUDE.md (Git flow). Um commit por task; sem push nem PR.
---

Pré-requisito: quem implementou já rodou `ktlintFormat` e os testes do módulo tocado. Esta skill
não roda testes de novo — só empacota a task num commit.

1. Rode `git status` e `git diff`. Se não houver nada a commitar, diga isso e pare.
2. Se a branch atual for `main`, **não commite**: crie/mude para uma branch curta antes
   (`feature/<spec>` | `fix/<assunto>` | `chore/<assunto>`, ver Git flow do `CLAUDE.md`).
3. Stage seletivo — adicione só os arquivos desta task. Nunca adicione artefato de build, arquivo
   de IDE, `settings.local.json`, `local.properties`, `.env*`, `*.jks`/`*.keystore` nem qualquer
   segredo. Nunca use `git add -f`.
4. Componha a mensagem **seguindo o padrão do `CLAUDE.md` (Git flow)** — não recopie as regras
   aqui, siga a fonte: imperativo, pt-BR, `Fase N: <o que>` ou `<área>: <o que>`, porquê no corpo
   quando não for óbvio.
5. `git commit` e mostre o resultado (hash + resumo em uma linha).

Não faça `git push` nem abra PR — isso é o passo final da implementação (`/pr`), não da task.

`$ARGUMENTS`: se fornecido, uma mensagem/área para focar (ex.: `backend: extrair texto do PDF`).
Se vazio, derive a mensagem do que a task entregou.
