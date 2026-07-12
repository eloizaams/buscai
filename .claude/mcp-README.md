# MCPs recomendados para este projeto

## Habilitar agora

**Context7** (`context7`) — docs atualizadas de bibliotecas direto no contexto, sem depender do
conhecimento de treino do modelo. Essencial aqui porque o projeto usa libs que mudam rápido e
onde acertar a API exata importa: ObjectBox (HNSW), PdfBox-Android, WorkManager, Ktor client.
Uso: peça "consulte a doc do ObjectBox sobre HNSW index" e o Claude resolve o ID da lib e busca
a versão atual antes de gerar código — evita alucinar assinatura de API antiga.

**GitHub** (`github`) — necessário para o fluxo de PR do SDD (task implementada → PR → review).
Exige um `GITHUB_TOKEN` com escopo `repo` como variável de ambiente (nunca hardcoded no `.mcp.json`
— o `${GITHUB_TOKEN}` acima já resolve isso via env var).

## Avaliar conforme a necessidade (não habilitar por padrão)

- **Filesystem MCP**: geralmente desnecessário — o Claude Code já tem Read/Write/Edit nativos
  mais eficientes em tokens que um MCP genérico de filesystem.
- **Servidor MCP de banco de dados** (ex.: Postgres/SQLite): só relevante se você mover o proxy
  backend para persistência própria (histórico de conversas, analytics de uso). Não é necessário
  para o vector DB on-device (ObjectBox roda embarcado no app, não via MCP).
- **MCP de CI/CD** (ex.: status de pipeline): útil só se o ciclo de review incluir aguardar o CI
  no mesmo fluxo. Adicione quando isso virar gargalo real, não antes.

## Por que não mais que isso

Cada servidor MCP conectado adiciona definições de ferramentas ao contexto mesmo sem uso —
alguns milhares de tokens por servidor. As definições de ferramenta específicas só carregam
quando o Claude efetivamente as invoca, mas os nomes/descrições ficam sempre presentes.
Audite mensalmente: desconecte o que não foi usado na última semana.
