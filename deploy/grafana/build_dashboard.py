"""Generates dashboards/repomind.json, the RepoMind operations dashboard (dashboard as code).

Run after editing:  python deploy/grafana/build_dashboard.py
"""
import json
from pathlib import Path

DS = {"type": "prometheus", "uid": "prometheus"}
API = 'uri=~"/api/.*|/mcp"'

panels = []
_id = 0
_y = 0


def _next_id():
    global _id
    _id += 1
    return _id


def row(title):
    global _y
    panels.append({"type": "row", "title": title, "id": _next_id(), "collapsed": False,
                   "gridPos": {"x": 0, "y": _y, "w": 24, "h": 1}, "panels": []})
    _y += 1


def target(expr, legend="", instant=False):
    return {"datasource": DS, "expr": expr, "legendFormat": legend, "refId": chr(65 + len(legend) % 26),
            "instant": instant, "range": not instant}


def stat(title, expr, x, w=4, h=4, unit="short", decimals=0, description="", mappings=None, color="blue"):
    panels.append({
        "type": "stat", "title": title, "id": _next_id(), "datasource": DS, "description": description,
        "gridPos": {"x": x, "y": _y, "w": w, "h": h},
        "targets": [target(expr, instant=True)],
        "options": {"reduceOptions": {"calcs": ["lastNotNull"], "values": False}, "colorMode": "value",
                    "graphMode": "none", "textMode": "value", "justifyMode": "center"},
        "fieldConfig": {"defaults": {"unit": unit, "decimals": decimals, "mappings": mappings or [],
                                     "color": {"mode": "fixed", "fixedColor": color}}, "overrides": []},
    })


def series(title, targets, x, w=12, h=8, unit="short", description="", stack=False):
    refs = []
    for i, (expr, legend) in enumerate(targets):
        t = target(expr, legend)
        t["refId"] = chr(65 + i)
        refs.append(t)
    panels.append({
        "type": "timeseries", "title": title, "id": _next_id(), "datasource": DS, "description": description,
        "gridPos": {"x": x, "y": _y, "w": w, "h": h}, "targets": refs,
        "options": {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi"}},
        "fieldConfig": {"defaults": {"unit": unit, "custom": {
            "lineWidth": 2, "fillOpacity": 12, "showPoints": "never", "spanNulls": True,
            "stacking": {"mode": "normal" if stack else "none"}}}, "overrides": []},
    })


def text(title, markdown, x, w, h):
    panels.append({"type": "text", "title": title, "id": _next_id(), "gridPos": {"x": x, "y": _y, "w": w, "h": h},
                   "options": {"mode": "markdown", "content": markdown}})


def advance(h):
    global _y
    _y += h


# ------------------------------------------------------------------------------------------ overview
row("Overview")
stat("Repositories indexed", 'sum(repomind_repositories{status="ready"})', 0, color="green")
stat("Code chunks indexed", "sum(repomind_index_chunks)", 4)
stat("Lines of code indexed", "sum(repomind_index_lines)", 8, unit="short", decimals=1)
stat("Questions answered (24 h)", "sum(increase(repomind_qa_cache_total[24h]))", 12)
stat("Cache hit rate (24 h)",
     'sum(increase(repomind_qa_cache_total{result!="miss"}[24h])) / sum(increase(repomind_qa_cache_total[24h]))',
     16, unit="percentunit", decimals=1, color="purple",
     description="Answers served by the exact (Redis) or semantic (pgvector) cache instead of a model call")
stat("Model tokens spent (24 h)", "sum(increase(repomind_limits_tokens_total[24h]))", 20, color="orange",
     description="Prompt + completion tokens charged to API clients' budgets")
advance(4)

# ---------------------------------------------------------------------------------- traffic and latency
row("Traffic and latency")
series("Requests per second by endpoint",
       [(f"sum by (method, uri) (rate(http_server_requests_seconds_count{{{API}}}[5m]))", "{{method}} {{uri}}")],
       0, unit="reqps")
series("Latency by endpoint (p95)",
       [(f"histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket{{{API}}}[5m])))",
         "{{uri}}")], 12, unit="s")
advance(8)
series("Responses by status",
       [(f"sum by (status) (rate(http_server_requests_seconds_count{{{API}}}[5m]))", "{{status}}")],
       0, unit="reqps", stack=True,
       description="401 = unknown API key, 429 = rate limit or token budget, 503 = no model available")
series("Retrieval and embedding latency (p95)",
       [("histogram_quantile(0.95, sum by (le, mode) (rate(repomind_search_latency_seconds_bucket[5m])))",
         "search {{mode}}"),
        ("histogram_quantile(0.95, sum by (le, type) (rate(repomind_embedding_latency_seconds_bucket[5m])))",
         "embed {{type}}")], 12, unit="s")
advance(8)

# ----------------------------------------------------------------------------------------------- LLM
row("Language models")
series("Model calls by provider and outcome",
       [("sum by (provider, outcome) (rate(repomind_chat_calls_total[5m]))", "{{provider}} {{outcome}}")],
       0, w=9, unit="reqps",
       description="fallback = answered by a lower-priority provider; skipped = circuit open, not even tried")
panels.append({
    "type": "stat", "title": "Circuit breakers", "id": _next_id(), "datasource": DS,
    "description": "Per-provider Resilience4j circuit: an open circuit is skipped until it recovers",
    "gridPos": {"x": 9, "y": _y, "w": 5, "h": 8},
    "targets": [target("max by (provider) (repomind_chat_circuit_state)", "{{provider}}", instant=True)],
    "options": {"reduceOptions": {"calcs": ["lastNotNull"], "values": False}, "colorMode": "background",
                "graphMode": "none", "textMode": "value_and_name", "orientation": "horizontal"},
    "fieldConfig": {"defaults": {"mappings": [{"type": "value", "options": {
        "0": {"text": "closed", "color": "green"}, "1": {"text": "OPEN", "color": "red"},
        "2": {"text": "half-open", "color": "orange"}}}]}, "overrides": []},
})
series("Model latency",
       [("histogram_quantile(0.5, sum by (le) (rate(repomind_qa_model_latency_seconds_bucket[15m])))", "answer p50"),
        ("histogram_quantile(0.95, sum by (le) (rate(repomind_qa_model_latency_seconds_bucket[15m])))", "answer p95"),
        ("histogram_quantile(0.95, sum by (le) (rate(repomind_review_model_latency_seconds_bucket[15m])))",
         "review p95")], 14, w=10, unit="s")
advance(8)
series("Answer cache results",
       [("sum by (result) (increase(repomind_qa_cache_total[15m]))", "{{result}}")], 0, w=8, stack=True,
       description="exact = Redis, semantic = similar earlier question (cosine >= 0.97), miss = model call")
series("Tokens spent by client",
       [("sum by (client) (increase(repomind_limits_tokens_total[15m]))", "{{client}}")], 8, w=8, stack=True)
series("Rejected requests",
       [("sum by (reason) (increase(repomind_limits_rejected_total[15m]))", "{{reason}}"),
        ("sum by (operation) (increase(repomind_limits_errors_total[15m]))", "limiter error {{operation}}")],
       16, w=8, description="rate = token bucket empty, budget = daily token budget spent; errors fail open")
advance(8)

# ------------------------------------------------------------------------------ ingestion and reviews
row("Ingestion and reviews")
series("Chunks embedded vs reused on re-index",
       [("sum by (source) (increase(repomind_ingest_chunks_total[1h]))", "{{source}}")], 0, w=8, stack=True,
       description="reused = unchanged chunk whose embedding was copied from the previous index")
series("Ingestion duration",
       [("histogram_quantile(0.5, sum by (le) (rate(repomind_ingest_duration_seconds_bucket[6h])))", "p50"),
        ("histogram_quantile(0.95, sum by (le) (rate(repomind_ingest_duration_seconds_bucket[6h])))", "p95")],
       8, w=8, unit="s")
series("Review jobs by outcome",
       [("sum by (outcome) (increase(repomind_review_jobs_total[1h]))", "{{outcome}}"),
        ("sum(increase(repomind_review_dropped_total[1h]))", "comments dropped (outside diff)")], 16, w=8)
advance(8)

# ----------------------------------------------------------------------------------------------- JVM
row("Service health")
series("JVM heap", [('sum by (id) (jvm_memory_used_bytes{area="heap"})', "{{id}}")], 0, w=8, unit="bytes",
       stack=True)
series("CPU", [("process_cpu_usage", "process"), ("system_cpu_usage", "system")], 8, w=8, unit="percentunit")
series("Threads and DB connections",
       [("jvm_threads_live_threads", "live threads"), ("hikaricp_connections_active", "active DB connections")],
       16, w=8)
advance(8)

# ------------------------------------------------------------------------------------------- context
row("About")
text("Evaluation results", """
| Retrieval, 40 labelled questions | Recall@5 | MRR@10 |
|---|---|---|
| Hybrid (vector + keyword, RRF) | **90.0%** | 0.679 |
| Vector only | 87.5% | 0.714 |
| Keyword only | 75.0% | 0.580 |

Answers (qwen2.5-coder:7b): 77.5% cite the expected code, 90% fact recall,
8/8 unanswerable questions correctly declined.
CI fails the build if retrieval drops below its baseline.
""", 0, 12, 8)
text("What this dashboard shows", """
**RepoMind** answers questions about Java codebases with file:line citations, reviews pull requests
and serves AI agents over MCP. These panels are its live metrics, scraped by Prometheus from
Spring Boot Actuator / Micrometer.

* **Cache hit rate**: answers served without spending a model call
* **Circuit breakers**: Claude → Ollama failover state per provider
* **Rejected requests**: per-client rate limits and daily token budgets

Source: [github.com/Ayush0612005/repomind](https://github.com/Ayush0612005/repomind)
""", 12, 12, 8)
advance(8)

dashboard = {
    "uid": "repomind-ops", "title": "RepoMind — Operations", "tags": ["repomind"], "timezone": "browser",
    "schemaVersion": 39, "version": 1, "editable": False, "refresh": "30s",
    "time": {"from": "now-6h", "to": "now"}, "panels": panels,
    "annotations": {"list": []}, "templating": {"list": []},
}

out = Path(__file__).parent / "dashboards" / "repomind.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(dashboard, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print(f"{len(panels)} panels -> {out}")
