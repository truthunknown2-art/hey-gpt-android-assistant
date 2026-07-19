[CmdletBinding()]
param(
    [string]$Distro = "OpenClawGateway",
    [string]$AgentId = "voice-main",
    [string]$Model = "openai/gpt-5.6-luna",
    [string]$NodeId = ""
)

$ErrorActionPreference = "Stop"

$PluginId = "voice-assistant-tools"
$BrokerPluginId = "assistant-capability-broker"
$MediaCommand = "media.play_search"
$WindowsExecuteCommand = "assistant.windows.execute.v1"
$RestrictedNodeCommands = @(
    "browser.proxy",
    "system.execApprovals.get",
    "system.execApprovals.set",
    "system.run",
    "system.run.prepare",
    "system.which"
)
$ExpectedTools = @(
    "android_media_play",
    "web_fetch",
    "web_search"
) | Sort-Object

. (Join-Path $PSScriptRoot "lib\wsl-plugin-transfer.ps1")

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
            @($_.commands) -contains $MediaCommand
        })
        if ($eligible.Count -gt 0) { break }
        Start-Sleep -Seconds 2
    }

    if ($RequestedNodeId) {
        $matches = @($eligible | Where-Object nodeId -eq $RequestedNodeId)
        if ($matches.Count -ne 1) {
            throw "Configured node '$RequestedNodeId' is not the single connected node declaring the safe media command."
        }
        return $RequestedNodeId
    }

    if ($eligible.Count -ne 1) {
        $labels = @($eligible | ForEach-Object { "$($_.displayName) [$($_.nodeId)]" }) -join ", "
        throw "Expected exactly one connected Android assistant node declaring the safe media command; found $($eligible.Count): $labels"
    }
    return [string]$eligible[0].nodeId
}

function Install-VoiceToolsPlugin {
    $pluginWindowsPath = (Resolve-Path (Join-Path $PSScriptRoot "..\integrations\openclaw-voice-tools")).Path
    $stagingPath = "/home/openclaw/.openclaw/plugin-dev/$PluginId"
    $stagingNextPath = "$stagingPath.next"
    $nodeBin = "/home/openclaw/.openclaw/tools/node/bin"
    $openClawBin = "/home/openclaw/.openclaw/bin"
    Send-PluginBundleToWsl `
        -Distro $Distro `
        -SourcePath $pluginWindowsPath `
        -DestinationPath $stagingNextPath `
        -Entries @("package.json", "openclaw.plugin.json", "README.md", "dist", "test")

    $stageCommand = @"
set -euo pipefail
find '$stagingNextPath' -type d -exec chmod 755 {} +
find '$stagingNextPath' -type f -exec chmod 644 {} +
export PATH="${nodeBin}:${openClawBin}:`$PATH"
cd '$stagingNextPath'
npm test
rm -rf '$stagingPath.previous'
if [ -e '$stagingPath' ]; then mv '$stagingPath' '$stagingPath.previous'; fi
if ! mv '$stagingNextPath' '$stagingPath'; then
    if [ -e '$stagingPath.previous' ]; then mv '$stagingPath.previous' '$stagingPath'; fi
    exit 1
fi
rm -rf '$stagingPath.previous'
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

This agent is activated by a nearby wake phrase while the phone is unlocked. Keep replies concise and conversational. Prefer one or two sentences and under 45 spoken words unless the user asks for detail.

- Use `web_search` and `web_fetch` for read-only web questions.
- Use `android_media_play` for Spotify playback. For an exact track, first use `web_search` to find its public `open.spotify.com/track/` page, convert only the final 22-character ID to `spotify:track:ID`, and include `spotifyUri`, `title`, and `artist`. The tool owns the phone identity, command, and package selection. A launched request is not proof that playback started, so only say playback is confirmed when `playbackConfirmed` is true.
- When available, use `assistant_contacts_search` for contact lookups. Matching names and numbers are spoken privately on the unlocked phone; do not ask for or invent private fields after the tool returns its sanitized receipt.
- When available, use `assistant_phone_call` only after the user explicitly asks to call a named contact. The phone resolves the recipient privately and requires a fresh one-shot approval; never ask for or invent the phone number.
- When available, use `assistant_sms_send` only after the user explicitly asks to send a text to a named contact. Pass the exact intended message, and let the phone privately resolve and display the recipient and full text for a fresh one-shot approval. Never claim delivery; `sent=true` confirms carrier submission only.
- When available, use `assistant_calendar_next` for upcoming calendar questions. Titles and times are spoken privately by the unlocked phone; never ask for or invent those details after its sanitized receipt.
- When available, use `assistant_calendar_create` only after the user explicitly asks to add an event. Resolve the intended local date and time, then let the phone show the real calendar, title, and schedule for a fresh one-shot approval. Only claim success when `created=true`.
- When available, use `assistant_windows_files_search` only for an explicit question about files in the user-selected Windows roots. It returns opaque paths and metadata only, requires the current unlocked voice session, and cannot run shell commands.
- Use `assistant_windows_files_read` only with an opaque path returned by Windows file search. The phone must display and approve the first bounded content read in the voice session; never claim file contents when approval or the tool fails.
- Never send or reply to messages without `assistant_sms_send`, call anyone without `assistant_phone_call`, purchase, post, upload, submit forms, change account or device settings, administer the Gateway, or look for a workaround when a capability is unavailable.
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
$allowCommands = @(@($nodeConfig.allowCommands) + $MediaCommand) |
    Where-Object { $_ -ne "notifications.list_package" } |
    Where-Object { $_ -notin $dangerousCaptureCommands -and $_ -notin $RestrictedNodeCommands } |
    Sort-Object -Unique
$denyCommands = @(@($nodeConfig.denyCommands) + @(
    "calendar.add",
    "contacts.add",
    "notifications.actions",
    "sms.send"
) + $RestrictedNodeCommands) | Sort-Object -Unique
Invoke-OpenClaw config set gateway.nodes.allowCommands ($allowCommands | ConvertTo-Json -Compress) --strict-json | Out-Null
Invoke-OpenClaw config set gateway.nodes.denyCommands ($denyCommands | ConvertTo-Json -Compress) --strict-json | Out-Null
Invoke-OpenClaw config validate | Out-Null
Invoke-OpenClaw gateway restart | Out-Null

$resolvedNodeId = Resolve-AssistantNodeId -RequestedNodeId $NodeId
$quotedNodeId = '"' + $resolvedNodeId + '"'
Invoke-OpenClaw config set "plugins.entries.$PluginId.config.nodeId" $quotedNodeId --strict-json | Out-Null
Install-VoiceToolsPlugin

# Preserve explicitly enabled broker tools when this base provisioning script is rerun.
$pluginList = ((Invoke-OpenClaw plugins list --json) -join "`n") | ConvertFrom-Json
$brokerEntry = @($pluginList.plugins | Where-Object id -eq $BrokerPluginId)
if ($brokerEntry.Count -eq 1) {
    $brokerConfig = ((Invoke-OpenClaw config get "plugins.entries.$BrokerPluginId.config") -join "`n") | ConvertFrom-Json
    $memoryAgentId = if ([string]$brokerConfig.memoryAgentId) { [string]$brokerConfig.memoryAgentId } else { "voice-main" }
    $privateReadAgentId = if ([string]$brokerConfig.privateReadAgentId) { [string]$brokerConfig.privateReadAgentId } else { "voice-main" }
    if ($brokerConfig.memoryEnabled -eq $true -and $memoryAgentId -eq $AgentId) {
        $ExpectedTools = @($ExpectedTools + @(
            "assistant_memory_forget",
            "assistant_memory_remember",
            "memory_get",
            "memory_search"
        )) | Sort-Object -Unique
    }
    if ($brokerConfig.privateReadsEnabled -eq $true -and $privateReadAgentId -eq $AgentId) {
        $ExpectedTools = @($ExpectedTools + "assistant_contacts_search") | Sort-Object -Unique
    }
    if ($brokerConfig.phoneCallsEnabled -eq $true -and $privateReadAgentId -eq $AgentId) {
        $ExpectedTools = @($ExpectedTools + "assistant_phone_call") | Sort-Object -Unique
    }
    if ($brokerConfig.smsSendEnabled -eq $true -and $privateReadAgentId -eq $AgentId) {
        $ExpectedTools = @($ExpectedTools + "assistant_sms_send") | Sort-Object -Unique
    }
    if ($brokerConfig.calendarReadsEnabled -eq $true -and $privateReadAgentId -eq $AgentId) {
        $ExpectedTools = @($ExpectedTools + "assistant_calendar_next") | Sort-Object -Unique
    }
    if ($brokerConfig.calendarWritesEnabled -eq $true -and $privateReadAgentId -eq $AgentId) {
        $ExpectedTools = @($ExpectedTools + "assistant_calendar_create") | Sort-Object -Unique
    }
    if ($brokerConfig.messengerReadsEnabled -eq $true -and $privateReadAgentId -eq $AgentId) {
        $ExpectedTools = @($ExpectedTools + "messenger_notifications_read") | Sort-Object -Unique
    }
    $windowsFileAgentId = if ([string]$brokerConfig.windowsFileAgentId) { [string]$brokerConfig.windowsFileAgentId } else { "voice-main" }
    if ($brokerConfig.windowsFileSearchEnabled -eq $true -and $windowsFileAgentId -eq $AgentId) {
        $ExpectedTools = @($ExpectedTools + "assistant_windows_files_search") | Sort-Object -Unique
        if ($WindowsExecuteCommand -notin $allowCommands) {
            throw "Windows file search is enabled but '$WindowsExecuteCommand' is not in gateway.nodes.allowCommands."
        }
    }
    if ($brokerConfig.windowsFileReadEnabled -eq $true -and $windowsFileAgentId -eq $AgentId) {
        $ExpectedTools = @($ExpectedTools + "assistant_windows_files_read") | Sort-Object -Unique
    }
}

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
) | Where-Object {
    $_ -notin $ExpectedTools -and
    -not ($_ -eq "group:memory" -and "memory_search" -in $ExpectedTools)
} | Sort-Object -Unique

Invoke-OpenClaw config set "agents.list[$agentIndex].model" $Model | Out-Null
Invoke-OpenClaw config set "agents.list[$agentIndex].thinkingDefault" off | Out-Null
Invoke-OpenClaw config set "agents.list[$agentIndex].reasoningDefault" off | Out-Null
Invoke-OpenClaw config set "agents.list[$agentIndex].fastModeDefault" true --strict-json | Out-Null
Invoke-OpenClaw config set "agents.list[$agentIndex].memorySearch.provider" '"none"' --strict-json | Out-Null
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
$missingPluginTools = @("android_media_play") |
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
