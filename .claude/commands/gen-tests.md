---
description: Roda o subagent test-writer para escrever/expandir testes de um arquivo ou área já implementada.
---

Alvo (arquivo, classe ou área) em `$ARGUMENTS`. Se vazio, pergunte ao usuário o que precisa de
testes antes de prosseguir — não adivinhe o escopo.

Delegue ao subagent `test-writer` a escrita dos testes para o alvo indicado, em `android/` ou
`backend/` conforme o caso. Ele já segue as prioridades de cobertura definidas na própria
descrição do subagent e nunca mexe em código de produção.

Depois que o subagent terminar, rode o comando de teste do módulo tocado
(`./gradlew testDebugUnitTest` em `android/`, `./gradlew test` em `backend/`) para confirmar que
os testes novos passam, e resuma o resultado para o usuário.
