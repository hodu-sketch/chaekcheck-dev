# Installs the chaekcheck toolchain when it is missing. Safe to run again.
#   JDK 25 (Eclipse Temurin) -> ~/.jdks, user JAVA_HOME
#   Node 24 LTS              -> nvm-windows
#   Docker Engine + Compose  -> inside WSL Ubuntu (asks for the Ubuntu sudo password)
# Usage: powershell -ExecutionPolicy Bypass -File scripts\setup-dev.ps1

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$Distro = 'Ubuntu'

function Install-Jdk25 {
    $jdks = Join-Path $env:USERPROFILE '.jdks'
    $jdk = Get-ChildItem $jdks -Directory -Filter 'jdk-25*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $jdk) {
        Write-Host 'Installing JDK 25 (Temurin)...'
        $pkg = (Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/25/hotspot?os=windows&architecture=x64&image_type=jdk&vendor=eclipse')[0].binary.package
        $zip = Join-Path $env:TEMP $pkg.name
        Invoke-WebRequest $pkg.link -OutFile $zip
        if ((Get-FileHash $zip -Algorithm SHA256).Hash.ToLower() -ne $pkg.checksum) { throw 'JDK checksum mismatch' }
        Expand-Archive $zip -DestinationPath $jdks -Force
        Remove-Item $zip
        $jdk = Get-ChildItem $jdks -Directory -Filter 'jdk-25*' | Select-Object -First 1
    }
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk.FullName, 'User')
    Write-Host "JDK 25: $($jdk.FullName) (user JAVA_HOME)"
}

function Install-Node24 {
    if (-not (Get-Command nvm -ErrorAction SilentlyContinue)) {
        throw 'nvm-windows is not installed. Install it from https://github.com/coreybutler/nvm-windows/releases and run this script again.'
    }
    if (-not ((nvm list) -match '\b24\.')) {
        Write-Host 'Installing Node 24...'
        nvm install 24 | Out-Host
    }
    $v = (nvm list | Select-String '\b24\.\d+\.\d+' | Select-Object -First 1).Matches[0].Value
    nvm use $v | Out-Host
}

function Install-DockerInWsl {
    wsl -d $Distro -- bash -lc 'command -v docker >/dev/null && docker compose version >/dev/null'
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Installing Docker Engine and Compose in WSL $Distro (sudo password may be asked)..."
        wsl -d $Distro -- bash -lc 'curl -fsSL https://get.docker.com | sudo sh && sudo usermod -aG docker $USER'
        if ($LASTEXITCODE -ne 0) { throw 'Docker install in WSL failed' }
        wsl --terminate $Distro
    }
    wsl -d $Distro -- bash -lc 'docker --version && docker compose version'
}

Install-Jdk25
Install-Node24
Install-DockerInWsl
Write-Host 'Done. Open a new terminal so JAVA_HOME is picked up.'
