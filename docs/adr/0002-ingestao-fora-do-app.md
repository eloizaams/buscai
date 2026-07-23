# ADR-0002: Ingestão de PDFs via ferramenta externa (CLI), não pelo app

## Status
Aceito — 2026-07-12

> **Nota (2026-07-22, `specs/conteudo-paginas-overlap/`, overlap condicional e delimitação de
> conteúdo):** duas emendas ao chunking descrito acima. (a) O overlap de 10–20% deixa de ser
> universal — passa a ser condicional ao `referenceType` do chunk (ADR-0013): `CHAPTER` mantém
> overlap (~15%, dá continuidade em cortes de parágrafo que são arbitrários por natureza);
> `NUMBERED_ITEM` fica **sem** overlap no texto — o corte entre chunks desse estilo é sempre uma
> fronteira deliberada de item, nunca um corte arbitrário de parágrafo (mesmo racional já usado no
> ADR-0013 seção 4 para o piso mínimo de tokens). Implementado em `Chunker.kt`. (b) A ingestão
> ganha uma capacidade nova: delimitação de conteúdo por intervalo de páginas fornecido pelo
> operador (`--content-pages=<início>-<fim>`), para excluir front matter (capa, sumário) e back
> matter (índice remissivo) do que vira chunk — coerente com o modelo de responsabilidade já usado
> para `bookId` no ADR-0008 (é o operador que ingere quem conhece a estrutura do livro).
> Implementado em `IngestArgsParser`/`IngestionService`.

## Contexto
Decorrente do [ADR-0001](0001-arquitetura-geral-backend-completo.md): quem alimenta os livros é o
desenvolvedor, não o usuário final do app. Não existe requisito de importar PDF pelo celular.

## Decisão
- A ingestão roda como uma **ferramenta de linha de comando (CLI)**, dentro do mesmo repositório/
  processo de build do backend (módulo `ingestion`, reaproveitando as classes de acesso ao vector
  DB), mas **não é exposta como endpoint HTTP público**. O desenvolvedor roda o comando localmente
  ou via um job manual (ex.: `./gradlew :ingestion:run --args="livro.pdf"`) apontando para a mesma
  base usada pelo backend de perguntas.
- Extração de texto com **Apache PDFBox** (JVM puro — não a variante `-android`, já que não roda
  mais no device).
- Pipeline mantém as regras já definidas no planejamento: detectar PDF escaneado sem camada de
  texto e avisar; limpar hifenização/headers/footers; chunking 300–800 tokens com overlap de
  10–20% respeitando parágrafos; metadados por chunk (`bookId`, `page`, `chapter`, `charOffset`);
  hash SHA-256 do arquivo para idempotência/reindexação.
- Progresso e logs ficam no console/log do backend — não há notificação de progresso no app,
  pois quem ingere não é quem usa o app.

## Alternativa considerada e rejeitada (por ora)
Endpoint administrativo protegido (`POST /admin/books`) para upload de PDF a partir de qualquer
máquina, sem precisar rodar a CLI no mesmo host do backend. Rejeitado por ora: exige autenticação/
autorização extra e superfície de ataque maior, sem necessidade real hoje (desenvolvedor tem acesso
direto ao host). Pode ser revisitado se a ingestão precisar ser feita de uma máquina sem acesso
direto ao servidor.

## Consequências
- App Android não depende de SAF, PdfBox-android nem WorkManager para ingestão — remove essa
  necessidade da Fase 6 (telas) e da Fase 3 (que deixa de existir como feature do app).
- A CLI e o backend de perguntas devem compartilhar o mesmo modelo/versão de embedding (ver
  [ADR-0003](0003-vector-db-e-embeddings.md)) para garantir que a busca vetorial funcione
  corretamente sobre o que foi indexado.
