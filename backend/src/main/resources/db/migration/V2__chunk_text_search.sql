-- Feature de retrieval (specs/retrieval/plan.md, seção "Schema (Flyway V2)"): coluna de full-text
-- search gerada a partir de chunk.text, usada pelo ramo léxico da busca híbrida (ADR-0003).
-- Config de idioma fixada em 'portuguese' (acervo majoritariamente PT-BR) — não mapeada em
-- Chunk.kt (catalog), só é lida via SQL nativo em HybridSearchDao (T3).

ALTER TABLE chunk
    ADD COLUMN text_search tsvector
        GENERATED ALWAYS AS (to_tsvector('portuguese', text)) STORED;

CREATE INDEX idx_chunk_text_search_gin ON chunk USING gin (text_search);
