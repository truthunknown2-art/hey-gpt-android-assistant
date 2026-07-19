[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$NodeId,
    [Parameter(Mandatory = $true)][string]$AndroidNodeId,
    [Parameter(Mandatory = $true)][string]$BrokerKeyId,
    [Parameter(Mandatory = $true)][string]$BrokerPublicKeyBase64Url,
    [string]$Distro = "OpenClawGateway",
    [string]$AgentId = "voice-main",
    [string]$ReadRoot = [Environment]::GetFolderPath("MyDocuments"),
    [string]$StateDir = (Join-Path $env:LOCALAPPDATA "OpenClaw\AgenticWindowsNode"),
    [string]$TaskName = "OpenClaw Node"
)

$ErrorActionPreference = "Stop"
$PluginId = "assistant-capability-broker"
$WindowsCommand = "assistant.windows.execute.v1"
$WindowsTools = @("assistant_windows_files_read", "assistant_windows_files_search")
$RestrictedNodeCommands = @(
    "browser.proxy",
    "system.execApprovals.get",
    "system.execApprovals.set",
    "system.run",
    "system.run.prepare",
    "system.which"
)

function Invoke-LocalOpenClaw {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    $output = & openclaw @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Local OpenClaw command failed: openclaw $($Arguments -join ' ')"
    }
    return $output
}

function Invoke-GatewayBash {
    param([Parameter(Mandatory = $true)][string]$Command)

    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Command))
    $runner = "printf '%s' '$encoded' | base64 -d | bash"
    $output = & wsl.exe -d $Distro -- bash -lc $runner
    if ($LASTEXITCODE -ne 0) {
        throw "Gateway command failed."
    }
    return $output
}

function Invoke-GatewayOpenClaw {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    $quotedArguments = @($Arguments | ForEach-Object {
        "'" + $_.Replace("'", "'\''") + "'"
    })
    $command = "openclaw " + ($quotedArguments -join " ")
    return Invoke-GatewayBash -Command $command
}

function Assert-ChildPath {
    param([string]$Path, [string]$Parent, [string]$Label)

    $fullPath = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $fullParent = [IO.Path]::GetFullPath($Parent).TrimEnd('\')
    if (-not $fullPath.StartsWith("$fullParent\", [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label must stay under '$fullParent'."
    }
    return $fullPath
}

if ($NodeId -notmatch '^[a-f0-9]{64}$' -or $AndroidNodeId -notmatch '^[a-f0-9]{64}$') {
    throw "NodeId and AndroidNodeId must each be exactly 64 lowercase hexadecimal characters."
}
if ($Distro -notmatch '^[A-Za-z0-9._-]+$' -or $AgentId -notmatch '^[a-z0-9][a-z0-9_-]{0,63}$') {
    throw "Distro or AgentId has an invalid format."
}
if ($BrokerKeyId -notmatch '^[A-Za-z0-9._-]{1,128}$') {
    throw "BrokerKeyId has an invalid format."
}
if ($BrokerPublicKeyBase64Url -notmatch '^[A-Za-z0-9_-]{43}$') {
    throw "BrokerPublicKeyBase64Url must be a 32-byte base64url Ed25519 public key."
}

$openClawRoot = Join-Path $env:LOCALAPPDATA "OpenClaw"
$resolvedStateDir = Assert-ChildPath -Path $StateDir -Parent $openClawRoot -Label "StateDir"
$resolvedReadRoot = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $ReadRoot).Path).TrimEnd('\')
if (-not (Test-Path -LiteralPath $resolvedReadRoot -PathType Container)) {
    throw "ReadRoot must be an existing directory."
}

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction Stop
$expectedLauncher = Join-Path $resolvedStateDir "node.vbs"
$taskLaunchers = @($task.Actions | ForEach-Object { [IO.Path]::GetFullPath([string]$_.Execute) })
if ($expectedLauncher -notin $taskLaunchers) {
    throw "Scheduled task '$TaskName' does not belong to the dedicated state directory."
}

$pluginSource = (Resolve-Path (Join-Path $PSScriptRoot "..\integrations\$PluginId")).Path
$pluginParent = Join-Path $resolvedStateDir "plugin-dev"
$pluginStage = Assert-ChildPath -Path (Join-Path $pluginParent $PluginId) -Parent $resolvedStateDir -Label "Plugin stage"
$pluginNext = Assert-ChildPath -Path "$pluginStage.next" -Parent $resolvedStateDir -Label "Plugin next stage"
$pluginPrevious = Assert-ChildPath -Path "$pluginStage.previous" -Parent $resolvedStateDir -Label "Plugin previous stage"
$transactionId = [Guid]::NewGuid().ToString("N")
$localConfigPath = Assert-ChildPath -Path (Join-Path $resolvedStateDir "openclaw.json") -Parent $resolvedStateDir -Label "Local config"
$localConfigBackup = Assert-ChildPath -Path "$localConfigPath.rollback.$transactionId" -Parent $resolvedStateDir -Label "Local config backup"
$gatewayConfigPath = "/home/openclaw/.openclaw/openclaw.json"
$gatewayConfigBackup = "$gatewayConfigPath.rollback.$transactionId"
$gatewayPluginStage = "/home/openclaw/.openclaw/plugin-dev/$PluginId"
$gatewayPluginBackup = "$gatewayPluginStage.rollback.$transactionId"
$voiceSessionKey = "agent:${AgentId}:voice-android-$($AndroidNodeId.Substring(0, 32))"
$pluginStageExisted = Test-Path -LiteralPath $pluginStage
$localConfigExisted = Test-Path -LiteralPath $localConfigPath -PathType Leaf
$taskStopped = $false
$promoted = $false
$localSnapshotReady = $false
$gatewaySnapshotReady = $false
$gatewayPluginExisted = $false
$rollbackFailed = $false

try {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    $taskStopped = $true
    if ($localConfigExisted) {
        Copy-Item -LiteralPath $localConfigPath -Destination $localConfigBackup -Force
    }
    $localSnapshotReady = $true

$gatewaySnapshot = @"
set -euo pipefail
trap 'rm -rf "$gatewayConfigBackup" "$gatewayPluginBackup"' ERR
test -f '$gatewayConfigPath'
cp -a '$gatewayConfigPath' '$gatewayConfigBackup'
rm -rf '$gatewayPluginBackup'
if [ -e '$gatewayPluginStage' ]; then
  cp -a '$gatewayPluginStage' '$gatewayPluginBackup'
  printf 'present'
else
  printf 'absent'
fi
"@
    $gatewayPluginExisted = (((Invoke-GatewayBash -Command $gatewaySnapshot) -join "").Trim() -eq "present")
    $gatewaySnapshotReady = $true

    foreach ($path in @($pluginNext, $pluginPrevious)) {
        if (Test-Path -LiteralPath $path) {
            Remove-Item -LiteralPath $path -Recurse -Force
        }
    }

    New-Item -ItemType Directory -Path $pluginNext -Force | Out-Null
    foreach ($entry in @("package.json", "openclaw.plugin.json", "README.md", "dist", "test")) {
        Copy-Item -LiteralPath (Join-Path $pluginSource $entry) -Destination $pluginNext -Recurse -Force
    }
    Push-Location $pluginNext
    try {
        & npm.cmd test
        if ($LASTEXITCODE -ne 0) { throw "Windows node plugin tests failed." }
    } finally {
        Pop-Location
    }

    if (Test-Path -LiteralPath $pluginStage) {
        Move-Item -LiteralPath $pluginStage -Destination $pluginPrevious
    }
    Move-Item -LiteralPath $pluginNext -Destination $pluginStage
    $promoted = $true

    $env:OPENCLAW_STATE_DIR = $resolvedStateDir
    $pluginList = ((Invoke-LocalOpenClaw plugins list --json) -join "`n") | ConvertFrom-Json
    $existing = @($pluginList.plugins | Where-Object id -eq $PluginId)
    if ($existing.Count -eq 0) {
        Invoke-LocalOpenClaw plugins install --link $pluginStage | Out-Null
    } elseif ($existing.Count -ne 1 -or $existing[0].rootDir -ne $pluginStage) {
        throw "Plugin '$PluginId' is registered from an unexpected path in the dedicated node state."
    }
    Invoke-LocalOpenClaw plugins enable $PluginId | Out-Null

    $nodeConfig = [ordered]@{
        windowsNodeEnabled = $true
        windowsNodeId = $NodeId
        windowsVoiceSessionKey = $voiceSessionKey
        windowsBrokerKeyId = $BrokerKeyId
        windowsBrokerPublicKeyBase64Url = $BrokerPublicKeyBase64Url
        windowsReadRoots = [ordered]@{ documents = $resolvedReadRoot }
    }
    foreach ($entry in $nodeConfig.GetEnumerator()) {
        $json = $entry.Value | ConvertTo-Json -Compress -Depth 6
        Invoke-LocalOpenClaw config set "plugins.entries.$PluginId.config.$($entry.Key)" $json --strict-json | Out-Null
    }
    Invoke-LocalOpenClaw config set nodeHost.browserProxy.enabled false --strict-json | Out-Null
    Invoke-LocalOpenClaw config validate | Out-Null
    $configuredVoiceSessionKey = ((Invoke-LocalOpenClaw config get "plugins.entries.$PluginId.config.windowsVoiceSessionKey") -join "`n").Trim().Trim('"')
    if ($configuredVoiceSessionKey -ne $voiceSessionKey) {
        throw "Dedicated Windows node voice-session binding does not match the Android session key."
    }

    & (Join-Path $PSScriptRoot "configure-assistant-capability-broker.ps1") -Distro $Distro

    $gatewayNodes = ((Invoke-GatewayOpenClaw config get gateway.nodes) -join "`n") | ConvertFrom-Json
    $allowCommands = @(@($gatewayNodes.allowCommands) + $WindowsCommand) |
        Where-Object { $_ -notin $RestrictedNodeCommands } |
        Sort-Object -Unique
    $denyCommands = @(@($gatewayNodes.denyCommands) + $RestrictedNodeCommands) | Sort-Object -Unique
    Invoke-GatewayOpenClaw config set gateway.nodes.allowCommands ($allowCommands | ConvertTo-Json -Compress) --strict-json | Out-Null
    Invoke-GatewayOpenClaw config set gateway.nodes.denyCommands ($denyCommands | ConvertTo-Json -Compress) --strict-json | Out-Null

    $brokerPrefix = "plugins.entries.$PluginId.config"
    Invoke-GatewayOpenClaw config set "$brokerPrefix.windowsFileSearchEnabled" true --strict-json | Out-Null
    Invoke-GatewayOpenClaw config set "$brokerPrefix.windowsFileReadEnabled" true --strict-json | Out-Null
    Invoke-GatewayOpenClaw config set "$brokerPrefix.windowsFileAgentId" ('"' + $AgentId + '"') --strict-json | Out-Null
    Invoke-GatewayOpenClaw config set "$brokerPrefix.windowsNodeId" ('"' + $NodeId + '"') --strict-json | Out-Null

    $configuredAgents = @((((Invoke-GatewayOpenClaw config get agents.list) -join "`n") | ConvertFrom-Json))
    $agentIndex = -1
    for ($index = 0; $index -lt $configuredAgents.Count; $index++) {
        if ([string]$configuredAgents[$index].id -eq $AgentId) { $agentIndex = $index; break }
    }
    if ($agentIndex -lt 0) { throw "Agent '$AgentId' is missing from agents.list." }
    $alsoAllow = @(@($configuredAgents[$agentIndex].tools.alsoAllow) + $WindowsTools) | Sort-Object -Unique
    Invoke-GatewayOpenClaw config set "agents.list[$agentIndex].tools.alsoAllow" ($alsoAllow | ConvertTo-Json -Compress) --strict-json | Out-Null
    Invoke-GatewayOpenClaw config validate | Out-Null
    Invoke-GatewayOpenClaw gateway restart | Out-Null

    Start-ScheduledTask -TaskName $TaskName
    $taskStopped = $false
    Start-Sleep -Seconds 5

    $status = ((Invoke-GatewayOpenClaw nodes status --json) -join "`n") | ConvertFrom-Json
    $node = @($status.nodes | Where-Object nodeId -eq $NodeId)
    if ($node.Count -ne 1 -or $node[0].connected -ne $true) {
        throw "Dedicated Windows node did not reconnect after provisioning. Check 'openclaw nodes pending'."
    }
    $commands = @($node[0].commands | Sort-Object -Unique)
    if ($commands.Count -ne 1 -or $commands[0] -ne $WindowsCommand) {
        throw "Dedicated Windows node exposed an unexpected command surface: $($commands -join ', ')."
    }

    if (Test-Path -LiteralPath $pluginPrevious) {
        Remove-Item -LiteralPath $pluginPrevious -Recurse -Force
    }
    Write-Host "Windows file bridge is active for '$AgentId' on node $NodeId with root alias 'documents'."
} catch {
    $provisioningError = $_
    $rollbackError = $null
    try {
        Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        $taskStopped = $true
        if ($promoted) {
            if (Test-Path -LiteralPath $pluginStage) {
                Remove-Item -LiteralPath $pluginStage -Recurse -Force
            }
            if ($pluginStageExisted -and (Test-Path -LiteralPath $pluginPrevious)) {
                Move-Item -LiteralPath $pluginPrevious -Destination $pluginStage
            }
        }

        if ($localSnapshotReady) {
            if ($localConfigExisted) {
                if (-not (Test-Path -LiteralPath $localConfigBackup -PathType Leaf)) {
                    throw "Local config rollback snapshot is missing."
                }
                Copy-Item -LiteralPath $localConfigBackup -Destination $localConfigPath -Force
            } elseif (Test-Path -LiteralPath $localConfigPath) {
                Remove-Item -LiteralPath $localConfigPath -Force
            }
        }

        if ($gatewaySnapshotReady) {
            $restoreGateway = @"
set -euo pipefail
test -f '$gatewayConfigBackup'
rm -rf '$gatewayPluginStage' '$gatewayPluginStage.next' '$gatewayPluginStage.previous'
if [ '$($gatewayPluginExisted.ToString().ToLowerInvariant())' = 'true' ]; then
  test -d '$gatewayPluginBackup'
  mv '$gatewayPluginBackup' '$gatewayPluginStage'
fi
cp -a '$gatewayConfigBackup' '$gatewayConfigPath'
"@
            Invoke-GatewayBash -Command $restoreGateway | Out-Null
            Invoke-GatewayOpenClaw config validate | Out-Null
            Invoke-GatewayOpenClaw gateway restart | Out-Null
        }
    } catch {
        $rollbackError = $_
    }

    if ($rollbackError) {
        $rollbackFailed = $true
        throw "Provisioning failed: $($provisioningError.Exception.Message) Rollback also failed: $($rollbackError.Exception.Message)"
    }
    throw $provisioningError
} finally {
    if (-not $rollbackFailed -and (Test-Path -LiteralPath $localConfigBackup)) {
        Remove-Item -LiteralPath $localConfigBackup -Force -ErrorAction SilentlyContinue
    }
    if ($gatewaySnapshotReady -and -not $rollbackFailed) {
        try {
            Invoke-GatewayBash -Command "rm -rf '$gatewayConfigBackup' '$gatewayPluginBackup'" | Out-Null
        } catch {
            Write-Warning "Could not remove Gateway rollback snapshots: $($_.Exception.Message)"
        }
    }
    if (Test-Path -LiteralPath $pluginNext) {
        Remove-Item -LiteralPath $pluginNext -Recurse -Force -ErrorAction SilentlyContinue
    }
    if ($taskStopped) {
        Start-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    }
}
