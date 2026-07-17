# Spec — Geração de resposta via Claude (RAG), streaming SSE

## Contexto

Corresponde à Fase 5 do `docs/planejamento-app-rag-android.md`, lida à luz do ADR-0004 (geração
via proxy backend, streaming SSE) e do ADR-0011 (o primeiro cliente a consumir esta feature é um
web app fino — `web/`, ainda não criado — e não o app Android). Esta spec cobre a última etapa do
pipeline RAG: transformar uma pergunta do usuário numa resposta em linguagem natural, fundamentada
nos trechos recuperados pela feature de retrieval (`specs/retrieval/`), com citação de livro e
página, entregue em streaming.

Esta é a primeira feature do backend que expõe um **endpoint HTTP público** (`POST /chat` e os
endpoints de histórico do ADR-0007) — ingestão e retrieval, até aqui, só existem como CLI/serviço
interno. A validação de `X-Api-Key` (ADR-0005) e o rate limiting nascem nesta feature, como
pré-requisito de qualquer endpoint que gaste crédito em API paga (constitution.md, seção 1).

## Ator

- **O usuário final**, através de qualquer cliente fino que fale HTTP com o backend (hoje nenhum
  cliente real existe ainda — o web app da Fase 6/ADR-0011 é o próximo a consumir este contrato;
  até lá, testes automatizados e um comando de debug, mesmo padrão do `RetrievalDebugCommand`, são
  os únicos consumidores).
- **O desenvolvedor/operador do backend**, que precisa conseguir testar o pipeline de geração
  isoladamente (sem cliente) durante o desenvolvimento, e que consome o resultado do
  `rag-evaluator` para medir groundedness/qualidade da resposta contra o golden set.

## O que o ator consegue fazer

1. **Fazer uma pergunta em linguagem natural** sobre o acervo de livros (todos ou um subconjunto —
   mesmo contrato de escopo do `RetrievalService`, `specs/retrieval/spec.md`) e receber uma
   resposta gerada, entregue **em streaming** (tokens chegando incrementalmente, não só no final).
2. **Receber uma resposta sempre fundamentada e citada**: toda afirmação relevante da resposta é
   sustentada pelos trechos recuperados, e a resposta indica explicitamente livro e página de onde
   veio a informação (constitution.md, seção 4).
3. **Saber quando a pergunta não tem resposta nos livros**: se o retrieval sinalizar "sem contexto
   relevante" (`RetrievalResult.NoRelevantContext`, `specs/retrieval/`), a resposta declara
   explicitamente que a informação não está no acervo indexado, em vez de inventar uma resposta
   (constitution.md — "resposta sem fundamento no contexto recuperado é bug, não detalhe").
4. **Identificar-se numa conversa** a cada pergunta (um identificador de conversa enviado pelo
   ator — novo, se for a primeira pergunta, ou existente, para continuar uma conversa já iniciada;
   ADR-0007). O backend usa esse identificador para recuperar o histórico e gravar a nova troca.
5. **Continuar uma conversa de múltiplas perguntas**: uma pergunta que só faz sentido à luz de
   pergunta(s) anterior(es) da mesma conversa (ex. "e no capítulo seguinte?") é entendida
   corretamente — antes do retrieval, a pergunta é reescrita usando o histórico da conversa (query
   rewriting, ADR-0004) para que a busca não dependa de o ator repetir contexto já dado.
6. **Retomar uma conversa antiga**: o ator consegue listar suas conversas anteriores e reabrir uma
   específica, recuperando as perguntas e respostas já trocadas (ADR-0007).
7. **Ser barrado sem gastar crédito de API caso não tenha uma chave de acesso válida** — nenhuma
   chamada à Claude ou à Voyage acontece antes da validação de `X-Api-Key` (ADR-0005). Isso vale
   tanto para a chamada de geração quanto para a de query rewriting (também Claude) e para o
   embedding da pergunta (Voyage, dentro do retrieval) — nenhuma delas é paga sem chave válida.
8. **Receber um erro claro quando algo falha**, em vez de uma resposta incompleta apresentada como
   se fosse completa e fundamentada: falha do retrieval (erro de embedding ou de banco, já
   documentado como propagado cru por `specs/retrieval/plan.md`), falha na chamada à Claude, ou
   uma queda da conexão com a Claude no meio do streaming SSE — em nenhum desses casos o ator deve
   receber uma resposta parcial sem indicação de que ela é incompleta, nem qualquer detalhe de erro
   que exponha uma chave de API.

## Critérios de aceite (observáveis)

- **CA1**: uma pergunta cuja resposta está claramente em um livro ingerido produz uma resposta que
  cita corretamente o livro e a página de origem do trecho usado.
- **CA2**: uma pergunta sem relação com nenhum conteúdo indexado (retrieval devolve
  `NoRelevantContext`) produz uma resposta que declara explicitamente a ausência de fundamento no
  acervo — nunca uma resposta inventada apresentada como se fosse fundamentada.
- **CA3**: a resposta chega ao ator de forma incremental (streaming via SSE), não só como um bloco
  único ao final do processamento completo.
- **CA4**: uma segunda pergunta na mesma conversa que depende de contexto de pergunta(s)
  anterior(es) recupera trechos e produz resposta coerentes com esse contexto (evidência de que o
  histórico influenciou o retrieval/a geração, não só a pergunta isolada).
- **CA5**: uma requisição sem `X-Api-Key` válido é rejeitada antes de qualquer chamada à Claude ou
  à Voyage — nenhum crédito de API é gasto numa requisição não autenticada.
- **CA6**: o escopo de busca informado pelo ator (todos os livros / um subconjunto) é respeitado —
  a resposta nunca cita ou se fundamenta em um livro fora do escopo pedido.
- **CA7**: reabrir uma conversa antiga recupera as perguntas e respostas trocadas anteriormente
  nela, na ordem correta.
- **CA8**: um volume de requisições acima do limite configurado (rate limit) recebe um erro claro
  ao ator em vez de degradar o backend ou estourar o orçamento de API sem controle.
- **CA9**: nenhuma chave de API de provedor de IA (Anthropic, Voyage) aparece em log, resposta de
  erro ou qualquer payload devolvido ao ator, em nenhum cenário (constitution.md, seção 1).
- **CA10**: uma falha na chamada de retrieval (erro de embedding ou de acesso a dados), na chamada
  de query rewriting, ou na chamada de geração propriamente dita — incluindo uma queda de conexão
  no meio do streaming SSE — produz um erro claro e reconhecível pelo ator como "algo falhou", sem
  jamais apresentar uma resposta parcial/incompleta como se fosse uma resposta completa e
  fundamentada.
- **CA11**: uma pergunta enviada sem identificador de conversa inicia uma conversa nova; uma
  pergunta enviada com o identificador de uma conversa existente é tratada como continuação dela
  (histórico recuperado, nova troca gravada na mesma conversa).

## Fora de escopo (explicitamente)

- Qualquer cliente real consumindo este endpoint (o web app fino da Fase 6/ADR-0011 é spec
  separada, que nasce depois desta feature existir).
- Autenticação por usuário/login — continua chave estática compartilhada (ADR-0005); mudar isso é
  decisão de um ADR revisando o ADR-0005, não desta spec.
- Endpoint de exclusão de histórico de conversa — mencionado como melhoria futura no ADR-0007, não
  bloqueante para esta feature a menos que vire requisito explícito depois.
- Corte automático de gasto por teto de tokens/mês — ADR-0005 já registra isso como melhoria
  futura, não bloqueante do MVP.
- Re-ranking via cross-encoder ou LLM sobre os trechos recuperados — fora de escopo também na
  spec de retrieval (v2 do planejamento original).
- Qualquer mudança em chunking, embedding ou no algoritmo de retrieval em si — esta feature
  **consome** `RetrievalService` como está; uma mudança ali é spec/PR separado, sujeito ao
  `rag-evaluator` conforme constitution.md.
- UI de citações clicáveis, seletor de escopo, histórico visual — pertence ao cliente (Fase 6),
  não ao backend.
