package com.buscai.backend.embedding

import com.buscai.backend.catalog.EMBEDDING_DIMENSIONS

/**
 * Cliente de embeddings compartilhado entre `ingestion` e `retrieval` (ADR-0010, movido de
 * `ingestion.embedding`): converte um lote de textos nos vetores correspondentes, na mesma ordem
 * da entrada, numa única chamada — a Voyage AI cobra por requisição+tokens, não por chunk
 * individual, então enviar em lote reduz custo além de I/O (ver
 * `specs/ingestao-pdf/plan.md`, seção "Processamento incremental").
 *
 * Implementação HTTP concreta: [VoyageEmbeddingClient]. A interface fica isolada para permitir um
 * fake determinístico nos testes de `IngestionService`/`RetrievalService`, sem chamar a Voyage
 * real.
 */
interface EmbeddingClient {
    /**
     * Gera um vetor de [EMBEDDING_DIMENSIONS] posições para cada texto em [texts], na mesma ordem
     * da entrada. Lista vazia devolve lista vazia sem chamar a API. [inputType] seleciona o
     * parâmetro assimétrico `input_type` da Voyage (ADR-0010) — `DOCUMENT` para conteúdo indexado
     * (ingestão), `QUERY` para a pergunta de busca (retrieval); usar o tipo errado degrada recall
     * silenciosamente, sem lançar erro.
     *
     * @throws EmbeddingClientException se a chamada falhar — timeout de rede, erro de conexão, ou
     *   resposta de erro/inesperada da API (CA7, `specs/ingestao-pdf/spec.md`); nunca deixa vazar
     *   uma exceção genérica sem contexto suficiente para o operador da CLI entender o que
     *   aconteceu.
     */
    fun embed(
        texts: List<String>,
        inputType: EmbeddingInputType,
    ): List<FloatArray>
}

/**
 * Erro ao chamar o provedor de embeddings (ADR-0003) — timeout de rede, erro de conexão, resposta
 * de erro da API (status HTTP + corpo, quando disponível), ou resposta com formato inesperado
 * (contagem/dimensão de vetores divergente do esperado). A mensagem sempre inclui contexto
 * suficiente para o operador da CLI de ingestão entender a causa (CA7); nunca inclui a API key
 * (CLAUDE.md).
 */
class EmbeddingClientException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
