# Spec — Pipeline de ingestão de livros em PDF

## Contexto

Quem alimenta o acervo de livros é o desenvolvedor/operador do backend, via uma ferramenta que
ele roda localmente — não é uma feature do app Android nem exposta como endpoint público
(decisão já registrada em ADR-0002). O usuário final do app nunca importa PDF; ele só pergunta
sobre livros que já foram ingeridos.

Esta spec cobre exclusivamente a ingestão: transformar um arquivo PDF em conteúdo pesquisável
(indexado) pela feature de busca/chat, que será especificada separadamente. Corresponde à
Fase 3 do `planejamento-app-rag-android.md`.

> Revisão do `android-architect` identificou uma lacuna entre identidade de "arquivo" (hash) e
> identidade de "livro", registrada em [ADR-0008](../../docs/adr/0008-identidade-e-versionamento-de-livros-ingeridos.md).
> Os critérios de aceite abaixo já refletem essa decisão.

## Ator

Desenvolvedor/operador do backend, com acesso direto à máquina/host onde a ferramenta roda e ao
banco de dados de destino.

## O que o ator consegue fazer

1. **Ingerir um livro novo**: apontar a ferramenta para um arquivo PDF, identificando o livro por
   um identificador estável que o próprio ator escolhe (ex.: `dom-casmurro`), e obter, ao final,
   o livro e seu conteúdo disponíveis para busca — sem precisar tocar em banco de dados
   manualmente.
2. **Saber se um PDF é utilizável**: se o PDF não tiver camada de texto (é só imagem escaneada),
   a ferramenta avisa isso claramente, não trava e não polui o acervo com lixo (chunks vazios ou
   sem sentido).
3. **Rodar de novo sem duplicar nem reprocessar à toa**: rodar a ferramenta de novo para o mesmo
   identificador de livro, arquivo e configuração de embedding não cria conteúdo duplicado nem
   gasta chamadas de API à toa — a ferramenta reconhece que já foi ingerido e avisa. Se o arquivo
   ou a configuração de embedding mudaram desde a última ingestão daquele identificador, a
   ferramenta **não reprocessa sozinha** (reprocessar chama uma API paga) — ela avisa que existe
   uma versão diferente disponível e que uma reindexação explícita é necessária.
4. **Forçar reindexação**: quando necessário (ex.: o modelo de embedding mudou, ou o conteúdo do
   PDF foi corrigido), o ator consegue pedir explicitamente que um livro já ingerido seja
   reprocessado do zero, usando o mesmo identificador de livro. Enquanto a reindexação não
   termina com sucesso, a busca continua servindo a versão anterior do livro — nunca uma mistura
   das duas nem uma versão incompleta.
5. **Acompanhar o progresso**: durante a ingestão de um livro grande, o ator vê progresso
   (ex.: página atual / total) e, ao final, um resumo (quantas páginas, quantos trechos gerados,
   tempo total).
6. **Saber por que uma ingestão falhou**: se algo der errado (arquivo corrompido, PDF inexistente,
   erro ao gerar embeddings), o ator recebe uma mensagem de erro específica o suficiente para
   saber o que corrigir — não só um stack trace genérico.

## Critérios de aceite (observáveis)

- **CA1**: dado um PDF válido com camada de texto, ao final da ingestão o conteúdo do livro está
  disponível para busca, associado a metadados de origem (de qual página/capítulo cada trecho
  veio) suficientes para uma citação futura (livro + página).
- **CA2**: livros grandes (300+ páginas) são o caso comum do acervo, não uma exceção — a
  ingestão completa sem falha por falta de memória, e o consumo de memória não cresce
  proporcionalmente ao tamanho do livro (processamento incremental; o livro inteiro nunca é
  carregado de uma vez, nem em texto extraído nem em chunks/embeddings pendentes).
- **CA3**: dado um PDF onde a grande maioria das páginas não tem camada de texto extraível (só
  imagem), a ferramenta identifica isso antes de gerar conteúdo vazio/sem sentido, avisa o ator e
  não deixa o livro em estado "pronto" enganoso.
- **CA4**: rodar a ingestão para o mesmo identificador de livro, arquivo e configuração de
  embedding não duplica o conteúdo do livro no acervo — a ferramenta reconhece que já foi
  ingerido e avisa, sem reprocessar. Se o arquivo ou a configuração de embedding mudaram desde a
  última ingestão daquele identificador, a ferramenta recusa reprocessar sem uma reindexação
  explícita e informa que há uma versão diferente disponível (ver ADR-0008).
- **CA5**: pedir reindexação explícita de um livro já ingerido substitui o conteúdo anterior
  daquele livro pelo novo **somente depois que a nova ingestão terminar com sucesso** — sem
  deixar conteúdo órfão da versão antiga nem um estado intermediário visível para a busca.
- **CA6**: durante uma ingestão longa, o ator consegue ver progresso incremental (não só
  "rodando..." sem informação até o fim).
- **CA7**: um arquivo inexistente, corrompido, de outro formato, ou uma falha no meio do
  processamento (ex.: erro ao gerar embeddings) produz uma mensagem de erro clara e específica,
  sem deixar o acervo em estado parcial/inconsistente — a versão anterior do livro (se houver)
  permanece intacta e servível.
- **CA8**: dois trechos (chunks) vizinhos de um mesmo livro preservam contexto suficiente para
  não cortar uma ideia no meio de forma que a busca/geração perca sentido. Até existir golden set
  para avaliação semântica via `rag-evaluator`, a ferramenta valida estruturalmente cada chunk
  (não vazio, tamanho e overlap dentro dos limites definidos em ADR-0002) e recusa indexar um
  livro cujos chunks violem esses limites.

## Fora de escopo (explicitamente)

- Upload de PDF via app Android ou via qualquer endpoint HTTP público (decidido e rejeitado em
  ADR-0002; só CLI local).
- OCR de PDFs escaneados sem camada de texto — a ferramenta detecta e avisa, não processa
  (fica para v2, conforme planejamento).
- A feature de busca/retrieval sobre o conteúdo ingerido (spec separada).
- Autenticação/autorização da própria ferramenta — ela roda localmente pelo operador, que já tem
  acesso direto ao host e ao banco (ADR-0002).
- Edição ou remoção de livros já ingeridos por outro meio que não a reindexação (CA5). Remoção
  explícita de um livro pode virar uma spec futura se a necessidade aparecer.
- Extração de metadados bibliográficos automática (autor, ISBN) além do que o próprio ator
  informar ou do que for trivial de extrair do PDF.
