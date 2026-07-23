package com.buscai.backend.retrieval

import org.springframework.stereotype.Component

/**
 * Detecta números de item numerado mencionados numa pergunta em pt-BR (RF1,
 * `specs/busca-exata-item/spec.md`). Componente puro — sem I/O, sem acesso a repositório/banco —
 * chamado pelo `RetrievalService` para alimentar a CTE de busca exata (`exact_rank`) do
 * `HybridSearchDao`.
 *
 * ## Regra de detecção
 *
 * Um número só é tratado como lookup de item quando é **acompanhado de um marcador explícito**:
 * `pergunta`/`perguntas`, `questão`/`questões`/`questao`/`questoes`, `item`/`itens`,
 * `número`/`números`/`numero`/`numeros`. Um número solto — ano ("1857"), contagem ("os 10
 * mandamentos") — nunca dispara lookup, mesmo que a frase tenha outras palavras próximas; a Fase 2
 * (`specs/busca-exata-item/plan.md`) exclui deliberadamente marcadores de outras obras/estruturas
 * (`salmo`, `capítulo`) do léxico desta feature.
 *
 * A abreviação de "número" (`nº`, `n°`, `n.`, `n.º`, ...) **não** é, sozinha, um marcador de
 * primeiro nível — "veja a nota n. 3", "lei n. 8.078" não disparam lookup, porque `n.`/`nº` é
 * abreviação genérica em pt-BR para qualquer coisa numerada (nota, figura, lei, artigo), não
 * exclusiva de item de livro numerado (achado do `code-reviewer`, T3). Ela só conta como
 * continuação de um marcador pleno já casado — "pergunta nº 25", "questão n. 700".
 *
 * [MARKER_REGEX] casa: um marcador pleno, opcionalmente seguido de outro marcador encadeado (ex.:
 * "questão n. 700" = `questão` + `n.` antes do número; "pergunta nº 25" = `pergunta` + `nº`),
 * separados só por espaço/ponto/"de", então **só o número imediatamente seguinte** — sem nenhuma
 * outra palavra no meio (a frase "pergunta que fala sobre 700" não casa: "700" não está
 * *acompanhado* do marcador, está a uma sentença de distância). Cada número precisa do próprio
 * marcador adjacente; **não** há suporte a lista/enumeração ("perguntas 25 e 26" detecta só `25`
 * — o `26` fica sem marcador próprio) nem a faixa ("perguntas 155 a 158" detecta só `155`) — ambos
 * são sintaxe de intervalo/enumeração na *pergunta*, deliberadamente YAGNI (`spec.md`, "Fora de
 * escopo": "cada número marcado resolve individualmente"). Uma primeira versão desta classe tentou
 * suportar "N e M" como enumeração, mas isso produzia falso positivo real em frases como "tenho
 * pergunta 1 e 2 gatos em casa" (→ `[1, 2]`, errado: "2 gatos" não é item) — removido (achado do
 * `code-reviewer`). O restante da pergunta continua coberto pela busca híbrida normal (RF5,
 * degradação segura, não alucina).
 *
 * ## Truncamento
 *
 * Se a pergunta mencionar mais números-com-marcador do que
 * [RetrievalProperties.maxExactItemNumbers], a lista é truncada mantendo os **primeiros N na
 * ordem de aparição no texto** (decisão desta task, não especificada no spec/plan) — mais simples
 * e previsível do ponto de vista do usuário do que qualquer outro critério de prioridade, e
 * simétrico ao guard já existente no `plan.md` para o afogamento do contexto semântico em
 * pergunta mista (risco R3).
 */
@Component
class ItemLookupDetector(
    private val properties: RetrievalProperties,
) {
    fun detect(question: String): List<Int> =
        MARKER_REGEX
            .findAll(question)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .take(properties.maxExactItemNumbers)
            .toList()

    companion object {
        // Marcador pleno de item — só estes abrem um match (singular/plural).
        private const val FULL_MARKER =
            """pergunta|perguntas|quest[aã]o|quest[oõ]es|item|itens|n[uú]mero|n[uú]meros"""

        // Abreviação de "número" (nº, n°, n., n.º — sempre exigindo pelo menos um "." ou "º/°"
        // depois do "n", pra não casar a letra solta "n"). Só vale como continuação encadeada de
        // um FULL_MARKER já casado, nunca sozinha (achado do code-reviewer, T3).
        private const val ABBREV_MARKER =
            """n(?:\.[º°]?|[º°]\.?)"""

        // Marcador pleno, opcionalmente encadeado com outro marcador (pleno ou abreviação; ex.:
        // "questão n. 700"), seguido (só espaço/ponto/"de" no meio, nunca outra palavra) do
        // número capturado no grupo 1 — só o número imediatamente adjacente, sem enumeração/faixa
        // (ver KDoc da classe).
        private val MARKER_REGEX =
            Regex(
                """\b(?:$FULL_MARKER)(?:[\s.]+(?:de\s+)?(?:$FULL_MARKER|$ABBREV_MARKER))*[\s.]*(\d+)""",
                RegexOption.IGNORE_CASE,
            )
    }
}
