# ADR-0006: Hospedagem do backend e do banco (sem custo recorrente)

## Status
Aceito — 2026-07-12

## Contexto
O [ADR-0001](0001-arquitetura-geral-backend-completo.md) aceitou que o app depende de um backend
sempre acessível, mas não definiu onde ele roda nem seu custo. Requisito explícito: sem custo
recorrente (uso pessoal).

## Decisão
- **Banco (Postgres + pgvector):** **Neon** (Postgres serverless, free tier), que suporta a
  extensão `pgvector` necessária para o [ADR-0003](0003-vector-db-e-embeddings.md). Escala a zero
  quando ocioso.
- **API (Spring Boot):** free tier de **Render** ou **Fly.io** (a decidir no momento do deploy,
  conforme qual tiver o free tier mais estável na época) rodando a aplicação em container.
- Ambos os free tiers **hibernam/escalam a zero após inatividade** — a primeira requisição depois
  de um período parado sofre *cold start* de alguns segundos (subir o container + reconectar ao
  Postgres). O app deve tratar isso explicitamente (timeout de rede generoso na primeira chamada,
  indicador de "conectando..." em vez de erro).

## Consequências
- Custo recorrente esperado: **zero**, dentro dos limites dos free tiers.
- Cold start ocasional é uma característica aceita da arquitetura, não um bug — precisa de UX
  própria no app (não confundir com falha de rede).
- Free tiers têm teto de armazenamento/uso (verificar limites atuais do Neon e do Render/Fly no
  momento do setup) — se o acervo de livros ou o tráfego crescerem além do free tier, este ADR
  precisa ser revisitado (ex. migrar para o Oracle Cloud Always Free ou um VPS pago).
- Reforça a necessidade do [ADR-0005](0005-autenticacao-e-limites.md): em free tier, gasto
  descontrolado nas APIs de IA (não no hosting) é o principal risco financeiro real.
