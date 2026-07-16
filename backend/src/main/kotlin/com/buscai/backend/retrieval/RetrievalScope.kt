package com.buscai.backend.retrieval

/**
 * Escopo de uma busca (`RetrievalService.search`, `specs/retrieval/spec.md`, CA2): todo o acervo
 * pronto (`READY`) ou um subconjunto de livros. Entrada da resolução de versões elegíveis descrita
 * em `plan.md`, seção "Contratos entre camadas".
 */
sealed class RetrievalScope {
    /**
     * Busca sobre `activeVersionId` de todo `Book` com versão ativa `READY` e cujo
     * `embeddingModel`/`embeddingModelVersion` batem com os configurados atualmente
     * (`VoyageProperties`) — evita misturar espaços vetoriais incompatíveis (ver `plan.md`).
     */
    object AllBooks : RetrievalScope()

    /**
     * Busca restrita ao subconjunto [bookIds] — nunca retorna chunk de um livro fora do conjunto
     * (CA2). O caso de um único livro é só o subconjunto de tamanho 1, não um modo à parte (ver
     * `spec.md`) — antecipa a seleção de múltiplos livros pelo usuário final (Fase 6) sem precisar
     * redesenhar o contrato depois.
     *
     * [bookIds] nunca vazio: "nenhum filtro" se expressa com [AllBooks], nunca com um conjunto
     * vazio aqui — [require] falha cedo (erro de programação do chamador) em vez de deixar
     * `RetrievalService` interpretar silenciosamente um conjunto vazio como "sem resultado".
     *
     * Cada `bookId` inexistente, sem `activeVersionId`, ou cuja `BookVersion` ativa não estiver
     * `READY` simplesmente não contribui uma versão elegível (não é erro); se nenhum contribuir,
     * a busca não encontra nada (`RetrievalResult.NoRelevantContext`, ver CA7).
     */
    data class Books(
        val bookIds: Set<String>,
    ) : RetrievalScope() {
        init {
            require(bookIds.isNotEmpty()) {
                "RetrievalScope.Books requer ao menos um bookId — escopo sem filtro é AllBooks, " +
                    "nunca Books com conjunto vazio"
            }
        }
    }
}
