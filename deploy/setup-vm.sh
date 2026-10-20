#!/usr/bin/env bash
# One-shot setup of a fresh Ubuntu 24.04 VM (tested on an Oracle Cloud Always Free Ampere A1 instance).
# Run as the default sudo user:   bash setup-vm.sh <public-ip>
# Idempotent: re-running pulls the latest code and redeploys, keeping data and the existing .env.
set -euo pipefail

PUBLIC_IP="${1:?usage: setup-vm.sh <public-ip>}"
REPO_URL="${REPO_URL:-https://github.com/Ayush0612005/repomind.git}"
DIR=/opt/repomind
COMPOSE=(sudo docker compose -f "$DIR/deploy/compose.prod.yaml" --env-file "$DIR/deploy/.env")

echo "==> Docker"
if ! command -v docker >/dev/null; then
  sudo apt-get update -q
  sudo apt-get install -y -q docker.io docker-compose-v2 docker-buildx git
  sudo systemctl enable --now docker
fi

echo "==> Firewall"
# Oracle's Ubuntu images ship iptables rules that reject everything except SSH, in addition to the
# cloud security list; open HTTP and HTTPS and persist the rules
for port in 80 443; do
  sudo iptables -C INPUT -p tcp --dport "$port" -m state --state NEW -j ACCEPT 2>/dev/null \
    || sudo iptables -I INPUT 5 -p tcp --dport "$port" -m state --state NEW -j ACCEPT
done
sudo apt-get install -y -q iptables-persistent >/dev/null 2>&1 || true
sudo netfilter-persistent save >/dev/null 2>&1 || true

echo "==> Code"
sudo mkdir -p "$DIR" && sudo chown "$USER" "$DIR"
if [ -d "$DIR/.git" ]; then git -C "$DIR" pull --ff-only; else git clone "$REPO_URL" "$DIR"; fi

ENV_FILE="$DIR/deploy/.env"
if [ ! -f "$ENV_FILE" ]; then
  echo "==> Generating $ENV_FILE"
  ADMIN_KEY="rm_$(openssl rand -hex 24)"
  sed -e "s|^SITE_ADDRESS=.*|SITE_ADDRESS=repomind.${PUBLIC_IP//./-}.sslip.io|" \
      -e "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=$(openssl rand -hex 24)|" \
      -e "s|^GRAFANA_ADMIN_PASSWORD=.*|GRAFANA_ADMIN_PASSWORD=$(openssl rand -hex 12)|" \
      -e "s|^REPOMIND_LIMITS_CLIENTS_0_KEYSHA256=.*|REPOMIND_LIMITS_CLIENTS_0_KEYSHA256=$(printf %s "$ADMIN_KEY" | sha256sum | cut -d' ' -f1)|" \
      "$DIR/deploy/.env.example" > "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  printf '%s\n' "$ADMIN_KEY" > "$DIR/deploy/admin-key.txt"
  chmod 600 "$DIR/deploy/admin-key.txt"
  echo "    admin API key written to $DIR/deploy/admin-key.txt (only its hash is in .env)"
fi

echo "==> Starting the stack"
"${COMPOSE[@]}" up -d --build

echo "==> Models"
CHAT_MODEL="$(grep -E '^REPOMIND_OLLAMA_CHAT_MODEL=' "$ENV_FILE" | cut -d= -f2)"
"${COMPOSE[@]}" exec -T ollama ollama pull nomic-embed-text
"${COMPOSE[@]}" exec -T ollama ollama pull "${CHAT_MODEL:-qwen2.5-coder:3b}"

SITE="$(grep -E '^SITE_ADDRESS=' "$ENV_FILE" | cut -d= -f2)"
echo
echo "Done. Web UI: https://$SITE/   Dashboard: https://$SITE/grafana/   MCP: https://$SITE/mcp"
echo "Next: bash $DIR/deploy/bootstrap-demo.sh https://$SITE \"\$(cat $DIR/deploy/admin-key.txt)\""
