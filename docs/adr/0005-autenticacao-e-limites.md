# ADR-0005: Autenticação app→backend e limites de uso

## Status
Aceito — 2026-07-12

## Contexto
O `android-architect` apontou, na revisão da Fase 0, que nenhum ADR definia quem pode chamar o
endpoint de chat. Como o backend paga por token nas APIs da Claude e da Voyage AI
([ADR-0004](0004-geracao-via-proxy-backend.md), [ADR-0003](0003-vector-db-e-embeddings.md)), um
endpoint aberto vira um proxy gratuito para quem descobrir a URL — risco financeiro direto, não
teórico.

## Decisão
- **Autenticação:** chave de API estática e compartilhada, enviada pelo app em um header
  (ex. `X-Api-Key`), validada por um filtro/interceptor no Spring Boot antes de qualquer chamada
  às APIs de IA. A chave fica no `BuildConfig` do app (não é um segredo forte — pode ser extraída
  de um APK decompilado — mas eleva a barreira o suficiente para este caso de uso: poucos usuários
  conhecidos, não uma distribuição pública ampla).
- **Rate limiting:** limite simples por chave/IP no backend (ex. bucket de requisições por minuto),
  suficiente para conter abuso acidental ou automatizado caso a chave vaze.
- **Teto de gasto:** acompanhar consumo pelos dashboards de billing da Anthropic e da Voyage AI.
  Registrar como melhoria futura (não bloqueia o MVP) um contador de tokens/mês no próprio backend
  com corte automático ao atingir um limite configurado, para não depender só do dashboard externo.

## Consequências
- Se o app for distribuído mais amplamente no futuro, a chave estática deixa de ser suficiente e
  este ADR precisa ser revisitado (ex. autenticação por usuário).
- Rotacionar a chave exige novo release do APK (aceitável na escala atual).
