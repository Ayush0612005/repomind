# RepoMind — Codebase Intelligence Service

RepoMind ingests a GitHub Java repository and answers questions about it with file/line citations,
reviews pull requests, and exposes itself as an **MCP server** so AI agents can query codebases.

The interesting part is the backend engineering around the LLM: asynchronous ingestion, AST-aware
chunking, hybrid retrieval, caching, cost control, provider fallback and measured accuracy.

> 🚧 Under active development — see the roadmap below for progress.

## Tech stack

| Area | Choice |
|---|---|
| Runtime | Java 25, Spring Boot 4.1, Spring AI 2.0 |
| Code parsing | JavaParser — AST-based chunking by class/method with metadata |
| Vector store | PostgreSQL 18 + pgvector, hybrid search (vector + full-text) |
| LLMs | Anthropic Claude (primary) via Spring AI, Ollama local model (dev + fallback) |
| Ingestion | Kafka topics for repo-ingest jobs, progress tracked in Postgres |
| Caching / limits | Redis response cache + semantic cache, per-user token budgets and rate limits |
| Integrations | GitHub REST API + webhooks (PR opened → review comments) |
| Agent access | MCP server exposing `search_code`, `explain_class` tools |
| Evaluation | JUnit-driven question/answer eval harness, accuracy tracked per release |
| Ops | Testcontainers, GitHub Actions, Prometheus + Grafana, Docker Compose, AWS EC2 |

## Roadmap

- [x] **Phase 1 — Ingestion:** clone repo, JavaParser chunking, embeddings into pgvector via async Kafka jobs, status API
- [x] **Phase 2 — Q&A API:** hybrid retrieval + answers with file/line citations
- [x] **Phase 3 — Evals:** 30–50 question eval set, accuracy score in CI
- [ ] **Phase 4 — Cost control:** response + semantic cache, token budgets, provider fallback to Ollama *(answer cache, rate limits and token budgets done; provider fallback next)*
- [x] **Phase 5 — PR review:** GitHub webhook → diff analysis → review comments
- [ ] **Phase 6 — MCP server:** expose tools to agents, tested from an MCP client

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/repos` | Track a GitHub repository (`{"repository": "owner/name"}`) and queue ingestion |
| `POST` | `/api/repos/{id}/reindex` | Re-ingest; unchanged chunks reuse their embeddings |
| `GET` | `/api/repos`, `/api/repos/{id}`, `/api/jobs/{id}` | Repository and ingestion progress |
| `GET` | `/api/repos/{id}/search?q=…&mode=HYBRID\|VECTOR\|KEYWORD` | Ranked code chunks with `file:line` citations |
| `POST` | `/api/repos/{id}/ask` | Answer a question (`{"question": "…"}`) from retrieved code, citing sources |
| `POST` | `/api/repos/{id}/pulls/{n}/review` | Preview a review of a pull request without posting it |
| `POST` | `/api/repos/{id}/pulls/{n}/reviews` | Queue a review of the PR's head commit and post it to GitHub |
| `GET` | `/api/review-jobs/{id}` | Review job status and the posted review's URL |
| `POST` | `/api/github/webhook` | GitHub `pull_request` webhook: reviews each new commit automatically |

Errors use RFC 9457 problem details.

### Answer cache

`/ask` checks two cache tiers before spending a model call, both scoped to the repository's
current index generation, so re-indexing invalidates every cached answer for the old code:

- **Exact (Redis):** the same question up to case, spacing and trailing punctuation. Served before
  the question is even embedded.
- **Semantic (pgvector):** an earlier question whose embedding has cosine similarity ≥ 0.97. The
  threshold is deliberately high, since "how many books can a *premium* member borrow" and "…a
  *standard* member…" embed very closely but have different answers. Semantic hits report the
  matched question and similarity in `cache`, so clients can label them.

Failed answers are never cached, and a Redis outage degrades to the semantic tier rather than
failing requests. Hit and miss counts are exported as `repomind.qa.cache` metrics.

### API keys, rate limits and token budgets

With `REPOMIND_LIMITS_ENABLED=true`, every `/api` request is identified by its `X-Api-Key` (or
`Authorization: Bearer`) header. Clients are configured under `repomind.limits.clients` with the
SHA-256 of their key, never the key itself, and requests without a key fall back to a per-IP
anonymous policy (remove it to require keys).

- **Rate limit:** a token bucket per client in Redis, updated by one atomic Lua script using
  Redis's clock, so concurrent requests and multiple app instances agree. Responses carry
  `X-RateLimit-Limit` / `X-RateLimit-Remaining`; rejections are `429` with `Retry-After`.
- **Daily token budget:** model tokens (provider-reported, estimated when a provider reports none)
  per client per UTC day. It is checked immediately before a model call, so cache hits and requests
  that never reach the model are free even when the budget is spent. Model-backed responses carry
  `X-Token-Budget-Remaining`; an exhausted budget is `429` with `Retry-After` set to midnight UTC.
- If Redis is unavailable, limits fail open (and count `repomind.limits.errors`) rather than taking
  the API down. GitHub webhooks are exempt: they are authenticated by signature.

### GitHub setup

- `GITHUB_TOKEN`: a token with *Pull requests: read and write* on the repository. Reading public
  repositories works without one; posting reviews requires it.
- `GITHUB_WEBHOOK_SECRET`: add a webhook to the repository pointing at `/api/github/webhook` with
  content type `application/json`, this secret, and the *Pull requests* event. Deliveries are
  verified with `X-Hub-Signature-256`, and each commit is reviewed at most once, so redeliveries
  are harmless. Reviews are posted as comments; RepoMind never approves or blocks a merge.

## Evaluation

The eval set is a fixed fixture codebase (a 23-file library-management service in
`src/test/resources/eval/library`) and `src/test/resources/eval/questions.json`: 40 questions,
each labelled with the code that answers it and the facts a correct answer states, plus 8
questions about things the code does not contain. Both evals ingest the fixture through the real
pipeline (git clone, AST chunking, embedding, Kafka).

**Retrieval, in CI** (`RetrievalEvalIT`): recall@k and MRR per search mode, published to the CI
job summary. The build fails if any mode drops below its recorded baseline. CI runs offline with
a deterministic hashing embedder, so it gates regressions in chunking, indexing, keyword search and
fusion rather than measuring a real embedding model.

**End to end, with real models** (`LiveEvalIT`, opt-in): the same retrieval with real embeddings,
then every question answered by the chat model and checked for citations of the expected code,
stated facts, and declining questions the code cannot answer. Latest run, on a 6 GB laptop GPU
with `nomic-embed-text` and `qwen2.5-coder:7b`
([full report with every answer](docs/eval/live-2026-10-19-qwen2.5-coder-7b.md)):

| Retrieval | Recall@5 | MRR@10 |
|---|---|---|
| Hybrid | **90.0%** | 0.679 |
| Vector only | 87.5% | 0.714 |
| Keyword only | 75.0% | 0.580 |

| Answers | |
|---|---|
| Cite the expected code | 77.5% |
| Fact recall | 90.0% |
| Unanswerable questions correctly declined | 8 / 8 |
| Answerable questions wrongly declined | 0 / 40 |
| Latency p50 / p95 | 5.0 s / 8.9 s |

What it surfaced: hybrid search finds the most answers, but fusing keyword results costs some
ranking precision against vector alone; when retrieval missed the controller, the 7B model invented
an endpoint (`/api/books/checkout`) instead of declining; and one correct answer named the code
without `[n]` citation markers. Fact matching is by accepted spellings, so it slightly undercounts
correct answers phrased differently.

To run it (models are pulled once into the Ollama volume):

```bash
docker compose up -d ollama
docker exec repomind-ollama-1 ollama pull nomic-embed-text
docker exec repomind-ollama-1 ollama pull qwen2.5-coder:7b
REPOMIND_EVAL_LIVE=true ./mvnw verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=LiveEvalIT
```

Set `REPOMIND_CHAT_PROVIDER=anthropic` and `ANTHROPIC_API_KEY` to evaluate Claude instead.

## Running locally

Prerequisites: JDK 25, Docker (with NVIDIA GPU support for Ollama, optional).

```bash
docker compose up -d                 # Postgres + pgvector, Kafka, Ollama
./mvnw spring-boot:run
```

Tests use Testcontainers and need a running Docker daemon:

```bash
./mvnw verify
```
