# Plan — Cliente web fino (Fase 6)

Referências: `specs/cliente-web/spec.md`, [ADR-0011](../../docs/adr/0011-cliente-web-fino-primeiro-android-adiado.md),
[ADR-0012](../../docs/adr/0012-cliente-web-seguranca-hospedagem-transporte.md), parecer do
`android-architect` (2026-07-20, ver notas datadas da spec).

## Decisões já tomadas (não reabertas aqui)

- Stack: HTML/CSS/JS puro, sem framework/build step (ADR-0011).
- Chave de acesso: digitada pelo usuário, guardada em `localStorage`, nunca commitada (ADR-0012).
- Hospedagem: same-origin, servida pelo próprio backend Spring Boot (ADR-0012).
- Transporte do streaming: `fetch` + `ReadableStream` (parsing manual de `text/event-stream`),
  não `EventSource` (ADR-0012, `EventSource` não permite headers customizados).

## Módulo e localização

```
web/                    — novo, paralelo a android/ e backend/ (ADR-0011)
  index.html             — shell da página, único ponto de entrada
  app.js                 — toda a lógica (estado, fetch, render) — um único módulo, sem bundler
  styles.css             — estilos

backend/
  build.gradle.kts                                                — nova task Gradle (copia web/ → build de recursos estáticos)
  src/main/kotlin/com/buscai/backend/catalog/
    BookService.kt                                                — NOVO — camada de serviço do catálogo (não existia)
    BookRepository.kt, BookVersionRepository.kt                    — inalterados, consumidos pelo BookService novo
  src/main/kotlin/com/buscai/backend/catalog/web/
    BookController.kt                                              — NOVO — GET /books
    BookResponse.kt                                                — NOVO — DTO de resposta
  src/main/kotlin/com/buscai/backend/config/
    ApiSecurityPaths.kt                                            — editado — isenta os arquivos estáticos do web/
```

### Por que um `BookService` novo (não reaproveitar lógica do `RetrievalService`)

`RetrievalService.resolveEligibleVersions` (retrieval/RetrievalService.kt:117-141) já filtra livros
por versão ativa `READY`, mas está acoplado à lógica de retrieval (inclui checagem de compatibilidade
de embedding model, que não é relevante para "listar livros para o usuário escolher escopo").
Constitution.md §2 exige controllers finos e acesso a repositório só pela camada de serviço — não
existe hoje nenhuma camada de serviço do catálogo (`BookRepository`/`BookVersionRepository` só são
consumidos direto por `IngestionService` e `RetrievalService`, cada um com sua própria lógica). Um
`BookService` pequeno e dedicado, com uma responsabilidade (listar livros disponíveis para uso),
evita acoplar `BookController` a `RetrievalService` só para reusar um filtro parecido mas não
idêntico — DRY não se aplica aqui porque as duas filtragens têm propósitos diferentes (retrieval
elegível vs. catálogo visível ao usuário), mesmo padrão de raciocínio que já levou o projeto a ter
`ConversationStore` separado de `GenerationService`.

## Contratos entre camadas

### `GET /books`

Atrás de `ApiKeyFilter`/`RateLimitFilter` como qualquer outro endpoint (nenhuma exceção em
`ApiSecurityPaths` para a rota de API em si — só os arquivos estáticos do `web/` são isentos, ver
seção "Arquivos estáticos" abaixo). Sem header `X-Device-Id` (não é um recurso por device, é o
catálogo inteiro).

```kotlin
// catalog/web/BookResponse.kt
data class BookResponse(
    val id: String,
    val title: String,
)

// catalog/web/BookController.kt
@RestController
class BookController(private val bookService: BookService) {
    @GetMapping("/books")
    fun listBooks(): List<BookResponse> =
        bookService.listAvailable().map { BookResponse(id = it.id, title = it.title) }
}

// catalog/BookService.kt
@Service
class BookService(
    private val bookRepository: BookRepository,
    private val bookVersionRepository: BookVersionRepository,
) {
    /** Livros cuja versão ativa terminou de indexar (BookVersionStatus.READY). Ordenado por título. */
    fun listAvailable(): List<Book> {
        val activeVersionIdToBook =
            bookRepository.findAll()
                .mapNotNull { book -> book.activeVersionId?.let { it to book } }
                .toMap()
        val readyVersionIds =
            bookVersionRepository.findAllById(activeVersionIdToBook.keys)
                .filter { it.status == BookVersionStatus.READY }
                .map { it.id }
                .toSet()
        return activeVersionIdToBook
            .filterKeys { it in readyVersionIds }
            .values
            .sortedBy { it.title }
    }
}
```

Sem paginação/filtro — acervo pequeno (mesma premissa já aceita em outras partes do backend).

### `POST /chat` e `GET /conversations(/{id})`

Sem mudança de contrato — o `web/` consome exatamente o que a Fase 5 já expõe (ver `spec.md`,
seção "O que o usuário consegue fazer", para o mapeamento evento SSE → UI).

## Arquivos estáticos: bundling e isenção de segurança

**Bundling:** nova task Gradle em `backend/build.gradle.kts` que copia `../web/` para
`build/resources/main/static/` antes de `processResources`, para que o Spring Boot Actuator/MVC
sirva os arquivos via seu resource handler default de `classpath:/static/**` — sem
`WebMvcConfigurer`/`addResourceHandlers` customizado, sem duplicar `web/` dentro de
`backend/src/`. `web/` continua sendo a única fonte da verdade dos arquivos, versionada uma vez só.

```kotlin
// backend/build.gradle.kts (adição)
tasks.named("processResources") {
    dependsOn("copyWebStatic")
}
tasks.register<Copy>("copyWebStatic") {
    from(rootDir.resolve("../web"))
    into(layout.buildDirectory.dir("resources/main/static"))
}
```

**Isenção de segurança:** `ApiSecurityPaths` ganha uma lista explícita dos caminhos estáticos
servidos (mesmo padrão hoje usado só para `/actuator/health` — allowlist explícita, não glob
"tudo que não é API conhecida", para não isentar por acidente uma rota de API futura):

```kotlin
private val EXEMPT_PATHS = setOf("/actuator/health", "/", "/index.html", "/app.js", "/styles.css")
```

Servir HTML/CSS/JS não gasta crédito em API paga (constitution §1 exige validar `X-Api-Key` antes
de "qualquer chamada às APIs de IA" — servir um arquivo estático não é isso); as chamadas que o JS
carregado faz depois (`/chat`, `/conversations`, `/books`) continuam 100% protegidas, sem exceção.

## Modelos de dado (cliente, `localStorage`)

| Chave              | Conteúdo                                                    |
|--------------------|--------------------------------------------------------------|
| `buscai_api_key`   | chave `X-Api-Key` informada pelo usuário (string)             |
| `buscai_device_id` | UUID gerado no primeiro carregamento (`crypto.randomUUID()`), reenviado como `X-Device-Id` em toda chamada |

Estado em memória (`app.js`, sem framework — um objeto de estado simples + funções de render):
`{ apiKey, deviceId, books: BookResponse[], selectedBookIds: Set<string> | null, conversations: ConversationSummaryResponse[], currentConversationId: UUID | null, messages: {role, content}[], streaming: boolean }`.

## Como roda (fluxo do usuário)

1. Carrega `/` → `index.html`/`app.js`/`styles.css` servidos same-origin, sem exigir `X-Api-Key`
   (isentos, ver acima).
2. `app.js` lê `localStorage`; sem `buscai_api_key`, mostra o gate pedindo a chave antes de
   qualquer outra coisa (CA6).
3. Com chave presente, dispara `GET /books` (popula seletor de escopo) e `GET /conversations`
   (popula lista lateral) em paralelo. `401`/`403` em qualquer uma delas volta ao gate (CA7).
4. Usuário escreve pergunta, opcionalmente escolhe livros no seletor, envia.
5. `app.js` monta `ChatRequest` (`conversationId` atual ou `null`, `query`, `bookIds` do seletor
   ou `null` para "todos") e chama `fetch('/chat', { method: 'POST', headers: {X-Api-Key, X-Device-Id, Content-Type: application/json}, body })`.
6. Lê `response.body.getReader()`, faz parsing manual de `event:`/`data:` linha a linha,
   despachando para os mesmos 4 casos de `ChatEvent` (`conversation`, `token`, `done`, `error`):
   - `conversation` → seta `currentConversationId`, re-busca `GET /conversations` (nova entra na
     lista lateral).
   - `token` → concatena no balão de resposta em construção (CA1).
   - `done` → encerra o estado de streaming.
   - `error` → mostra a mensagem genérica recebida, encerra o estado de streaming (CA9).
7. Se a requisição não retornar nada por um tempo (cold start, ADR-0006), a UI já está em estado
   "aguardando resposta" desde o passo 5 — sem timeout próprio no cliente, o timeout de 120s do
   próprio `SseEmitter` do backend é quem eventualmente fecha a conexão (CA8).

## Fora do plano desta feature

- Testes automatizados de UI (Playwright/Cypress) — fora de escopo por não haver framework de
  build; verificação manual via `/run` antes de considerar cada task concluída, como já é
  convenção do projeto para UI.
- Qualquer mudança em `ApiKeyFilter`/`RateLimitFilter` além da allowlist de `ApiSecurityPaths` —
  a lógica dos filtros em si não muda.
- CORS — não se aplica (same-origin, ADR-0012).
- Paginação/busca em `GET /books` ou `GET /conversations` — acervo e histórico pequenos nesta fase.
