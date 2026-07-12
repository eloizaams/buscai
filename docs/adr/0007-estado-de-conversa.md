# ADR-0007: Propriedade do estado de conversa

## Status
Aceito — 2026-07-12

## Contexto
O `android-architect` apontou uma tensão entre "app é um cliente fino" ([ADR-0001](0001-arquitetura-geral-backend-completo.md))
e a necessidade de histórico para query rewriting multi-turno ([ADR-0004](0004-geracao-via-proxy-backend.md)):
se o app não guarda nada, de onde vem o histórico a cada pergunta?

## Decisão
- O **histórico de conversa vive no backend** (tabelas `conversations`/`messages` no mesmo Postgres
  do [ADR-0006](0006-hospedagem-backend.md)), não no app.
- O app gera um identificador de conversa local (ex. UUID salvo em DataStore) e o envia em cada
  chamada a `/chat`. O backend usa esse ID para recuperar o histórico, fazer o query rewriting e
  gravar a nova mensagem — o app nunca precisa reenviar o histórico inteiro nem persisti-lo.
- Para reabrir uma conversa antiga (tela de histórico), o app busca no backend por
  `GET /conversations` e `GET /conversations/{id}` em vez de manter cópia local.

## Consequências
- Mantém o app realmente fino, coerente com o ADR-0001.
- Sem autenticação de usuário real (o [ADR-0005](0005-autenticacao-e-limites.md) usa uma chave
  compartilhada, não login), conversas são isoladas por `device-id` gerado localmente, não por
  conta — múltiplos dispositivos com a mesma chave de API têm históricos separados entre si, mas
  não há garantia de que um device não possa ler o histórico de outro se souber o ID da conversa.
  Aceitável no escopo atual (poucos usuários confiáveis); revisitar se isso mudar.
- Apagar o app/dados locais não apaga o histórico do lado do servidor — precisa de um endpoint de
  exclusão explícito se isso for um requisito de privacidade.
