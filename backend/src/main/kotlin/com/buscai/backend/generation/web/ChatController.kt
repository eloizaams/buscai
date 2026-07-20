package com.buscai.backend.generation.web

import com.buscai.backend.generation.GenerationService
import com.buscai.backend.retrieval.RetrievalScope
import jakarta.annotation.PreDestroy
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val DEVICE_ID_HEADER = "X-Device-Id"

// Timeout do SseEmitter, explícito (não o default do Spring): generoso o bastante para cobrir tanto
// um cold start do backend no free tier (ADR-0006 — Render/Fly podem levar dezenas de segundos para
// acordar um container hibernado) quanto a latência de uma resposta longa da Claude em streaming.
// 120s é um valor de partida (a calibrar contra latência observada em produção, mesma ressalva de
// outras constantes "a calibrar" do projeto), bem acima do que uma requisição bem-sucedida deveria
// levar em condições normais.
private const val SSE_TIMEOUT_MILLIS = 120_000L

// Tamanho do pool dedicado ao trabalho bloqueante de /chat (resolver conversa, rewrite, retrieval,
// geração via Claude em streaming) — deliberadamente pequeno e fixo (core == max, sem crescimento
// dinâmico): poucas conversas simultâneas são esperadas neste estágio do produto (free tier,
// ADR-0006), e um pool pequeno evita competir por CPU/memória com o resto do processo. Valor de
// partida, a calibrar contra tráfego real.
private const val CHAT_WORKER_POOL_SIZE = 4

// Fila pequena e deliberadamente limitada (bounded): além das CHAT_WORKER_POOL_SIZE tarefas já em
// execução, no máximo esta quantidade de pedidos aguarda vaga — acima disso,
// [RejectWithoutBlockingTomcatPolicy] entra em ação em vez de enfileirar sem limite (esgotaria
// memória) ou bloquear a thread do Tomcat que fez a requisição (o que `CallerRunsPolicy` faria).
private const val CHAT_WORKER_QUEUE_CAPACITY = 8

private const val GENERIC_ERROR_MESSAGE = "Ocorreu um erro ao gerar a resposta. Tente novamente em instantes."

// Content-Type da resposta SSE, com charset UTF-8 explícito. Bug de produção descoberto na T8
// (teste de aceite E2E): SseEmitter.extendResponse só define `Content-Type: text/event-stream` (sem
// charset) quando o header ainda está nulo no momento em que o Spring monta a resposta — sem um
// Content-Type já setado aqui, o StringHttpMessageConverter cai no charset default (ISO-8859-1),
// corrompendo qualquer acento em português na resposta ("página" -> "p?gina"). Setar o Content-Type
// já com charset=UTF-8 diretamente no HttpServletResponse, antes de criar/devolver o SseEmitter,
// faz SseEmitter.extendResponse respeitá-lo (só sobrescreve se o Content-Type ainda for nulo) — mesmo
// padrão de declarar UTF-8 explicitamente já usado em SecurityFilterSupport.writeSecurityError
// (config/), adaptado aqui ao SseEmitter.
private const val SSE_CONTENT_TYPE = "text/event-stream;charset=UTF-8"

/**
 * Política de rejeição do executor dedicado de `/chat`: nunca [ThreadPoolExecutor.CallerRunsPolicy],
 * que executaria o trabalho bloqueante (chamada a `GenerationService`/Claude) na própria thread do
 * Tomcat que submeteu a tarefa — o oposto do que se quer ao delegar para um `Executor` dedicado.
 * Em vez disso, relança [RejectedExecutionException] para a thread chamadora ([ChatController]),
 * que a converte num HTTP `503` (fila cheia = backend sobrecarregado agora, não uma falha do pedido
 * em si) sem nunca chegar a criar/emitir nada pelo [SseEmitter].
 */
private object RejectWithoutBlockingTomcatPolicy : RejectedExecutionHandler {
    override fun rejectedExecution(
        r: Runnable,
        executor: ThreadPoolExecutor,
    ): Unit =
        throw RejectedExecutionException(
            "fila do executor de /chat cheia (${executor.queue.size} tarefas) — rejeitando para não bloquear a thread do Tomcat",
        )
}

private fun chatWorkerThreadFactory(): ThreadFactory {
    val counter = AtomicInteger(0)
    return ThreadFactory { runnable ->
        // daemon=true: threads deste pool nunca devem impedir o processo de encerrar (ex. shutdown
        // do servidor, fim de um teste que sobe o contexto Spring).
        Thread(runnable, "chat-worker-${counter.incrementAndGet()}").apply { isDaemon = true }
    }
}

private fun SseEmitter.sendChatEvent(event: ChatEvent) {
    val builder =
        when (event) {
            is ChatEvent.Conversation -> SseEmitter.event().name("conversation").data(event.id.toString())
            is ChatEvent.Token -> SseEmitter.event().name("token").data(event.text)
            ChatEvent.Done -> SseEmitter.event().name("done").data("done")
            is ChatEvent.Error -> SseEmitter.event().name("error").data(event.message)
        }
    send(builder)
}

/**
 * `POST /chat` (`specs/geracao/plan.md`, "Como roda"): único endpoint que expõe
 * [GenerationService] via HTTP, em streaming SSE ([ChatEvent]). Passa pelos filtros de T3
 * ([com.buscai.backend.config.ApiKeyFilter]/[com.buscai.backend.config.RateLimitFilter]) como
 * qualquer outra rota — nenhuma exceção registrada em
 * [com.buscai.backend.config.ApiSecurityPaths].
 *
 * **Ordem `event: conversation` antes de `event: token` (CA11):** resolvida via o parâmetro
 * `onConversationResolved` de [GenerationService.answer] — chamado assim que a conversa é
 * resolvida/criada, antes de qualquer rewrite/retrieval/geração — em vez deste controller chamar
 * [com.buscai.backend.generation.conversation.ConversationStore] por conta própria (o que
 * duplicaria a leitura/criação da conversa e romperia `GenerationService` como única porta de
 * entrada da lógica, `specs/geracao/plan.md`, "Contratos entre camadas").
 */
@RestController
class ChatController(
    private val generationService: GenerationService,
) {
    private val logger = LoggerFactory.getLogger(ChatController::class.java)

    private val chatWorkerExecutor =
        ThreadPoolExecutor(
            CHAT_WORKER_POOL_SIZE,
            CHAT_WORKER_POOL_SIZE,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(CHAT_WORKER_QUEUE_CAPACITY),
            chatWorkerThreadFactory(),
            RejectWithoutBlockingTomcatPolicy,
        )

    @PreDestroy
    fun shutdownChatWorkerExecutor() {
        chatWorkerExecutor.shutdown()
    }

    @PostMapping("/chat")
    fun chat(
        @RequestHeader(DEVICE_ID_HEADER) deviceIdHeader: String?,
        @RequestBody request: ChatRequest,
        httpServletResponse: HttpServletResponse,
    ): SseEmitter {
        val deviceId =
            deviceIdHeader?.takeUnless { it.isBlank() }
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "cabeçalho $DEVICE_ID_HEADER ausente")

        // Setado antes de criar/devolver o SseEmitter (ver KDoc de SSE_CONTENT_TYPE) — precisa
        // acontecer aqui, não dentro de runChat (thread de worker), porque SseEmitter.extendResponse
        // só respeita um Content-Type já presente no momento em que a resposta assíncrona é montada,
        // logo após este método retornar.
        httpServletResponse.contentType = SSE_CONTENT_TYPE

        val emitter = SseEmitter(SSE_TIMEOUT_MILLIS)

        try {
            chatWorkerExecutor.execute { runChat(emitter, deviceId, request) }
        } catch (rejected: RejectedExecutionException) {
            logger.warn("Executor de /chat saturado, rejeitando requisição de device {}", deviceId, rejected)
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "servidor ocupado, tente novamente em instantes")
        }

        return emitter
    }

    /**
     * Roda inteiramente na thread de worker (`chatWorkerExecutor`), fora da sessão JPA do request
     * (`specs/geracao/plan.md`, "Execução assíncrona, transação e persistência"). Guard único
     * (CLAUDE.md, seção "método que orquestra vários passos sequenciais"): resolver a conversa
     * (dentro de `GenerationService.answer`), emitir `event: conversation`, e o restante do
     * pipeline são passos sequenciais que precisam da mesma reação em caso de falha (evento `error`
     * genérico + completar o emitter, CA9/CA10) — por isso um único `try/catch` envolve o corpo
     * inteiro, não um por passo.
     */
    private fun runChat(
        emitter: SseEmitter,
        deviceId: String,
        request: ChatRequest,
    ) {
        try {
            val scope = request.bookIds?.let { RetrievalScope.Books(it) } ?: RetrievalScope.AllBooks

            generationService.answer(
                deviceId = deviceId,
                conversationId = request.conversationId,
                query = request.query,
                scope = scope,
                onConversationResolved = { conversationId, isNew ->
                    if (isNew) {
                        emitter.sendChatEvent(ChatEvent.Conversation(conversationId))
                    }
                },
                onToken = { token -> emitter.sendChatEvent(ChatEvent.Token(token)) },
            )

            emitter.sendChatEvent(ChatEvent.Done)
            emitter.complete()
        } catch (ex: Exception) {
            logger.warn("Falha ao processar /chat para device {}", deviceId, ex)
            runCatching { emitter.sendChatEvent(ChatEvent.Error(GENERIC_ERROR_MESSAGE)) }
            emitter.complete()
        }
    }
}
