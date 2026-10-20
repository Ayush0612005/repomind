#!/usr/bin/env bash
# Indexes the demo repositories and warms the answer cache with the web UI's suggested questions,
# so a first-time visitor gets instant answers even on the CPU-only model.
#   bash deploy/bootstrap-demo.sh https://repomind.<ip>.sslip.io <admin-api-key>
set -euo pipefail

BASE="${1:?usage: bootstrap-demo.sh <base-url> <admin-key>}"
KEY="${2:?usage: bootstrap-demo.sh <base-url> <admin-key>}"
REPOS=("spring-projects/spring-petclinic" "Ayush0612005/repomind")

json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

for repo in "${REPOS[@]}"; do
  # posting a tracked repository re-indexes it, which would also invalidate its cached answers
  ready="$(curl -fsS "$BASE/api/repos" | json "any(r['fullName'] == '$repo' and r['status'] == 'READY' for r in d)")"
  if [ "$ready" = "True" ]; then
    echo "==> $repo is already indexed"
    continue
  fi
  echo "==> Indexing $repo"
  response="$(curl -fsS -X POST "$BASE/api/repos" -H "X-Api-Key: $KEY" -H 'Content-Type: application/json' \
    -d "{\"repository\": \"$repo\"}" || true)"
  job="$(printf '%s' "$response" | json "d['job']['id']" 2>/dev/null || true)"
  if [ -z "$job" ]; then
    echo "    already indexing or indexed: $response"
    continue
  fi
  while :; do
    status="$(curl -fsS "$BASE/api/jobs/$job" | json "d['status'] + ' ' + str(d['progressPercent']) + '%'")"
    echo "    $status"
    case "$status" in COMPLETED*|FAILED*) break ;; esac
    sleep 10
  done
done

warm() {
  local repo="$1"; shift
  local id
  id="$(curl -fsS "$BASE/api/repos" | json "next(r['id'] for r in d if r['fullName'] == '$repo')")"
  for question in "$@"; do
    echo "==> Warming cache: $repo: $question"
    curl -fsS -m 600 -X POST "$BASE/api/repos/$id/ask" -H "X-Api-Key: $KEY" -H 'Content-Type: application/json' \
      -d "$(python3 -c 'import json,sys; print(json.dumps({"question": sys.argv[1]}))' "$question")" \
      | json "d['cache']['status'] + ' in ' + str(d['latencyMs']) + ' ms'"
  done
}

# keep these in sync with SUGGESTIONS in src/main/resources/static/index.html
warm "spring-projects/spring-petclinic" \
  "How is a new owner validated before it is saved?" \
  "Where are vets and their specialties loaded?" \
  "How does the app find owners by last name?" \
  "What happens when a pet's birth date is in the future?"
warm "Ayush0612005/repomind" \
  "How does hybrid search merge vector and keyword results?" \
  "How is a review comment prevented from targeting lines outside the diff?" \
  "How does the rate limiter work?" \
  "When does the chat gateway fall back to Ollama?"

echo "Done: $BASE/"
