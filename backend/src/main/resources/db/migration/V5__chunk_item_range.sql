-- Feature busca-exata-item (specs/busca-exata-item/plan.md, seção "Modelo de dados"; ADR-0013):
-- colunas inteiras derivadas item_start/item_end, usadas pelo ramo exato da busca híbrida (T4,
-- ainda não implementado nesta task). Daqui pra frente a ingestão preenche as duas colunas
-- diretamente a partir de Chunker.groupReference (fonte única, sem parse de string); esta migration
-- faz o backfill único do dado legado já persistido, parseando a coluna `reference` existente.

ALTER TABLE chunk
    ADD COLUMN item_start INT NULL,
    ADD COLUMN item_end INT NULL;

-- Backfill idempotente, restrito a reference_type = 'NUMBERED_ITEM'. `reference` é ou um valor
-- único só de dígitos ("25") ou uma faixa "N–M" separada por en-dash U+2013 (nunca hífen ASCII —
-- ver Chunker.kt, groupReference, literal "$first–$last"). Qualquer linha cujo parse não bata em
-- nenhum dos dois padrões fica com as duas colunas NULL (default do ALTER TABLE acima) — o
-- backfill nunca pode abortar a migration por causa de um dado legado malformado.
--
-- Os dois padrões limitam cada número a no máximo 9 dígitos (`\d{1,9}`, teto seguro < 2^31-1):
-- `reference` é VARCHAR(1000) sem outra garantia de tamanho, então um valor legado tipo
-- "99999999999" bateria em `^\d+$` mas estouraria o `::INT` DENTRO da avaliação do próprio WHERE,
-- abortando o UPDATE inteiro (Postgres não faz curto-circuito por linha em erro de cast) — exatamente
-- o abort que este backfill promete nunca causar. Linha fora do teto de 9 dígitos simplesmente não
-- bate no regex e fica NULL, mesma defesa das demais entradas malformadas.
--
-- A defesa contra faixa invertida (item_start > item_end, ex. "222–5") é o predicado extra
-- `split_part(...,1)::INT <= split_part(...,2)::INT` no próprio WHERE do UPDATE de faixa: a linha
-- malformada nunca chega a ser escrita, então nunca fica em estado inválido nem transitoriamente —
-- importante porque o CHECK chk_chunk_item_range só é adicionado ao final desta mesma migration,
-- mas um backfill futuro reexecutado manualmente (ex. em teste) rodaria com o CHECK já existente.
UPDATE chunk
SET item_start = reference::INT,
    item_end = reference::INT
WHERE reference_type = 'NUMBERED_ITEM'
  AND reference ~ '^\d{1,9}$';

UPDATE chunk
SET item_start = split_part(reference, '–', 1)::INT,
    item_end = split_part(reference, '–', 2)::INT
WHERE reference_type = 'NUMBERED_ITEM'
  AND reference ~ '^\d{1,9}–\d{1,9}$'
  AND split_part(reference, '–', 1)::INT <= split_part(reference, '–', 2)::INT;

CREATE INDEX idx_chunk_item_range ON chunk (book_version_id, item_start, item_end) WHERE item_start IS NOT NULL;

ALTER TABLE chunk
    ADD CONSTRAINT chk_chunk_item_range CHECK (item_start IS NULL OR item_end >= item_start);
