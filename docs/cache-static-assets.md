# Follow-up: cache de estáticos (`web/`) pode prender usuário em versão antiga

Registrado em 2026-07-22, durante a verificação manual de `specs/fontes-web-titulo-obra` (T1).

## O que aconteceu

Testando manualmente a renderização de fontes no cliente web, o navegador continuou servindo uma
cópia antiga de `app.js` — de antes da mudança entrar em produção — mesmo depois de:

- reiniciar o backend (`scripts/dev-run.sh`);
- `location.reload()`/recarregar a aba normalmente;
- navegar para uma URL nova da página (`?cb=...`).

Só forçar uma URL de script nunca antes vista pelo navegador (`app.js?cachebust=...`) fez o
navegador buscar o arquivo fresco. Até lá, o código novo parecia "não funcionar" quando na
verdade já estava correto — o problema era cache do navegador, não o código.

## Por que isso importa em produção, não só no dev local

`backend/src/main/resources/application.yml` não configura nenhum `Cache-Control` para os
estáticos servidos de `web/` (ADR-0011/0012). Sem essa configuração, o Spring Boot deixa o
navegador aplicar cache heurístico ao `app.js`/`styles.css`. Isso significa que, depois de um
deploy real, o navegador de um usuário que já tinha usado o app antes pode continuar rodando o
JS/CSS antigo por um bom tempo — sem erro nenhum visível, só comportamento desatualizado (ex.:
uma feature nova "não aparece" até um hard-refresh).

## Sugestão (não implementada nesta spec — fora de escopo)

Alguma das duas, decisão técnica a tomar quando for priorizado:

1. **Cache-busting por hash de conteúdo** — nomear os arquivos com hash do conteúdo (ex.
   `app.abc123.js`) e referenciar esse nome no `index.html` gerado no build. Exige um passo de
   build (o projeto hoje é HTML/CSS/JS puro sem build step, ADR-0011/0012 — mudaria essa premissa).
2. **`Cache-Control: no-cache` nos estáticos** — mais simples, sem exigir build step: configurar
   Spring para responder `Cache-Control: no-cache` (o navegador sempre revalida via
   `If-None-Match`/ETag antes de usar o cache, então uma mudança real é sempre pega, mas ainda
   evita rebaixar toda vez que o conteúdo não mudou).

Recomendação: opção 2 primeiro (mais barata, não quebra a premissa de "sem build step" do
ADR-0011/0012); considerar opção 1 só se o projeto vier a adotar um build step por outro motivo.
