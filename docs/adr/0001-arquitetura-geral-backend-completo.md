# ADR-0001: Arquitetura geral — backend completo, app Android como cliente de chat

## Status
Aceito — 2026-07-12. **Parcialmente revisto por [ADR-0011](0011-cliente-web-fino-primeiro-android-adiado.md)
(2026-07-17)**: a decisão de arquitetura de backend abaixo continua valendo integralmente; só a
escolha de *qual cliente é construído primeiro* mudou — um web app fino passa à frente do app
Android (adiado, não cancelado). Onde este documento diz "o app Android" como cliente, leia-se
"o cliente fino de chat (hoje web, no futuro possivelmente também Android)".

## Contexto
O planejamento original (`../planejamento-app-rag-android.md`, Decisão 1) considerava três opções:
A) 100% on-device, B) híbrido (ingestão/embeddings/vector DB on-device + geração via API), e
C) backend completo.

Definição de escopo do produto: o usuário final do app **só faz perguntas** pelo aplicativo.
Os livros em PDF são selecionados e disponibilizados pelo desenvolvedor (dono do projeto), não
pelo usuário final — não há fluxo de "importar PDF" para quem usa o app.

## Decisão
Adotar a **Opção C — backend completo**:

- Um backend (Kotlin + Spring Boot — ver [ADR-0006](0006-hospedagem-backend.md)) concentra: ingestão de PDFs, geração de embeddings,
  armazenamento vetorial, retrieval (busca híbrida) e geração de resposta via Claude API.
- O app Android é um **cliente fino de chat**: envia a pergunta (+ histórico), recebe a resposta
  em streaming (SSE) com citações (livro/página). Não faz parsing de PDF, não gera embeddings,
  não mantém vector DB local.
- Ver [ADR-0002](0002-ingestao-fora-do-app.md) (ingestão), [ADR-0003](0003-vector-db-e-embeddings.md)
  (vector DB/embeddings), [ADR-0004](0004-geracao-via-proxy-backend.md) (geração),
  [ADR-0005](0005-autenticacao-e-limites.md) (autenticação/limites),
  [ADR-0006](0006-hospedagem-backend.md) (hospedagem) e
  [ADR-0007](0007-estado-de-conversa.md) (estado de conversa).

## Consequências
- **Positivo:** app drasticamente mais simples (sem ObjectBox, ONNX Runtime, PdfBox-android,
  WorkManager de ingestão); atualizar o acervo de livros não exige novo release do APK; reaproveita
  o perfil backend do desenvolvedor; superfície de testes on-device menor.
- **Negativo:** o app **exige rede** para qualquer pergunta (sem modo offline); os PDFs e o índice
  vetorial residem no servidor, não só no device — aceito conscientemente, já que quem fornece os
  livros é o próprio desenvolvedor/operador do backend, não terceiros.
- **Impacto no planejamento:** as Fases 3 e 4 do documento original (pipeline de ingestão e
  retrieval "no app") passam a ser responsabilidade do backend, não do módulo Android. A Fase 6
  perde a tela de "Biblioteca" com import via SAF (não há usuário final importando livros); pode
  sobrar uma tela simples de seleção de escopo (todos os livros / um livro específico) alimentada
  por uma lista que o backend expõe.
- Módulos Android ficam mais enxutos: `:app` + `:feature:chat` (+ `:core:network`), sem
  `:ingestion`, `:rag`, `:core:data` vetorial.
