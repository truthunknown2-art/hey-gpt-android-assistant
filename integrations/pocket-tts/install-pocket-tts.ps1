param(
    [string]$Distro = "OpenClawGateway",
    [string]$Voice = "alba",
    [int]$Port = 18080,
    [string]$ServePath = "/hey-gpt-tts"
)

$ErrorActionPreference = "Stop"

if ($Port -ne 18080) {
    throw "The checked-in systemd unit currently requires port 18080."
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$sourceDir = Join-Path $repoRoot "integrations\pocket-tts"
$tailscale = tailscale status --json | ConvertFrom-Json
$selfUser = $tailscale.User.PSObject.Properties[$tailscale.Self.UserID.ToString()].Value.LoginName
if ([string]::IsNullOrWhiteSpace($selfUser)) {
    throw "Could not determine the local Tailscale login."
}

$installRoot = "/home/openclaw/hey-gpt-tts"
$serviceRoot = "$installRoot/service"
$uv = "$installRoot/bin/uv"
$python = "$installRoot/venv/bin/python"

wsl -d $Distro -- bash -lc "set -euo pipefail; mkdir -p '$installRoot/bin' '$serviceRoot' '/home/openclaw/.config/systemd/user'; test -x '$uv' || (curl -LsSf https://astral.sh/uv/0.11.29/install.sh | env UV_UNMANAGED_INSTALL='$installRoot/bin' sh); test -x '$python' || '$uv' venv --python /usr/bin/python3 '$installRoot/venv'; '$uv' pip install --python '$python' -r '/mnt/c/$($sourceDir.Substring(3).Replace('\','/'))/requirements.txt'"
if ($LASTEXITCODE -ne 0) { throw "Pocket TTS dependency installation failed." }

$wslSource = "/mnt/c/$($sourceDir.Substring(3).Replace('\','/'))"
wsl -d $Distro -- cp "$wslSource/hey_gpt_tts_server.py" "$serviceRoot/hey_gpt_tts_server.py"
wsl -d $Distro -- cp "$wslSource/hey-gpt-pocket-tts.service" "/home/openclaw/.config/systemd/user/hey-gpt-pocket-tts.service"

$environmentContent = "HEY_GPT_TTS_ALLOWED_LOGIN=$selfUser`nHEY_GPT_TTS_VOICE=$Voice`n"
$encodedEnvironment = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($environmentContent))
wsl -d $Distro -- bash -lc "set -euo pipefail; printf '%s' '$encodedEnvironment' | base64 -d > '$serviceRoot/hey-gpt-tts.env'; chmod 600 '$serviceRoot/hey-gpt-tts.env'; systemctl --user daemon-reload; systemctl --user enable hey-gpt-pocket-tts.service; systemctl --user restart hey-gpt-pocket-tts.service"
if ($LASTEXITCODE -ne 0) { throw "Pocket TTS service startup failed." }

$deadline = (Get-Date).AddMinutes(2)
do {
    Start-Sleep -Seconds 2
    $active = (wsl -d $Distro -- systemctl --user is-active hey-gpt-pocket-tts.service 2>$null) -eq "active"
    $ready = $false
    if ($active) {
        wsl -d $Distro -- curl --fail --silent --output /dev/null --header "Tailscale-User-Login: $selfUser" "http://127.0.0.1:$Port/health" 2>$null
        $ready = $LASTEXITCODE -eq 0
    }
} until ($ready -or (Get-Date) -ge $deadline)
if (-not $ready) {
    wsl -d $Distro -- journalctl --user -u hey-gpt-pocket-tts.service -n 50 --no-pager
    throw "Pocket TTS service did not become ready."
}

tailscale serve --bg --https=443 --set-path=$ServePath "http://127.0.0.1:$Port"
if ($LASTEXITCODE -ne 0) { throw "Tailscale Serve path configuration failed." }

$dnsName = $tailscale.Self.DNSName.TrimEnd('.')
Write-Host "Pocket TTS is running at https://$dnsName$ServePath"
