-- Phase 4: semantic answer cache. Exact repeats are served from Redis; this table finds earlier
-- questions whose embedding is nearly identical. Rows belong to one index generation, so a
-- re-index makes every cached answer for the old code unreachable, and activation deletes them.

CREATE TABLE answer_cache (
    id                BIGSERIAL PRIMARY KEY,
    repository_id     UUID        NOT NULL REFERENCES repositories (id) ON DELETE CASCADE,
    generation_job_id UUID        NOT NULL REFERENCES ingest_jobs (id) ON DELETE CASCADE,
    question          TEXT        NOT NULL,
    question_key      TEXT        NOT NULL,
    embedding         vector(768) NOT NULL,
    answer            JSONB       NOT NULL,
    hits              INT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_hit_at       TIMESTAMPTZ,
    UNIQUE (generation_job_id, question_key)
);

CREATE INDEX answer_cache_embedding_idx ON answer_cache USING hnsw (embedding vector_cosine_ops);
