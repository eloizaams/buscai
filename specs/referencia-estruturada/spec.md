# Spec — Referência estruturada de chunk (capítulo/item) e lista de fontes no chat

## Contexto

Nasce de um teste real (2026-07-20): "O Livro dos Espíritos" (Allan Kardec) foi ingerido e
perguntas por número de item ("qual a pergunta 157?") falharam a encontrar conteúdo que existe no
acervo, e uma pergunta temática ("o que acontece no momento da morte?") trouxe trechos tangenciais
em vez do capítulo canônico sobre o assunto. A causa raiz identificada: o backend só sabe citar por
**página** (`Chunk.page`, ADR-0002), que não é uma referência estável entre edições, e não tem
nenhuma noção de **capítulo** ou **item numerado** — a estrutura real de muitos livros (prosa
capitulada, ou catecismo numerado, comum na literatura espírita).

O dono do produto decidiu o requisito (ADR-0013): a resposta do chat passa a citar por **capítulo**
ou **número do item/pergunta**, nunca por página, e a API passa a devolver, além do texto gerado,
uma **lista estruturada dos chunks recuperados** que fundamentaram a resposta — cada um com sua
referência e o próprio texto do trecho — entregue num evento SSE novo (`event: sources`).

Esta spec cobre as três pontas que o ADR-0013 decidiu: (1) a ingestão passa a capturar essa
referência por chunk, declarada pelo operador por livro; (2) o retrieval e a geração propagam essa
referência; (3) o contrato de `POST /chat` ganha o evento `event: sources`. Consome
`RetrievalService`/`GenerationService` como já existem (`specs/retrieval/`, `specs/geracao/`) —
estende os contratos delas, não os substitui.

Esta spec também revisa a constitution.md, seção 4 ("a resposta do chat sempre cita livro e
página") — passa a "livro e, quando houver, referência (capítulo/item), com página como metadado
interno não citado" (ADR-0013 é o ADR que registra essa revisão).

Esta spec **supersede** duas decisões já implementadas em `specs/geracao/plan.md`: o
`ANSWER_SYSTEM_PROMPT` atual instrui citar página (linha "conforme Dom Casmurro, p. 42") — passa a
instruir citar capítulo/item; e a decisão "sem tabela de citações estruturada... citação só inline,
não como metadado separado" (`specs/geracao/plan.md`, nota sobre o schema V3) é revertida pelo
`event: sources`. O `plan.md` desta feature deve reescrever o prompt e registrar essa reversão
explicitamente, não deixar as duas decisões como fontes de verdade conflitantes.

## Ator

- **O usuário final**, através do cliente web (`web/`, `specs/cliente-web/`) ou qualquer cliente que
  fale `POST /chat`: recebe a resposta com citação estável entre edições, e uma lista de fontes que
  pode inspecionar (trecho original + de onde veio).
- **O desenvolvedor/operador do backend**, que ao ingerir um livro (`IngestCommand`) declara qual é
  o estilo de referência daquele livro (capítulo ou item numerado) — informação que ele já tem, por
  conhecer a estrutura do livro que está ingerindo (mesmo espírito do `bookId` explícito, ADR-0008).

## O que o ator consegue fazer

1. **Fazer uma pergunta e receber, além da resposta em texto, a lista dos trechos que a
   fundamentaram** — cada um com o livro, a referência (capítulo ou número do item) e o texto
   completo do trecho — entregue como um evento distinto do streaming da resposta, não misturado no
   meio do texto gerado.
2. **Ler uma citação que não depende da edição do livro**: a resposta nunca menciona número de
   página; menciona capítulo (livros em prosa) ou o número do item/pergunta (livros de catecismo
   numerado, ex. *O Livro dos Espíritos*).
3. **Perguntar por um item numerado específico e recebê-lo corretamente**: uma pergunta que
   referencia um número de item existente no acervo (ex. "o que diz a pergunta 157?") recupera o
   item correto — a citação de um item numerado nunca é ambígua nem aproximada por um intervalo
   quando o item existe isolado num chunk.
4. **Continuar recebendo uma resposta útil para livros já ingeridos antes desta feature**: um chunk
   sem referência capturada (livro ingerido antes desta mudança, ou reindexado sem declarar o
   estilo) ainda aparece na lista de fontes, identificado só pelo livro — nunca omitido, nunca
   citado por página.
5. **(Operador) Declarar o estilo de referência do livro ao ingerir**: a CLI de ingestão aceita
   informar se o livro é estruturado por capítulos ou por itens numerados; sem essa informação, a
   ingestão continua funcionando normalmente, só sem referência estruturada (comportamento
   equivalente ao atual).

## Critérios de aceite (observáveis)

- **CA1**: a resposta a uma pergunta com contexto encontrado (`RetrievalResult.Found`) vem
  acompanhada de exatamente um evento `event: sources`, emitido uma única vez e antes do primeiro
  `event: token` da resposta, contendo a lista dos chunks usados — cada um com `chunkId`, `bookId`,
  `bookTitle`, `reference` (pode ser `null`), `referenceType` (pode ser `null`), e o texto do
  chunk.
- **CA2**: uma pergunta sem contexto relevante (`RetrievalResult.NoRelevantContext`) não produz
  `event: sources` (nada foi de fato usado para fundamentar a mensagem fixa de "não encontrei").
- **CA3**: nenhuma citação — nem no texto gerado pela IA, nem na lista de fontes — menciona número
  de página. O texto gerado cita livro + capítulo ou livro + número do item, conforme o
  `referenceType` de cada chunk usado; um chunk sem referência é citado só pelo livro.
- **CA4**: um livro ingerido com `--reference-style=numbered-item` faz o item numerado ser a
  unidade atômica do chunking (amenda ao piso de 300 tokens do ADR-0008 — chunks desse estilo podem
  ficar abaixo do piso, por design): nenhum chunk parte um item ao meio, e um item que cabe sozinho
  num chunk é citado por esse número exato, nunca um intervalo.
- **CA5**: quando um chunk cuja `reference` cobre um número de item pedido pelo usuário (ex.
  "pergunta 157") **é de fato recuperado** pelo retrieval, a citação resultante aponta para esse
  número corretamente. Esta feature garante a citação correta uma vez que o chunk é recuperado —
  **não** garante que toda busca por número de item sempre recupera o chunk certo: resolver isso de
  forma exata na busca (predicado estruturado sobre `reference`, em vez de depender de
  embedding/full-text) é follow-up registrado no ADR-0013, fora do escopo desta feature (ver "Fora
  de escopo").
- **CA6**: um chunk de um livro ingerido antes desta feature (ou sem `--reference-style` informado)
  aparece no `event: sources` com `reference: null` e `referenceType: null`, nunca omitido da lista
  nem citado por página.
- **CA7**: a ingestão sem `--reference-style` continua funcionando exatamente como hoje (nenhuma
  regressão no pipeline de ingestão para livros que não usam essa flag).
- **CA8**: o `rag-evaluator` rodado contra `specs/eval/golden-set.json` (com casos novos cobrindo um
  livro de item numerado) não mostra regressão de recall/groundedness em relação à linha de base
  anterior a esta feature — este é o gate tanto para o novo prompt de citação (CA3) quanto para o
  piso de chunk condicional ao `reference_style` (CA4, amenda ao ADR-0008).

## Dependências com o `cliente-web` já publicado

O cliente web (`specs/cliente-web/`, Fase 6, PR #11 já mergeado em `main`) já consome `POST /chat`
em produção. Esta feature precisa ser compatível com ele sem quebrar o que já está no ar:

- A premissa de `specs/cliente-web/spec.md` ("o backend não expõe metadado estruturado de
  citação... a UI renderiza texto, não constrói a partir de um campo à parte") fica **superada** por
  esta feature — registrar isso na spec do cliente-web quando o painel de fontes for implementado
  ali (spec/tasks próprios, fora de escopo aqui).
- O parser SSE hoje em produção (`web/app.js`) só trata `conversation`/`token`/`done`/`error`. O
  `event: sources` novo precisa ser **seguro de ignorar** por um cliente que não o reconhece — esta
  feature deve confirmar (teste de integração ou verificação manual) que o parser atual do
  `web/app.js` não quebra, não trava e não trata `data:` de `sources` como delta de texto ao
  receber um evento SSE desconhecido, antes do merge.
- A citação inline no texto gerado muda de rótulo (página → capítulo/item) — é mudança de
  comportamento visível no cliente já publicado, mesmo antes de qualquer UI nova existir ali.

## Fora de escopo (explicitamente)

- Busca estruturada exata por número de item (ex. resolver "pergunta 157" no *retrieval* consultando
  `reference` diretamente, em vez de depender de embedding/full-text) — o ADR-0013 registra isso como
  follow-up habilitado por esta feature (a coluna `reference` passa a existir), mas não decidido
  aqui. CA5 desta spec é satisfeito pelo chunk cobrir o item corretamente, não por uma busca exata
  garantida em qualquer cenário.
- Truncamento/paginação do texto do chunk no `event: sources` — o texto vai completo (ADR-0013); um
  teto de tamanho fica para revisão futura se o payload se mostrar um problema real.
- UI do painel de fontes no cliente web (`web/`) — spec/tasks do `specs/cliente-web/`, consome o
  contrato desta feature.
- Detecção automática (sem flag) do estilo de referência — ADR-0013 rejeitou por não-determinismo;
  o operador sempre declara explicitamente.
- Re-ingestão em massa do acervo já existente para popular `reference` — fica a critério do
  operador rodar `--reindex` livro a livro (ADR-0008); esta feature não dispara isso automaticamente.
