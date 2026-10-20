# Runs the full RepoMind stack on this PC (local model on the NVIDIA GPU) and prints a public link
# through a Cloudflare quick tunnel, for live demos. The link is new on every start and works while
# this PC is on and the stack is running. No account, router setup or open ports are needed.
#
#   powershell -ExecutionPolicy Bypass -File deploy\live-demo.ps1           start and print the link
#   powershell -ExecutionPolicy Bypass -File deploy\live-demo.ps1 -Stop     stop; data stays for next time
#   powershell -ExecutionPolicy Bypass -File deploy\live-demo.ps1 -Remove   delete everything RepoMind
#                                                                           keeps in Docker (~21 GB)
#
# Starting works from nothing: Docker downloads the images, the database is restored from
# deploy\snapshot\repomind.dump (the indexed demo repositories and their cached answers), and the
# models are pulled. That first start downloads ~14 GB; later starts take 1-2 minutes.
# Needs Docker Desktop running and deploy\.env (copy deploy\.env.example; set SITE_ADDRESS=:80 and
# HTTP_PORT=8088).
param([switch]$Stop, [switch]$Remove)
# native commands are checked by exit code: Windows PowerShell turns their redirected stderr (docker
# progress, cloudflared logs) into errors under "Stop"
$ErrorActionPreference = "Continue"

$deploy = $PSScriptRoot
$root = Split-Path $deploy -Parent
$envFile = Join-Path $deploy ".env"
$snapshot = Join-Path $deploy "snapshot\repomind.dump"
$models = @("nomic-embed-text", "qwen2.5-coder:7b")
$compose = @("compose", "-p", "repomind-prod",
  "-f", (Join-Path $deploy "compose.prod.yaml"), "-f", (Join-Path $deploy "compose.live.yaml"),
  "--env-file", $envFile)
# every image the stack uses, removed by -Remove (the app image is built locally from the last two)
$images = @("repomind-prod-app", "ollama/ollama:0.40.4", "cloudflare/cloudflared:2026.9.3",
  "pgvector/pgvector:0.8.7-pg18-trixie", "apache/kafka:4.3.1", "redis:8.2-alpine",
  "grafana/grafana:12.0.2", "prom/prometheus:v3.5.0", "caddy:2.10.0",
  "maven:3.9-eclipse-temurin-25", "eclipse-temurin:25-jre")

if (-not (Test-Path $envFile)) {
  throw "deploy\.env is missing. Copy deploy\.env.example to deploy\.env, set SITE_ADDRESS=:80 and HTTP_PORT=8088."
}
docker info *> $null
if ($LASTEXITCODE -ne 0) { throw "Docker is not running. Start Docker Desktop and try again." }

function Wait-Postgres {
  $deadline = (Get-Date).AddMinutes(3)
  while ((docker inspect -f "{{.State.Health.Status}}" repomind-prod-postgres-1 2>$null) -ne "healthy") {
    if ((Get-Date) -gt $deadline) { throw "Postgres did not become healthy. Logs: docker logs repomind-prod-postgres-1" }
    Start-Sleep -Seconds 2
  }
}

if ($Stop) {
  docker @compose stop
  Write-Host "Stopped. The indexed repositories and cached answers are kept for the next start."
  return
}

if ($Remove) {
  # keep anything new (repositories indexed or answers cached during a demo) for the next start
  docker @compose up -d postgres
  Wait-Postgres
  $ready = docker exec repomind-prod-postgres-1 psql -U repomind -d repomind -Atc `
    "SELECT CASE WHEN to_regclass('public.repositories') IS NULL THEN 0 ELSE (SELECT count(*) FROM repositories WHERE status = 'READY') END"
  if ([int]$ready -gt 0) {
    docker exec repomind-prod-postgres-1 pg_dump -U repomind -d repomind -Fc -Z 6 -f /tmp/repomind.dump
    if ($LASTEXITCODE -ne 0) { throw "Could not save the database snapshot; nothing was removed." }
    docker cp repomind-prod-postgres-1:/tmp/repomind.dump $snapshot
    if ($LASTEXITCODE -ne 0) { throw "Could not save the database snapshot; nothing was removed." }
    Write-Host "Saved the database to deploy\snapshot\repomind.dump"
  } else {
    # an empty database must not overwrite a good snapshot
    Write-Host "The database has no indexed repositories; keeping the existing snapshot."
  }

  docker @compose down -v
  # the development stack shares the model volume and images
  docker compose -f (Join-Path $root "compose.yaml") down -v
  foreach ($image in $images) { docker rmi $image 2>$null | Out-Null }
  docker builder prune -f | Out-Null
  Write-Host ""
  Write-Host "Removed RepoMind's containers, data, models and images." -ForegroundColor Green
  Write-Host "Docker's disk file on D: does not shrink by itself; see 'Give the space back to D:' in the README."
  return
}

Write-Host "Starting the database and the model server..."
docker @compose up -d postgres ollama
if ($LASTEXITCODE -ne 0) { throw "docker compose up failed; see the output above." }
Wait-Postgres

$hasSchema = docker exec repomind-prod-postgres-1 psql -U repomind -d repomind -Atc "SELECT to_regclass('public.repositories') IS NOT NULL"
if ($hasSchema -ne "t") {
  if (-not (Test-Path $snapshot)) { throw "The database is empty and deploy\snapshot\repomind.dump is missing." }
  Write-Host "Restoring the demo repositories and cached answers from the snapshot..."
  docker cp $snapshot repomind-prod-postgres-1:/tmp/repomind.dump
  docker exec repomind-prod-postgres-1 pg_restore -U repomind -d repomind --no-owner --exit-on-error /tmp/repomind.dump
  if ($LASTEXITCODE -ne 0) { throw "Restoring the snapshot failed; see the output above." }
}

$deadline = (Get-Date).AddMinutes(2)
while ($true) {
  $installed = docker exec repomind-prod-ollama-1 ollama list 2>$null
  if ($LASTEXITCODE -eq 0) { break }
  if ((Get-Date) -gt $deadline) { throw "The model server did not start. Logs: docker logs repomind-prod-ollama-1" }
  Start-Sleep -Seconds 2
}
foreach ($model in $models) {
  if (-not ($installed | Select-String -SimpleMatch $model)) {
    Write-Host "Downloading the $model model (one time)..."
    docker exec repomind-prod-ollama-1 ollama pull $model
    if ($LASTEXITCODE -ne 0) { throw "Downloading $model failed; check the internet connection and run again." }
  }
}

Write-Host "Starting RepoMind..."
docker @compose up -d --build
if ($LASTEXITCODE -ne 0) { throw "docker compose up failed; see the output above." }

$port = "80"
$portLine = Select-String -Path $envFile -Pattern '^HTTP_PORT=(\d+)' | Select-Object -First 1
if ($portLine) { $port = $portLine.Matches[0].Groups[1].Value }
$local = "http://localhost:$port"

Write-Host "Waiting for the service at $local ..."
$deadline = (Get-Date).AddMinutes(5)
while ($true) {
  try {
    if ((Invoke-WebRequest "$local/api/repos" -UseBasicParsing -TimeoutSec 5).StatusCode -eq 200) { break }
  } catch { }
  if ((Get-Date) -gt $deadline) { throw "The service did not come up in 5 minutes. Logs: docker logs repomind-prod-app-1" }
  Start-Sleep -Seconds 3
}

Write-Host "Loading the model into GPU memory..."
docker exec repomind-prod-ollama-1 ollama run qwen2.5-coder:7b "ok" 2>&1 | Out-Null

# a fresh tunnel container, so its log holds only this run's URL
docker @compose up -d --force-recreate tunnel
if ($LASTEXITCODE -ne 0) { throw "Could not start the tunnel." }
$deadline = (Get-Date).AddMinutes(2)
$url = $null
while (-not $url) {
  $match = docker logs repomind-prod-tunnel-1 2>&1 | Select-String -Pattern 'https://[a-z0-9-]+\.trycloudflare\.com' |
    Select-Object -First 1
  if ($match) { $url = $match.Matches[0].Value; break }
  if ((Get-Date) -gt $deadline) { throw "No tunnel URL yet. Logs: docker logs repomind-prod-tunnel-1" }
  Start-Sleep -Seconds 2
}

Write-Host ""
Write-Host "RepoMind is live:" -ForegroundColor Green
Write-Host "  App:        $url"
Write-Host "  Dashboard:  $url/grafana/"
Write-Host "  On this PC: $local"
Write-Host ""
Write-Host "Stop it with:   powershell -ExecutionPolicy Bypass -File deploy\live-demo.ps1 -Stop"
Write-Host "Free the disk:  powershell -ExecutionPolicy Bypass -File deploy\live-demo.ps1 -Remove"
