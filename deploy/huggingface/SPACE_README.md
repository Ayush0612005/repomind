---
title: RepoMind
emoji: 🧠
colorFrom: blue
colorTo: indigo
sdk: docker
app_port: 7860
pinned: true
short_description: Ask a Java codebase questions; get file:line citations
---

# RepoMind — live demo

Ask questions about a Java codebase and get answers that cite the exact lines, search code with
hybrid vector + keyword retrieval, and explore class outlines. The **Live dashboard** link opens
the service's Grafana operations dashboard.

This Space runs the whole system in one container on free CPU hardware: Postgres + pgvector,
Redis, a local Ollama model (`qwen2.5-coder:3b`), Prometheus, Grafana and the Spring Boot service.
Two repositories are pre-indexed (`spring-projects/spring-petclinic` and RepoMind itself), and the
suggested questions are cached, so they answer instantly. **A new question takes 1–3 minutes** on
the free 2-CPU hardware; with a Claude API key the same service answers in seconds.

If the Space was asleep, the first load takes a couple of minutes while it starts.

Source, architecture, evaluation results and the full production setup (Kafka ingestion, PR
reviews posted to GitHub, MCP server for coding agents):
**https://github.com/Ayush0612005/repomind**
