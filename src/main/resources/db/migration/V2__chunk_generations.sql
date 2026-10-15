-- Each ingestion writes a new generation of chunks tagged with its job id. Queries only read the
-- repository's active generation, so a re-index never serves a half-built index; on success the
-- active pointer is swapped and older generations are deleted in one transaction.

ALTER TABLE code_chunks
    ADD COLUMN ingest_job_id UUID NOT NULL REFERENCES ingest_jobs (id) ON DELETE CASCADE;

ALTER TABLE repositories
    ADD COLUMN active_job_id UUID REFERENCES ingest_jobs (id) ON DELETE SET NULL;

DROP INDEX code_chunks_repository_idx;
CREATE INDEX code_chunks_job_idx ON code_chunks (ingest_job_id);
CREATE INDEX code_chunks_unembedded_idx ON code_chunks (ingest_job_id, id) WHERE embedding IS NULL;
CREATE INDEX code_chunks_reuse_idx ON code_chunks (repository_id, content_hash);

-- At most one unfinished job per repository, enforced by the database rather than a racy check.
CREATE UNIQUE INDEX ingest_jobs_one_active_per_repo
    ON ingest_jobs (repository_id)
    WHERE status NOT IN ('COMPLETED', 'FAILED');
