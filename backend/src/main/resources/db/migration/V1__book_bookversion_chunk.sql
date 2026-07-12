-- Schema inicial da feature de ingestão de livros em PDF (specs/ingestao-pdf/plan.md).
-- ADR-0003: pgvector como vector DB; embeddings via Voyage AI (modelo voyage-3, dimensão de
-- saída fixa em 1024 — confirmado em https://docs.voyageai.com/docs/embeddings).
-- ADR-0008: identidade de livro por bookId (slug), versionamento com swap atômico.

CREATE EXTENSION IF NOT EXISTS vector;

-- book.active_version_id referencia book_version.id, mas book_version.book_id referencia
-- book.id (referência circular) — cria-se book primeiro sem a FK, depois book_version, e só
-- então a FK de book.active_version_id é adicionada via ALTER TABLE.
CREATE TABLE book (
    id                 VARCHAR(255) PRIMARY KEY,
    title              VARCHAR(1000) NOT NULL,
    active_version_id  UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE book_version (
    id                       UUID PRIMARY KEY,
    book_id                  VARCHAR(255) NOT NULL REFERENCES book (id),
    file_hash                VARCHAR(64) NOT NULL,
    embedding_model          VARCHAR(255) NOT NULL,
    embedding_model_version  VARCHAR(255) NOT NULL,
    status                   VARCHAR(20) NOT NULL
        CHECK (status IN ('INGESTING', 'READY', 'FAILED')),
    page_count               INTEGER,
    chunk_count              INTEGER,
    started_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at             TIMESTAMPTZ
);

-- Suporte à chave de gatilho do ADR-0008 (skip/reindex-necessário).
CREATE INDEX idx_book_version_trigger_key
    ON book_version (book_id, file_hash, embedding_model, embedding_model_version);

ALTER TABLE book
    ADD CONSTRAINT fk_book_active_version
        FOREIGN KEY (active_version_id) REFERENCES book_version (id);

CREATE TABLE chunk (
    id              UUID PRIMARY KEY,
    book_version_id UUID NOT NULL REFERENCES book_version (id) ON DELETE CASCADE,
    page            INTEGER NOT NULL,
    chapter         VARCHAR(1000),
    char_offset     INTEGER NOT NULL,
    token_count     INTEGER NOT NULL,
    text            TEXT NOT NULL,
    embedding       vector(1024) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_chunk_book_version_id ON chunk (book_version_id);

-- ADR-0003: busca por cosine similarity via HNSW.
CREATE INDEX idx_chunk_embedding_hnsw ON chunk USING hnsw (embedding vector_cosine_ops);
