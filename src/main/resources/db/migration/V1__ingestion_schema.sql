-- Phase 1: repositories, ingestion jobs and AST-derived code chunks.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE repositories (
    id             UUID PRIMARY KEY,
    owner          TEXT        NOT NULL,
    name           TEXT        NOT NULL,
    clone_url      TEXT        NOT NULL,
    default_branch TEXT,
    commit_sha     TEXT,
    status         TEXT        NOT NULL CHECK (status IN ('PENDING', 'INGESTING', 'READY', 'FAILED')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner, name)
);

CREATE TABLE ingest_jobs (
    id              UUID PRIMARY KEY,
    repository_id   UUID        NOT NULL REFERENCES repositories (id) ON DELETE CASCADE,
    status          TEXT        NOT NULL CHECK (status IN ('QUEUED', 'CLONING', 'PARSING', 'EMBEDDING', 'COMPLETED', 'FAILED')),
    files_total     INT         NOT NULL DEFAULT 0,
    files_parsed    INT         NOT NULL DEFAULT 0,
    chunks_total    INT         NOT NULL DEFAULT 0,
    chunks_embedded INT         NOT NULL DEFAULT 0,
    lines_of_code   INT         NOT NULL DEFAULT 0,
    error           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ
);

CREATE INDEX ingest_jobs_repository_idx ON ingest_jobs (repository_id, created_at DESC);

-- One row per class/interface/enum/record header or method/constructor.
-- search_text holds identifiers split on camelCase so "findOwner" matches "find owner".
CREATE TABLE code_chunks (
    id            BIGSERIAL PRIMARY KEY,
    repository_id UUID    NOT NULL REFERENCES repositories (id) ON DELETE CASCADE,
    file_path     TEXT    NOT NULL,
    kind          TEXT    NOT NULL,
    package_name  TEXT,
    type_name     TEXT,
    symbol        TEXT    NOT NULL,
    start_line    INT     NOT NULL,
    end_line      INT     NOT NULL,
    content       TEXT    NOT NULL,
    content_hash  TEXT    NOT NULL,
    search_text   TEXT    NOT NULL,
    token_count   INT     NOT NULL,
    embedding     vector(768),
    tsv           tsvector GENERATED ALWAYS AS (to_tsvector('simple', search_text)) STORED
);

CREATE INDEX code_chunks_repository_idx ON code_chunks (repository_id);
CREATE INDEX code_chunks_type_idx ON code_chunks (repository_id, type_name);
CREATE INDEX code_chunks_tsv_idx ON code_chunks USING GIN (tsv);
CREATE INDEX code_chunks_embedding_idx ON code_chunks USING hnsw (embedding vector_cosine_ops);
