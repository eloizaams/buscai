# ADR-0008: Identidade de livro, chave de idempotência e reindexação atômica

## Status
Aceito — 2026-07-12

> **Nota (2026-07-21, `specs/limite-item-numerado/`, CA8):** a chave de gatilho skip/reindex
> (`bookId`, `hashDoArquivo`, `versãoDoModeloDeEmbedding`) decidida acima tem uma lacuna real: com
> `--reindex`, se essa combinação bater com a versão ativa (mesmo arquivo, mesmo modelo), a CLI
> pulava mesmo assim — a flag existe justamente para forçar reprocessamento e não deveria respeitar
> o mesmo gate que ela serve para contornar. Decisão aprovada: `--reindex=true` sempre reprocessa,
> independentemente da chave de gatilho bater ou não; sem a flag, o comportamento decidido acima
> (skip se bate, exige a flag se não bate) continua valendo. Ainda pendente de implementação —
> rastreado em `specs/limite-item-numerado/tasks.md` T6 (task futura desta mesma spec).

> **Nota (2026-07-22, `specs/limite-item-numerado/`, achado de overlap):** a "Verificação estrutural
> de chunk" descrita abaixo (overlap real dentro de 10–20%) nunca tinha sido exercitada de verdade
> contra itens numerados atômicos, porque nenhum item era detectado como fronteira própria antes da
> correção de `Chunker.splitIntoParagraphs` (ver ADR-0013 seção 4). Uma vez corrigida a detecção, o
> teste sintético confirmou violação real: `ChunkValidator.measureOverlapRatio` divide o overlap
> medido pelo `tokenCount` **total** do chunk anterior (que já inclui overlap por ele herdado), não
> pelo conteúdo próprio — para um item curto isso facilmente estoura o teto de 20% mesmo sem
> problema real de continuidade. Decisão: a checagem de overlap deixa de se aplicar quando
> `referenceType == NUMBERED_ITEM` — mesmo precedente já aberto pelo ADR-0013 (seção 4) para o piso
> mínimo de tokens (item numerado é a unidade atômica de chunk; overlap existe para dar continuidade
> quando o corte de parágrafo é arbitrário, premissa que não se aplica quando o corte é sempre uma
> fronteira de item deliberada). Ver `specs/limite-item-numerado/plan.md`.

## Contexto
Ao especificar a feature de ingestão (`specs/ingestao-pdf/spec.md`), o `android-architect` apontou
que o [ADR-0002](0002-ingestao-fora-do-app.md) decidiu usar hash SHA-256 do arquivo para
"idempotência/reindexação" sem definir a granularidade — e isso gera uma contradição real entre
dois comportamentos que a spec precisa de ambos:

- **Não duplicar** ao rodar a ingestão duas vezes com o mesmo conteúdo.
- **Permitir reindexar** um livro já ingerido (ex.: PDF corrigido, ou modelo de embedding trocado
  — [ADR-0003](0003-vector-db-e-embeddings.md) já exige gravar a versão do modelo por chunk para
  detectar essa necessidade).

Hash de arquivo identifica um **arquivo**, não um **livro**. Um PDF corrigido (mesmo livro,
conteúdo diferente) tem hash diferente — se a identidade do livro for o hash, a correção vira um
livro novo e duplicado em vez de uma reindexação do livro existente.

Também ficou em aberto a estratégia para a reindexação não deixar o acervo em estado parcial se
falhar no meio (a geração de embeddings é uma chamada de API externa, por livro potencialmente
milhares de chunks — uma falha na chamada 5000 de 8000 não pode corromper o livro).

## Decisão

- **Identidade do livro:** um `bookId` (slug) **fornecido explicitamente pelo operador** como
  argumento da CLI, estável entre execuções — não é derivado do hash do arquivo nem do nome do
  arquivo. O operador decide o slug (ex.: `dom-casmurro`) e o reusa deliberadamente quando quer
  reindexar o mesmo livro a partir de um PDF diferente.
- **Chave de gatilho skip/reindex:** a combinação `(bookId, hashDoArquivo, versãoDoModeloDeEmbedding)`.
  - Se essa combinação já existe e está com ingestão completa: a CLI **pula** e informa que já foi
    ingerido (comportamento padrão, sem flag).
  - Se o `bookId` já existe mas `hashDoArquivo` e/ou `versãoDoModeloDeEmbedding` mudaram: a CLI
    **não reindexa silenciosamente** — reporta que há uma versão diferente disponível e exige a
    flag explícita `--reindex` para reprocessar (protege contra reprocessamento acidental caro,
    já que embeddings são API paga — ADR-0003).
  - Se o `bookId` é novo: ingestão normal, sem exigir flag.
- **Reindexação atômica (swap de versão):** reindexar não é "apagar o antigo e inserir o novo" em
  sequência. A nova ingestão escreve seus chunks/vetores sob uma nova versão interna do livro; só
  quando **toda** a nova versão termina com sucesso, o ponteiro "versão ativa" do livro muda para
  ela, e a versão anterior é removida. Se a nova ingestão falhar em qualquer ponto, a versão ativa
  continua sendo a anterior — o acervo nunca fica com um livro parcialmente reindexado visível
  para a busca.
- **Detecção de PDF sem camada de texto (critério mensurável):** uma página é considerada "sem
  texto" quando o texto extraído tem menos de 20 caracteres úteis (após remover espaços). O livro
  é sinalizado como "sem camada de texto" quando mais de 90% das páginas se enquadram nesse caso
  (o limiar, em vez de 100%, tolera capas/páginas em branco escaneadas dentro de um PDF majoritariamente
  textual). Threshold revisável se a prática mostrar falsos positivos/negativos.
- **Verificação estrutural de chunk (proxy para CA8 até existir golden set):** a própria ingestão
  valida, para cada chunk gerado: não está vazio; contagem de tokens dentro de 300–800
  (ADR-0002); overlap real com o chunk vizinho dentro de 10–20%. Viola qualquer um: falha a
  ingestão daquele livro em vez de indexar silenciosamente um chunk fora do padrão. Isso é um
  proxy estrutural, não substitui o `rag-evaluator` (que mede groundedness/recall semânticos) —
  fica registrado para não bloquear a spec numa avaliação que só existe depois do golden set.

## Consequências
- O operador precisa escolher e lembrar o `bookId` — é responsabilidade manual dele, não
  derivada automaticamente. Aceitável na escala atual (poucos livros, um operador).
- Todo `book` no schema de dados carrega uma versão/geração ativa; a reindexação em swap exige
  que chunks/vetores referenciem essa geração, não só o `bookId` — impacto direto no modelo de
  dados do `plan.md` desta feature.
- `--reindex` custa uma reingestão completa do livro (todas as chamadas de embedding de novo),
  nunca um reprocessamento incremental — aceitável dado o volume atual; revisitar se o custo por
  reindexação virar problema real.
