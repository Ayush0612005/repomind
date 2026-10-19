-- Phase 3: pull request reviews posted to GitHub.
-- A review is identified by the commit it reviewed: GitHub redelivers webhooks and users click
-- twice, and the unique key turns both into no-ops instead of duplicate reviews on the PR.

CREATE TABLE review_jobs (
    id                UUID PRIMARY KEY,
    repository_id     UUID        NOT NULL REFERENCES repositories (id) ON DELETE CASCADE,
    pr_number         INT         NOT NULL CHECK (pr_number > 0),
    head_sha          TEXT        NOT NULL,
    trigger           TEXT        NOT NULL CHECK (trigger IN ('API', 'WEBHOOK')),
    status            TEXT        NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'POSTED', 'SUPERSEDED', 'FAILED')),
    comments_posted   INT,
    dropped_comments  INT,
    github_review_url TEXT,
    error             TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    UNIQUE (repository_id, pr_number, head_sha)
);

CREATE INDEX review_jobs_pull_idx ON review_jobs (repository_id, pr_number, created_at DESC);
CREATE INDEX review_jobs_pending_idx ON review_jobs (status, created_at) WHERE status IN ('QUEUED', 'RUNNING');
