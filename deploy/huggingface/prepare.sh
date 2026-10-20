#!/usr/bin/env bash
# Assembles the Hugging Face Space folder: Space files + dashboard + a database snapshot of the
# local production stack (indexed demo repositories and their pre-warmed answers).
#   bash deploy/huggingface/prepare.sh [output-dir]      (default D:/DevCache/hf-space)
# Needs the local stack from deploy/compose.prod.yaml running with the demo already bootstrapped.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${1:-/d/DevCache/hf-space}"
PG_CONTAINER="${PG_CONTAINER:-repomind-prod-postgres-1}"

commit="$(git -C "$REPO_ROOT" rev-parse HEAD)"
if ! git -C "$REPO_ROOT" branch -r --contains "$commit" | grep -q origin/; then
  echo "HEAD $commit is not pushed; the Space builds from GitHub, so push first" >&2
  exit 1
fi

rm -rf "$OUT" && mkdir -p "$OUT/data" "$OUT/grafana/dashboards"
cp -r "$REPO_ROOT/deploy/huggingface/." "$OUT/"
rm -f "$OUT/prepare.sh" "$OUT/publish.py"
mv "$OUT/SPACE_README.md" "$OUT/README.md"
cp "$REPO_ROOT/deploy/grafana/dashboards/repomind.json" "$OUT/grafana/dashboards/"
sed -i "s/^ARG REPOMIND_COMMIT=.*/ARG REPOMIND_COMMIT=$commit/" "$OUT/Dockerfile"

echo "==> Snapshot of $PG_CONTAINER"
docker exec "$PG_CONTAINER" pg_dump -U repomind -d repomind -Fc -Z 6 > "$OUT/data/repomind.dump"
docker exec "$PG_CONTAINER" psql -U repomind -d repomind -Atc \
  "SELECT 'repositories: ' || (SELECT count(*) FROM repositories WHERE status = 'READY')
       || ', chunks: ' || (SELECT count(*) FROM code_chunks c JOIN repositories r ON c.ingest_job_id = r.active_job_id)
       || ', cached answers: ' || (SELECT count(*) FROM answer_cache)"

printf '*.dump filter=lfs diff=lfs merge=lfs -text\n' > "$OUT/.gitattributes"
echo "==> $OUT ($(du -sh "$OUT" | cut -f1)), app pinned to $commit"
