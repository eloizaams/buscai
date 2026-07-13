# ADR-0004: Geração de resposta via backend próprio (Claude API), streaming SSE

## Status
Aceito — 2026-07-12

## Contexto
O planejamento original já recomendava nunca embutir a API key da Claude no APK, exigindo um
proxy backend fino. Com o [ADR-0001](0001-arquitetura-geral-backend-completo.md), esse backend já
existe para ingestão e retrieval — a geração é só mais uma responsabilidade do mesmo serviço, não
um componente novo.

## Decisão
- O backend (Spring Boot) expõe um endpoint de chat (ex.: `POST /chat` com resposta SSE,
  autenticado conforme [ADR-0005](0005-autenticacao-e-limites.md)) que:
  1. Recebe a pergunta + histórico da conversa + escopo (todos os livros / livro específico).
  2. Se multi-turno, reescreve a pergunta usando o histórico (query rewriting) antes do retrieval.
  3. Faz o retrieval híbrido (ADR-0003), monta o contexto (dedup de chunks vizinhos, orçamento de
     tokens, metadados para citação).
  4. Chama a Messages API da Claude (Haiku 4.5 por custo, Sonnet para qualidade — configurável)
     com streaming, e repassa os tokens ao app via SSE.
  5. A API key da Claude e da Voyage AI ficam **só no servidor** (variável de ambiente/secret),
     nunca no cliente.
- **Prompt de sistema:** responder somente com base no contexto fornecido; citar livro e página;
  declarar explicitamente quando a resposta não está nos livros.
- O app Android consome o SSE, exibe streaming e cada citação é clicável (abre o trecho/página de
  origem, cujo conteúdo também vem do backend, já que não há PDF nem chunk local no device).

## Consequências
- Um único serviço backend concentra ingestão, retrieval e geração — menos peças móveis para operar
  do que a arquitetura híbrida original.
- App não precisa embutir nenhuma credencial; toda autenticação com provedores de IA é
  responsabilidade do backend.
- Modo offline (Gemma on-device, cogitado como v2 no planejamento original) deixa de fazer sentido
  como evolução natural, já que a arquitetura agora é backend-first por decisão explícita — se
  offline vier a ser um requisito real no futuro, precisa de um novo ADR revisitando o ADR-0001.
