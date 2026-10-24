# Cycle 24 AI book search: try it on the laptop and open it through a Cloudflare quick tunnel.
# Paste the whole file into PowerShell (or run it); it runs as one block, so an error stops it. It
#   1. puts branch claude/awesome-carson-8416jq in its own worktree next to the dev folder (the dev folder is not touched)
#   2. starts the local DB (compose project of the dev folder) and fills aisearch.search_book from the laptop DB:
#      every listed book's title and author, no search line yet (step 2 lines come later, docs/maintenance/6-aisearch.md)
#   3. opens three windows: backend (AI on), web dev server, cloudflared. The tunnel address is in the cloudflared window.
# The API key is asked here and lives only in these windows' environment; it is not written anywhere.
# Liquibase applies the new changesets (aisearch/001, 002, usagelog/008) to the local DB when the backend starts.

& {
  $ErrorActionPreference = 'Stop'
  $dev = 'C:\Users\yujun\Documents\Claude\Projects\chaekcheck-dev'
  $wt = 'C:\Users\yujun\Documents\Claude\Projects\chaekcheck-dev-c24'
  $branch = 'claude/awesome-carson-8416jq'

  # 1. the branch in its own folder
  git -C $dev fetch origin $branch
  if (Test-Path $wt) {
    git -C $wt pull --ff-only origin $branch
  } else {
    git -C $dev worktree add -B c24-try $wt "origin/$branch"
  }
  if ($LASTEXITCODE -ne 0) { throw 'git failed' }
  Copy-Item "$dev\.env" "$wt\.env" -Force

  # 2. the DB (the dev folder's compose project) and the AI search's books
  Push-Location $dev
  wsl -d Ubuntu -- docker compose up -d db
  if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'docker compose failed' }
  foreach ($i in 1..30) {
    wsl -d Ubuntu -- docker compose exec -T db pg_isready -q
    if ($LASTEXITCODE -eq 0) { break }
    Start-Sleep -Seconds 1
  }
  Pop-Location

  $secure = Read-Host 'Anthropic API key (not shown, not saved)' -AsSecureString
  $env:CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
  $env:CHAEKCHECK_AISEARCH_ENABLED = 'true'

  # a file with the header only: the import puts every listed book in, without search lines
  $tsv = Join-Path $env:TEMP 'book-texts-header-only.tsv'
  [IO.File]::WriteAllText($tsv, "kind`tbook_id`tsource`tmade_on`ttext`n", (New-Object Text.UTF8Encoding $false))
  & "$wt\backend\gradlew.bat" -p "$wt\backend" bootRun "--args=--spring.profiles.active=collection-import --chaekcheck.collection.import.mode=book-texts --chaekcheck.collection.import.book-texts-file=$tsv"
  if ($LASTEXITCODE -ne 0) { throw 'book-texts import failed (see the log above)' }

  # 3. backend, web, tunnel (each window inherits the key and AI on from this one)
  Start-Process powershell -ArgumentList '-NoExit', '-Command', "Set-Location '$wt'; .\backend\gradlew.bat -p backend bootRun"
  Push-Location "$wt\web"
  npm install
  Pop-Location
  $env:__VITE_ADDITIONAL_SERVER_ALLOWED_HOSTS = '.trycloudflare.com'
  Start-Process powershell -ArgumentList '-NoExit', '-Command', "Set-Location '$wt'; npm --prefix web run dev -- --port 5175 --strictPort"

  $cf = Get-Command cloudflared -ErrorAction SilentlyContinue
  if (-not $cf) {
    winget install --id Cloudflare.cloudflared -e --accept-source-agreements --accept-package-agreements
    $cf = Get-Command cloudflared -ErrorAction SilentlyContinue
  }
  $cfPath = if ($cf) { $cf.Source } else { "${env:ProgramFiles(x86)}\cloudflared\cloudflared.exe" }
  Start-Process powershell -ArgumentList '-NoExit', '-Command', "& '$cfPath' tunnel --url http://localhost:5175"

  Write-Host ''
  Write-Host 'cloudflared 창에 나오는 https://....trycloudflare.com 주소를 여세요. 백엔드가 뜨기까지 1분쯤 걸립니다.'
}
