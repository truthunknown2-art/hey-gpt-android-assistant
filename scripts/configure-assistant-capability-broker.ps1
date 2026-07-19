[CmdletBinding()]
param(
    [string]$Distro = "OpenClawGateway"
)

$ErrorActionPreference = "Stop"
$PluginId = "assistant-capability-broker"
$StagePath = "/home/openclaw/.openclaw/plugin-dev/$PluginId"
$StageNextPath = "$StagePath.next"

. (Join-Path $PSScriptRoot "lib\wsl-plugin-transfer.ps1")

function Invoke-WslBash {
    param([string]$Command)

    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Command))
    $runner = "printf '%s' '$encoded' | base64 -d | bash"
    $output = & wsl.exe -d $Distro -- bash -lc $runner
    if ($LASTEXITCODE -ne 0) {
        throw "WSL command failed: $Command"
    }
    return $output
}

function Invoke-OpenClaw {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    $quotedArguments = @($Arguments | ForEach-Object {
        "'" + $_.Replace("'", "'\''") + "'"
    })
    $command = "openclaw " + ($quotedArguments -join " ")
    $nodeBin = "/home/openclaw/.openclaw/tools/node/bin"
    $openClawBin = "/home/openclaw/.openclaw/bin"
    return Invoke-WslBash "export PATH='${nodeBin}:${openClawBin}':`$PATH; $command"
}

$pluginWindowsPath = (Resolve-Path (Join-Path $PSScriptRoot "..\integrations\$PluginId")).Path
Send-PluginBundleToWsl `
    -Distro $Distro `
    -SourcePath $pluginWindowsPath `
    -DestinationPath $StageNextPath `
    -Entries @("package.json", "openclaw.plugin.json", "README.md", "dist", "test")

$stageCommand = @"
set -euo pipefail
find '$StageNextPath' -type d -exec chmod 755 {} +
find '$StageNextPath' -type f -exec chmod 644 {} +
export PATH="/home/openclaw/.openclaw/tools/node/bin:/home/openclaw/.openclaw/bin:`$PATH"
cd '$StageNextPath'
npm test
rm -rf '$StagePath.previous'
if [ -e '$StagePath' ]; then mv '$StagePath' '$StagePath.previous'; fi
if ! mv '$StageNextPath' '$StagePath'; then
    if [ -e '$StagePath.previous' ]; then mv '$StagePath.previous' '$StagePath'; fi
    exit 1
fi
rm -rf '$StagePath.previous'
"@
Invoke-WslBash $stageCommand | Out-Null

$pluginList = ((Invoke-OpenClaw plugins list --json) -join "`n") | ConvertFrom-Json
$existing = @($pluginList.plugins | Where-Object id -eq $PluginId)
if ($existing.Count -eq 0) {
    Invoke-OpenClaw plugins install --link $StagePath | Out-Null
} elseif ($existing.Count -ne 1 -or $existing[0].rootDir -ne $StagePath) {
    throw "Plugin '$PluginId' is already registered from an unexpected path."
}

Invoke-OpenClaw plugins enable $PluginId | Out-Null
Invoke-OpenClaw config validate | Out-Null
Invoke-OpenClaw gateway restart | Out-Null

$runtime = ((Invoke-OpenClaw plugins inspect $PluginId --runtime --json) -join "`n") | ConvertFrom-Json
$tools = @($runtime.plugin.toolNames | ForEach-Object { [string]$_ }) | Sort-Object -Unique
$knownTools = @(
    "assistant_contacts_search",
    "assistant_phone_call",
    "assistant_sms_send",
    "assistant_calendar_next",
    "assistant_calendar_create",
    "assistant_memory_forget",
    "assistant_memory_remember",
    "assistant_windows_files_read",
    "assistant_windows_files_search"
) | Sort-Object
if ($runtime.plugin.status -ne "loaded") {
    throw "Plugin '$PluginId' did not load."
}
if (@($tools | Where-Object { $_ -notin $knownTools }).Count -gt 0) {
    throw "Broker registered an unexpected tool surface: $($tools -join ', ')."
}

$status = ((Invoke-OpenClaw gateway call assistant.broker.status --json) -join "`n") | ConvertFrom-Json
if ($status.modelToolsRegistered -ne $tools.Count) {
    throw "Broker status tool count does not match its runtime registration."
}

Write-Host "Assistant capability broker is active on $Distro with durable ledgers and $($tools.Count) optional model tools registered."
Write-Host "Plans: $($status.plans); pending proposals: $($status.pendingProposals); receipts: $($status.terminalReceipts)."
