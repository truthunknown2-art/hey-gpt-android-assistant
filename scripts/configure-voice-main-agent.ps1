[CmdletBinding()]
param(
    [string]$Distro = "OpenClawGateway",
    [string]$AgentId = "voice-main",
    [string]$Model = "openai/gpt-5.6-sol",
    [string]$NodeId = ""
)

$ErrorActionPreference = "Stop"

$PluginId = "voice-assistant-tools"
$MediaCommand = "media.play_search"
$MessengerCommand = "notifications.list_package"
$ExpectedTools = @(
    "android_media_play",
    "messenger_notifications_read",
    "web_fetch",
    "web_search"
) | Sort-Object

function Invoke-OpenClaw {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    $quotedArguments = @($Arguments | ForEach-Object {
        "'" + $_.Replace("'", "'\''") + "'"
    })
    $command = "openclaw " + ($quotedArguments -join " ")
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($command))
    $runner = "printf '%s' '$encoded' | base64 -d | bash"
    $output = & wsl.exe -d $Distro -- bash -lc $runner
    if ($LASTEXITCODE -ne 0) {
        throw "OpenClaw command failed: openclaw $($Arguments -join ' ')"
    }
    return $output
}

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

function Resolve-AssistantNodeId {
    param([string]$RequestedNodeId)

    $eligible = @()
    for ($attempt = 0; $attempt -lt 15; $attempt++) {
        $status = ((Invoke-OpenClaw nodes status --json) -join "`n") | ConvertFrom-Json
        $eligible = @($status.nodes | Where-Object {
            $_.connected -eq $true -and
            @($_.commands) -contains $MediaCommand -and
            @($_.commands) -contains $MessengerCommand
        })
        if ($eligible.Count -gt 0) { break }
        Start-Sleep -Seconds 2
    }

    if ($RequestedNodeId) {
        $matches = @($eligible | Where-Object nodeId -eq $RequestedNodeId)
        if ($matches.Count -ne 1) {
            throw "Configured node '$RequestedNodeId' is not the single connected node declaring both safe voice commands."
        }
        return $RequestedNodeId
    }

    if ($eligible.Count -ne 1) {
        $labels = @($eligible | ForEach-Object { "$($_.displayName) [$($_.nodeId)]" }) -join ", "
        throw "Expected exactly one connected Android assistant node declaring both safe commands; found $($eligible.Count): $labels"
    }
    return [string]$eligible[0].nodeId
}

function Install-VoiceToolsPlugin {
    $pluginWindowsPath = (Resolve-Path (Join-Path $PSScriptRoot "..\integrations\openclaw-voice-tools")).Path
    $drive = $pluginWindowsPath.Substring(0, 1).ToLowerInvariant()
    $relativePath = $pluginWindowsPath.Substring(2).Replace('\', '/')
    $pluginWslPath = "/mnt/$drive$relativePath"

    $escapedPath = "'" + $pluginWslPath.Replace("'", "'\''") + "'"
    $stagingPath = "/home/openclaw/.openclaw/plugin-dev/$PluginId"
    $nodeBin = "/home/openclaw/.openclaw/tools/node/bin"
    $openClawBin = "/home/openclaw/.openclaw/bin"
    $stageCommand = @"
set -euo pipefail
rm -rf '$stagingPath'
mkdir -p '$stagingPath'
cp -a $escapedPath/package.json $escapedPath/openclaw.plugin.json $escapedPath/README.md '$stagingPath/'
cp -a $escapedPath/dist $escapedPath/test '$stagingPath/'
find '$stagingPath' -type d -exec chmod 755 {} +
find '$stagingPath' -type f -exec chmod 644 {} +
export PATH="${nodeBin}:${openClawBin}:`$PATH"
cd '$stagingPath'
npm test
"@
    Invoke-WslBash $stageCommand | Out-Null

    $pluginList = ((Invoke-OpenClaw plugins list --json) -join "`n") | ConvertFrom-Json
    $existing = @($pluginList.plugins | Where-Object id -eq $PluginId)
    if ($existing.Count -eq 0) {
        Invoke-OpenClaw plugins install --link $stagingPath | Out-Null
    } elseif ($existing.Count -ne 1 -or $existing[0].rootDir -ne $stagingPath) {
        throw "Plugin '$PluginId' is already registered from an unexpected path."
    }
    Invoke-OpenClaw plugins enable $PluginId | Out-Null
}

function Add-VoicePolicy {
    param([string]$Workspace)

    $agentsPath = "$Workspace/AGENTS.md"
    $current = (& wsl.exe -d $Distro -- cat $agentsPath) -join "`n"
    if ($LASTEXITCODE -ne 0) {
        throw "Could not read $agentsPath"
    }

    $policy = @'
## Ambient Voice Policy

This agent is activated by a nearby wake phrase while the phone is unlocked. Keep replies concise and conversational.

- Use `web_search` and `web_fetch` for read-only web questions.
- Use `android_media_play` for Spotify playback. The tool owns the phone identity, command, and package selection.
- Use `messenger_notifications_read` only when the user asks about Messenger notifications. It returns a privacy-minimized, read-only payload.
- Never send or reply to messages, call anyone, purchase, post, upload, submit forms, change account or device settings, administer the Gateway, or look for a workaround when a capability is unavailable.
- Do not claim an action succeeded unless the corresponding tool returned success.
'@

    $marker = "## Ambient Voice Policy"
    $markerIndex = $current.IndexOf($marker, [StringComparison]::Ordinal)
    $base = if ($markerIndex -ge 0) {
        $current.Substring(0, $markerIndex).TrimEnd()
    } else {
        $current.TrimEnd()
    }
    $merged = $base + "`n`n" + $policy.Trim() + "`n"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($merged))
    Invoke-WslBash "printf '%s' '$encoded' | base64 -d > '$agentsPath'" | Out-Null
}

if ($NodeId) {
    if ($NodeId -notmatch '^[a-f0-9]{64}$') {
        throw "NodeId must be exactly 64 lowercase hexadecimal characters."
    }
    $preflightNodeId = '"' + $NodeId + '"'
    Invoke-OpenClaw config set "plugins.entries.$PluginId.config.nodeId" $preflightNodeId --strict-json | Out-Null
}

$nodeConfig = ((Invoke-OpenClaw config get gateway.nodes) -join "`n") | ConvertFrom-Json
$dangerousCaptureCommands = @("camera.clip", "camera.snap", "screen.record")
$allowCommands = @(@($nodeConfig.allowCommands) + $MediaCommand + $MessengerCommand) |
    Where-Object { $_ -notin $dangerousCaptureCommands } |
    Sort-Object -Unique
$denyCommands = @(@($nodeConfig.denyCommands) + @(
    "calendar.add",
    "contacts.add",
    "notifications.actions",
    "sms.send"
)) | Sort-Object -Unique
Invoke-OpenClaw config set gateway.nodes.allowCommands ($allowCommands | ConvertTo-Json -Compress) --strict-json | Out-Null
Invoke-OpenClaw config set gateway.nodes.denyCommands ($denyCommands | ConvertTo-Json -Compress) --strict-json | Out-Null
Invoke-OpenClaw config validate | Out-Null
Invoke-OpenClaw gateway restart | Out-Null

$resolvedNodeId = Resolve-AssistantNodeId -RequestedNodeId $NodeId
$quotedNodeId = '"' + $resolvedNodeId + '"'
Invoke-OpenClaw config set "plugins.entries.$PluginId.config.nodeId" $quotedNodeId --strict-json | Out-Null
Install-VoiceToolsPlugin

$parsedAgents = ((Invoke-OpenClaw agents list --json) -join "`n") | ConvertFrom-Json
$agents = @($parsedAgents | ForEach-Object { $_ })
$workspace = "/home/openclaw/.openclaw/workspace-$AgentId"
if (@($agents | ForEach-Object { [string]$_.id }) -notcontains $AgentId) {
    Invoke-OpenClaw agents add $AgentId `
        --workspace $workspace `
        --model $Model `
        --non-interactive `
        --json | Out-Null
}

$parsedConfiguredAgents = ((Invoke-OpenClaw config get agents.list) -join "`n") | ConvertFrom-Json
$configuredAgents = @($parsedConfiguredAgents | ForEach-Object { $_ })
$agentIndex = -1
for ($index = 0; $index -lt $configuredAgents.Count; $index++) {
    if ([string]$configuredAgents[$index].id -eq $AgentId) {
        $agentIndex = $index
        break
    }
}
if ($agentIndex -lt 0) {
    throw "Agent '$AgentId' was created but is missing from agents.list."
}

$alsoAllowJson = $ExpectedTools | ConvertTo-Json -Compress
$deny = @(
    "agents_list",
    "browser",
    "canvas",
    "codex_threads",
    "cron",
    "gateway",
    "group:fs",
    "group:memory",
    "group:messaging",
    "group:nodes",
    "group:runtime",
    "image_generate",
    "memory_get",
    "memory_search",
    "message",
    "music_generate",
    "nodes",
    "session_status",
    "sessions_history",
    "sessions_list",
    "sessions_send",
    "sessions_spawn",
    "sessions_yield",
    "subagents",
    "video_generate"
) | Sort-Object -Unique

Invoke-OpenClaw config set "agents.list[$agentIndex].model" $Model | Out-Null
Invoke-OpenClaw config set "agents.list[$agentIndex].tools.profile" minimal | Out-Null
Invoke-OpenClaw config set "agents.list[$agentIndex].tools.alsoAllow" $alsoAllowJson --strict-json | Out-Null
Invoke-OpenClaw config set "agents.list[$agentIndex].tools.deny" ($deny | ConvertTo-Json -Compress) --strict-json | Out-Null
Invoke-OpenClaw config set "agents.list[$agentIndex].skills" '[]' --strict-json | Out-Null
Invoke-OpenClaw config set tools.web.search.enabled true --strict-json | Out-Null
Invoke-OpenClaw config set tools.web.search.openaiCodex.enabled true --strict-json | Out-Null
Invoke-OpenClaw config set tools.web.search.provider '"codex"' --strict-json | Out-Null
Add-VoicePolicy -Workspace $workspace
Invoke-OpenClaw config validate | Out-Null
Invoke-OpenClaw gateway restart | Out-Null

$runtimePlugin = ((Invoke-OpenClaw plugins inspect $PluginId --runtime --json) -join "`n") | ConvertFrom-Json
$registeredTools = @($runtimePlugin.plugin.toolNames | ForEach-Object { [string]$_ }) |
    Sort-Object -Unique
$missingPluginTools = @("android_media_play", "messenger_notifications_read") |
    Where-Object { $_ -notin $registeredTools }
if ($missingPluginTools.Count -gt 0) {
    throw "Voice tools plugin did not register: $($missingPluginTools -join ', ')."
}

$policyCheckKey = "agent:${AgentId}:policy-check-$([guid]::NewGuid().ToString('N'))"
if (-not $policyCheckKey.StartsWith("agent:${AgentId}:")) {
    throw "Voice-main policy check session key has the wrong agent prefix."
}
$smoke = ((Invoke-OpenClaw agent --agent $AgentId `
    --session-key $policyCheckKey --timeout 60 `
    --message "Reply with exactly: voice main policy ready" --json) -join "`n") | ConvertFrom-Json
$effectiveModel = "$($smoke.result.meta.agentMeta.provider)/$($smoke.result.meta.agentMeta.model)"
$effectiveTools = @($smoke.result.meta.systemPromptReport.tools.entries | ForEach-Object name) |
    Sort-Object -Unique

if ($effectiveModel -ne $Model) {
    throw "Voice-main smoke test used '$effectiveModel' instead of '$Model'."
}
$toolDifference = @(Compare-Object -ReferenceObject $ExpectedTools -DifferenceObject $effectiveTools)
if ($toolDifference.Count -gt 0) {
    throw "Voice-main effective tools are not exact. Expected [$($ExpectedTools -join ', ')], got [$($effectiveTools -join ', ')]."
}

Write-Host "Voice agent '$AgentId' is ready on $Distro with model $Model, node $resolvedNodeId, and exact tools: $($effectiveTools -join ', ')."
