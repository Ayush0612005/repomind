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

- [ ] **Phase 1 — Ingestion:** clone repo, JavaParser chunking, embeddings into pgvector via async Kafka jobs, status API
- [ ] **Phase 2 — Q&A API:** hybrid retrieval + answers with file/line citations
- [ ] **Phase 3 — Evals:** 30–50 question eval set, accuracy score in CI
- [ ] **Phase 4 — Cost control:** response + semantic cache, token budgets, provider fallback to Ollama
- [ ] **Phase 5 — PR review:** GitHub webhook → diff analysis → review comments
- [ ] **Phase 6 — MCP server:** expose tools to agents, tested from an MCP client

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
