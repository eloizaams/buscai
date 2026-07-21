-- Feature de referência estruturada (specs/referencia-estruturada/plan.md, seção "Schema"; ADR-0013):
-- substitui chunk.chapter (nunca preenchido em produção, ver ADR-0013 seção "Contexto") por um par
-- genérico reference/reference_type, reaproveitado tanto para capítulo (prosa) quanto para item
-- numerado (catecismo). book_version.reference_type registra o estilo declarado na ingestão
-- (--reference-style), usado por ChunkValidator para saber qual invariante estrutural aplicar.

ALTER TABLE chunk
    ADD COLUMN reference VARCHAR(1000),
    ADD COLUMN reference_type VARCHAR(20)
        CHECK (reference_type IN ('CHAPTER', 'NUMBERED_ITEM'));

ALTER TABLE book_version
    ADD COLUMN reference_type VARCHAR(20)
        CHECK (reference_type IN ('CHAPTER', 'NUMBERED_ITEM'));

-- Backfill idempotente e seguro: chapter nunca foi preenchido em produção (Chunker sempre gravou
-- null — ver ADR-0013, Contexto), mas o backfill é feito de qualquer forma por correção, não por
-- necessidade prática observada.
UPDATE chunk SET reference = chapter, reference_type = 'CHAPTER' WHERE chapter IS NOT NULL;

ALTER TABLE chunk DROP COLUMN chapter;
