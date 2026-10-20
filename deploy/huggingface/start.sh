#!/usr/bin/env bash
# Starts every service of the single-container Space, restoring the demo data on each boot (the
# free Space has no persistent disk). Exits when any service dies, so the Space restarts cleanly.
set -euo pipefail

APP=/opt/repomind
LOGS="$HOME/logs"
mkdir -p "$LOGS"
log() { echo "[start] $*"; }

log "postgres: initialising and restoring the demo snapshot"
export PGDATA="$HOME/pgdata"
initdb -D "$PGDATA" -U repomind --auth=trust > "$LOGS/initdb.log"
pg_ctl -D "$PGDATA" -l "$LOGS/postgres.log" \
  -o "-c listen_addresses=127.0.0.1 -c unix_socket_directories=/tmp -c shared_buffers=256MB" start
createdb -h 127.0.0.1 -U repomind repomind
pg_restore -h 127.0.0.1 -U repomind -d repomind --no-owner --exit-on-error "$APP/data/repomind.dump"

log "redis"
redis-server --bind 127.0.0.1 --save "" --appendonly no --maxmemory 256mb --maxmemory-policy allkeys-lru \
  --daemonize yes --logfile "$LOGS/redis.log"

log "ollama"
ollama serve > "$LOGS/ollama.log" 2>&1 &

log "prometheus"
prometheus --config.file="$APP/prometheus.yml" --storage.tsdb.path="$HOME/prometheus" \
  --storage.tsdb.retention.time=7d --web.listen-address=127.0.0.1:9090 > "$LOGS/prometheus.log" 2>&1 &

log "grafana"
mkdir -p "$HOME/grafana"
GF_PATHS_DATA="$HOME/grafana" GF_PATHS_LOGS="$LOGS" GF_PATHS_PROVISIONING="$APP/grafana/provisioning" \
GF_SERVER_HTTP_ADDR=127.0.0.1 GF_SERVER_HTTP_PORT=3000 \
GF_SERVER_ROOT_URL="%(protocol)s://%(domain)s/grafana/" GF_SERVER_SERVE_FROM_SUB_PATH=true \
GF_AUTH_ANONYMOUS_ENABLED=true GF_AUTH_ANONYMOUS_ORG_ROLE=Viewer GF_USERS_ALLOW_SIGN_UP=false \
GF_ANALYTICS_REPORTING_ENABLED=false GF_SECURITY_ADMIN_PASSWORD="${GRAFANA_ADMIN_PASSWORD:-$(head -c 18 /dev/urandom | base64)}" \
GF_SECURITY_ALLOW_EMBEDDING=true \
GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH="$APP/grafana/dashboards/repomind.json" \
  grafana server --homepath /opt/grafana > "$LOGS/grafana.log" 2>&1 &

log "repomind"
# shellcheck disable=SC2086
java $JAVA_OPTS -jar "$APP/app.jar" > "$LOGS/app.log" 2>&1 &

log "caddy on :7860"
caddy run --config "$APP/Caddyfile" --adapter caddyfile > "$LOGS/caddy.log" 2>&1 &

# warm the chat model into memory so the first new question doesn't also pay the load time
( until curl -sf http://127.0.0.1:11434/api/version > /dev/null; do sleep 2; done
  curl -sf http://127.0.0.1:11434/api/generate -d '{"model":"qwen2.5-coder:3b","prompt":"ok","stream":false}' > /dev/null
  log "models loaded" ) &

tail -n +1 -F "$LOGS/app.log" &
wait -n
log "a service exited; stopping so the Space restarts"
exit 1
