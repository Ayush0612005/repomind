# Runs the full RepoMind stack on this PC (local model on the NVIDIA GPU) and prints a public link
# through a Cloudflare quick tunnel, for live demos. The link is new on every start and works while
# this PC is on and the stack is running. No account, router setup or open ports are needed.
#
#   powershell -ExecutionPolicy Bypass -File deploy\live-demo.ps1          start and print the link
#   powershell -ExecutionPolicy Bypass -File deploy\live-demo.ps1 -Stop    stop (indexed data is kept)
#
# Needs Docker Desktop running and deploy\.env (copy deploy\.env.example; set SITE_ADDRESS=:80 and
# HTTP_PORT=8088). The first start builds the app image and takes a few minutes.
param([switch]$Stop)
# native commands are checked by exit code: Windows PowerShell turns their redirected stderr (docker
# progress, cloudflared logs) into errors under "Stop"
$ErrorActionPreference = "Continue"

$deploy = $PSScriptRoot
$envFile = Join-Path $deploy ".env"
$compose = @("compose", "-p", "repomind-prod",
  "-f", (Join-Path $deploy "compose.prod.yaml"), "-f", (Join-Path $deploy "compose.live.yaml"),
  "--env-file", $envFile)

if (-not (Test-Path $envFile)) {
  throw "deploy\.env is missing. Copy deploy\.env.example to deploy\.env, set SITE_ADDRESS=:80 and HTTP_PORT=8088."
}
docker info *> $null
if ($LASTEXITCODE -ne 0) { throw "Docker is not running. Start Docker Desktop and try again." }

if ($Stop) {
  docker @compose stop
  Write-Host "Stopped. The indexed repositories and cached answers are kept for the next start."
  return
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
docker @compose exec -T ollama ollama run qwen2.5-coder:7b "ok" > $null

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
Write-Host "Stop it with: powershell -ExecutionPolicy Bypass -File deploy\live-demo.ps1 -Stop"
