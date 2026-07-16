# Spec — Busca e retrieval sobre o acervo ingerido

## Contexto

Corresponde à Fase 4 do `docs/planejamento-app-rag-android.md`, já lida à luz do pivô de
arquitetura (ADR-0001): retrieval não é mais uma feature do app Android, é uma capacidade do
backend. Esta spec cobre exclusivamente a busca sobre o conteúdo já ingerido pela feature de
ingestão (`specs/ingestao-pdf/`) — transformar uma pergunta em linguagem natural num conjunto de
trechos (chunks) relevantes, com metadados suficientes para citação. Não cobre a geração da
resposta final via LLM, que é uma spec separada (Fase 5).

## Ator

- **Consumidor primário: a feature de geração (Fase 5, ainda não especificada).** Ela envia uma
  pergunta (e um escopo — todos os livros ou um livro específico) e recebe de volta os trechos
  mais relevantes para montar o contexto do prompt de geração.
- **Consumidor secundário, hoje: o desenvolvedor/operador do backend.** Enquanto a geração não
  existe, o operador precisa conseguir rodar a busca isoladamente durante o desenvolvimento e
  para alimentar o `rag-evaluator` (`specs/eval/golden-set.json`), que mede recall@k comparando
  os `expected_sources` do golden set contra os trechos retornados.

A forma concreta dessa interação (endpoint HTTP interno, comando de linha, ou só uma chamada de
serviço Kotlin usada em teste/futura geração) é decisão técnica do `plan.md`, não desta spec.

## O que o ator consegue fazer

1. **Buscar trechos relevantes** dada uma pergunta em linguagem natural, recebendo os top-k
   trechos ordenados por relevância — cada um com o texto, o livro de origem, a página e (quando
   detectado na ingestão) o capítulo, suficiente para uma citação futura (livro + página).
2. **Restringir a busca a um livro específico** (escopo) em vez de buscar no acervo inteiro.
3. **Encontrar termos exatos e nomes próprios** que uma busca puramente semântica pode não
   colocar no topo (busca híbrida léxica + vetorial, recomendada em ADR-0003).
4. **Receber um conjunto de trechos pronto para consumo pela geração**: sem trechos vizinhos
   redundantes duplicados no resultado, dentro de um orçamento de tamanho que caiba no prompt de
   geração.
5. **Buscar sempre sobre a versão pronta (`READY`) de cada livro**, nunca sobre uma versão em
   ingestão ou que falhou — coerente com o swap atômico do ADR-0008: a busca não pode expor um
   estado intermediário ou quebrado do acervo.
6. **Saber quando não há nada relevante**: se a pergunta não tem relação com o conteúdo indexado
   (similaridade baixa em todos os trechos candidatos), a busca sinaliza isso explicitamente em
   vez de forçar um top-k de trechos sem relação com a pergunta.

## Critérios de aceite (observáveis)

- **CA1**: dada uma pergunta cuja resposta está claramente em um livro ingerido, os trechos
  corretos aparecem entre os resultados retornados (validável contra `expected_sources` do
  golden set).
- **CA2**: buscar com escopo restrito a um livro específico nunca retorna trechos de outro livro.
- **CA3**: buscar um termo exato ou nome próprio presente literalmente no texto de algum chunk
  retorna esse chunk mesmo em casos onde a similaridade puramente semântica não o colocaria entre
  os primeiros — evidência de que a fusão léxica+vetorial está funcionando, não só a vetorial.
- **CA4**: dois trechos vizinhos fortemente sobrepostos do mesmo ponto do texto não aparecem
  ambos, redundantemente, no conjunto final devolvido.
- **CA5**: o conjunto de trechos retornado nunca ultrapassa um orçamento máximo definido (em
  tokens), mesmo quando o top-k bruto da busca ultrapassaria esse limite.
- **CA6**: buscar sobre um livro cuja versão mais recente está `INGESTING` ou `FAILED` continua
  retornando resultados da versão anterior `READY` (se existir) — nunca resultados da versão
  incompleta ou quebrada, nunca uma mistura das duas.
- **CA7**: uma pergunta sem relação com nenhum conteúdo indexado é sinalizada como "sem contexto
  relevante" em vez de devolver trechos de baixa relevância como se fossem uma resposta válida.
- **CA8**: latência de busca abaixo de 100 ms com um acervo da ordem de 50 mil chunks, **em estado
  estável** (excluindo cold start do backend — ADR-0006, que já trata isso como estado esperado em
  free tier) — meta original da Fase 4 do planejamento, a validar com pgvector + HNSW em condições
  reais antes de considerar a meta cumprida.

## Fora de escopo (explicitamente)

- Geração da resposta final via LLM e streaming SSE ao app (Fase 5, spec separada).
- Re-ranking via cross-encoder ou LLM sobre os candidatos (v2, adiado no planejamento original).
- Query rewriting multi-turno usando histórico de conversa — pertence à geração (Fase 5), que é
  quem tem acesso ao histórico (ADR-0007); esta spec recebe sempre uma pergunta já "final".
- UI de citações clicáveis ou seletor de escopo no app Android (Fase 6).
- Decidir se a busca é exposta como endpoint HTTP público, endpoint interno, ou só como serviço
  consumido em processo pela geração — decisão técnica do `plan.md`.
- OCR ou qualquer tratamento de PDF — já coberto (ou explicitamente fora de escopo) pela feature
  de ingestão (`specs/ingestao-pdf/spec.md`).
