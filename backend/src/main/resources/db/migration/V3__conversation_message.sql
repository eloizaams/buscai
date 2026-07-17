-- Feature de geração de resposta (specs/geracao/plan.md, seção "Schema (Flyway V3)"): estado de
-- conversa (ADR-0007, ADR-0009 — não faz parte do acervo em catalog/).

CREATE TABLE conversation (
    id          UUID PRIMARY KEY,
    device_id   VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_conversation_device_id ON conversation (device_id);

CREATE TABLE message (
    id              UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversation (id),
    role            VARCHAR(20) NOT NULL,  -- USER / ASSISTANT
    content         TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_message_conversation_id ON message (conversation_id);
