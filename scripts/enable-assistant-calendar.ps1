[CmdletBinding()]
param(
    [string]$Distro = "OpenClawGateway",
    [string]$AgentId = "voice-main",
    [string]$Model = "openai/gpt-5.6-luna",
    [string]$NodeId = "",
    [switch]$Disable
)

$ErrorActionPreference = "Stop"
$PluginId = "assistant-capability-broker"
$CalendarReadTool = "assistant_calendar_next"
$CalendarCreateTool = "assistant_calendar_create"
$MessengerReadTool = "messenger_notifications_read"
$PresenceCommand = "assistant.presence.v1"
$ExecuteCommand = "assistant.execute.v1"
$BaseVoiceTools = @(
    "android_media_play",
    "web_fetch",
    "web_search"
)
$BrokerTools = @(
    "assistant_contacts_search",
    "assistant_phone_call",
    "assistant_sms_send",
    $CalendarReadTool,
    $CalendarCreateTool,
    $MessengerReadTool,
    "assistant_memory_remember",
    "assistant_memory_forget"
)
$GenericToolDeny = @(
    "agents_list", "browser", "canvas", "codex_threads", "cron", "gateway",
    "group:fs", "group:memory", "group:messaging", "group:nodes", "group:runtime",
    "image_generate", "memory_get", "memory_search", "message", "music_generate", "nodes", "session_status",
    "sessions_history", "sessions_list", "sessions_send", "sessions_spawn",
    "sessions_yield", "subagents", "video_generate"
)

function Invoke-WslBash {
    param([string]$Command)

    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Command))
    $runner = "printf '%s' '$encoded' | base64 -d | bash"
    $output = & wsl.exe -d $Distro -- bash -lc $runner
    if ($LASTEXITCODE -ne 0) { throw "WSL command failed: $Command" }
    return $output
}

function Invoke-OpenClaw {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    $quotedArguments = @($Arguments | ForEach-Object {
        "'" + $_.Replace("'", "'\''") + "'"
    })
    $command = "openclaw " + ($quotedArguments -join " ")
    return Invoke-WslBash "export PATH='/home/openclaw/.openclaw/tools/node/bin:/home/openclaw/.openclaw/bin':`$PATH; $command"
}

function Resolve-AssistantNodeId {
    param([string]$RequestedNodeId)

    $eligible = @()
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $status = ((Invoke-OpenClaw nodes status --json) -join "`n") | ConvertFrom-Json
        $eligible = @($status.nodes | Where-Object {
            $_.connected -eq $true -and
            @($_.commands) -contains $PresenceCommand -and
            @($_.commands) -contains $ExecuteCommand
        })
        if ($eligible.Count -gt 0) { break }
        Start-Sleep -Seconds 2
    }
    if ($RequestedNodeId) {
        if ($RequestedNodeId -notmatch '^[a-f0-9]{64}$') {
            throw "NodeId must be exactly 64 lowercase hexadecimal characters."
        }
        $matches = @($eligible | Where-Object nodeId -eq $RequestedNodeId)
        if ($matches.Count -ne 1) {
            throw "Configured node '$RequestedNodeId' is not connected with both signed assistant commands."
        }
        return $RequestedNodeId
    }
    if ($eligible.Count -ne 1) {
        throw "Expected one connected Android node with both signed assistant commands; found $($eligible.Count)."
    }
    return [string]$eligible[0].nodeId
}

function Set-CalendarPolicy {
    param([string]$Workspace, [bool]$Enabled)

    $agentsPath = "$Workspace/AGENTS.md"
    $current = (& wsl.exe -d $Distro -- cat $agentsPath) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Could not read $agentsPath" }
    $startMarker = "<!-- signed-private-calendar-policy:start -->"
    $endMarker = "<!-- signed-private-calendar-policy:end -->"
    $startIndex = $current.IndexOf($startMarker, [StringComparison]::Ordinal)
    $endIndex = $current.IndexOf($endMarker, [StringComparison]::Ordinal)
    if (($startIndex -ge 0) -ne ($endIndex -ge 0) -or ($startIndex -ge 0 -and $endIndex -lt $startIndex)) {
        throw "Existing signed calendar policy markers are malformed."
    }
    if ($startIndex -ge 0) {
        $before = $current.Substring(0, $startIndex).TrimEnd()
        $after = $current.Substring($endIndex + $endMarker.Length).TrimStart()
        $current = $before + $(if ($after) { "`n`n" + $after } else { "" })
    }
    if ($Enabled) {
        $policy = @'
## Signed Private Calendar

- Use `assistant_calendar_next` for upcoming calendar questions. Event titles and times are spoken only by the unlocked phone and never returned to this model. Do not ask for or invent those details after the tool runs.
- The first calendar read in a voice session requires a phone approval. Only `COMPLETED` with `privateDelivery=spoken_on_phone` proves local speech finished.
- Use `assistant_calendar_create` only when the user explicitly asks to add an event. Resolve the intended local date and time before calling it; use an exclusive end date for all-day events.
- The phone chooses the writable calendar locally and shows the real calendar, title, and local schedule in a fresh one-shot approval. Only `created=true` proves insertion.
- Never use raw `calendar.add`, generic node, filesystem, browser-control, or shell tools as a workaround.
'@
        $current = $current.TrimEnd() + "`n`n$startMarker`n$($policy.Trim())`n$endMarker"
    }
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($current.TrimEnd() + "`n"))
    Invoke-WslBash "printf '%s' '$encoded' | base64 -d > '$agentsPath'" | Out-Null
}

& (Join-Path $PSScriptRoot "configure-assistant-capability-broker.ps1") -Distro $Distro

$configuredAgents = @((((Invoke-OpenClaw config get agents.list) -join "`n") | ConvertFrom-Json) | ForEach-Object { $_ })
$agentIndex = -1
for ($index = 0; $index -lt $configuredAgents.Count; $index++) {
    if ([string]$configuredAgents[$index].id -eq $AgentId) {
        $agentIndex = $index
        break
    }
}
if ($agentIndex -lt 0) { throw "Agent '$AgentId' is missing from agents.list." }

if ($Disable) {
    Invoke-OpenClaw config set "plugins.entries.$PluginId.config.calendarReadsEnabled" false --strict-json | Out-Null
    Invoke-OpenClaw config set "plugins.entries.$PluginId.config.calendarWritesEnabled" false --strict-json | Out-Null
    $remainingConfig = ((Invoke-OpenClaw config get "plugins.entries.$PluginId.config") -join "`n") | ConvertFrom-Json
    $otherAssistantCapabilitiesEnabled =
        $remainingConfig.privateReadsEnabled -eq $true -or
        $remainingConfig.phoneCallsEnabled -eq $true -or
        $remainingConfig.smsSendEnabled -eq $true -or
        $remainingConfig.messengerReadsEnabled -eq $true
    $nodeConfig = ((Invoke-OpenClaw config get gateway.nodes) -join "`n") | ConvertFrom-Json
    $allowCommands = if ($otherAssistantCapabilitiesEnabled) {
        @(@($nodeConfig.allowCommands) + $PresenceCommand + $ExecuteCommand) | Sort-Object -Unique
    } else {
        @($nodeConfig.allowCommands | Where-Object { $_ -notin @($PresenceCommand, $ExecuteCommand) }) | Sort-Object -Unique
    }
    Invoke-OpenClaw config set gateway.nodes.allowCommands ($allowCommands | ConvertTo-Json -Compress) --strict-json | Out-Null
    $existingAllow = @($configuredAgents[$agentIndex].tools.alsoAllow | ForEach-Object { [string]$_ })
    $existingDeny = @($configuredAgents[$agentIndex].tools.deny | ForEach-Object { [string]$_ })
    $nextAllow = @($existingAllow | Where-Object { $_ -notin @($CalendarReadTool, $CalendarCreateTool) }) | Sort-Object -Unique
    $nextDeny = @($existingDeny + $CalendarReadTool + $CalendarCreateTool) | Sort-Object -Unique
    Invoke-OpenClaw config set "agents.list[$agentIndex].tools.alsoAllow" ($nextAllow | ConvertTo-Json -Compress) --strict-json | Out-Null
    Invoke-OpenClaw config set "agents.list[$agentIndex].tools.deny" ($nextDeny | ConvertTo-Json -Compress) --strict-json | Out-Null
} else {
    $nodeConfig = ((Invoke-OpenClaw config get gateway.nodes) -join "`n") | ConvertFrom-Json
    $allowCommands = @(@($nodeConfig.allowCommands) + $PresenceCommand + $ExecuteCommand) | Sort-Object -Unique
    $denyCommands = @(@($nodeConfig.denyCommands) + "calendar.add") |
        Where-Object { $_ -notin @($PresenceCommand, $ExecuteCommand) } |
        Sort-Object -Unique
    Invoke-OpenClaw config set gateway.nodes.allowCommands ($allowCommands | ConvertTo-Json -Compress) --strict-json | Out-Null
    Invoke-OpenClaw config set gateway.nodes.denyCommands ($denyCommands | ConvertTo-Json -Compress) --strict-json | Out-Null
    Invoke-OpenClaw config validate | Out-Null
    Invoke-OpenClaw gateway restart | Out-Null
    $resolvedNodeId = Resolve-AssistantNodeId -RequestedNodeId $NodeId
    Invoke-OpenClaw config set "plugins.entries.$PluginId.config.androidNodeId" ('"' + $resolvedNodeId + '"') --strict-json | Out-Null
    Invoke-OpenClaw config set "plugins.entries.$PluginId.config.privateReadAgentId" ('"' + $AgentId + '"') --strict-json | Out-Null
    Invoke-OpenClaw config set "plugins.entries.$PluginId.config.calendarReadsEnabled" true --strict-json | Out-Null
    Invoke-OpenClaw config set "plugins.entries.$PluginId.config.calendarWritesEnabled" true --strict-json | Out-Null
}

$agents = @((((Invoke-OpenClaw agents list --json) -join "`n") | ConvertFrom-Json) | ForEach-Object { $_ })
$agent = @($agents | Where-Object id -eq $AgentId)
if ($agent.Count -ne 1 -or -not [string]$agent[0].workspace) {
    throw "Expected one runtime agent named '$AgentId' with a trusted workspace."
}
Set-CalendarPolicy -Workspace ([string]$agent[0].workspace) -Enabled (-not $Disable)

Invoke-OpenClaw config validate | Out-Null
Invoke-OpenClaw gateway restart | Out-Null

$runtime = ((Invoke-OpenClaw plugins inspect $PluginId --runtime --json) -join "`n") | ConvertFrom-Json
$registered = @($runtime.plugin.toolNames | ForEach-Object { [string]$_ }) | Sort-Object -Unique
if (-not $Disable -and $CalendarReadTool -notin $registered) { throw "Broker did not register '$CalendarReadTool'." }
if (-not $Disable -and $CalendarCreateTool -notin $registered) { throw "Broker did not register '$CalendarCreateTool'." }
if ($Disable -and $CalendarReadTool -in $registered) { throw "Broker still registered '$CalendarReadTool' after disable." }
if ($Disable -and $CalendarCreateTool -in $registered) { throw "Broker still registered '$CalendarCreateTool' after disable." }

if (-not $Disable) {
    $brokerVoiceTools = @($registered | Where-Object { $_ -in $BrokerTools })
    $registeredMemoryTools = @($brokerVoiceTools | Where-Object {
        $_ -in @("assistant_memory_remember", "assistant_memory_forget")
    })
    if ($registeredMemoryTools.Count -notin @(0, 2)) {
        throw "Broker registered an incomplete explicit-memory tool pair."
    }
    $memoryTools = if ($registeredMemoryTools.Count -eq 2) { @("memory_get", "memory_search") } else { @() }
    $expectedTools = @($BaseVoiceTools + $brokerVoiceTools + $memoryTools) | Sort-Object -Unique
    $deny = @($GenericToolDeny | Where-Object {
        $_ -notin $expectedTools -and
        -not ($_ -eq "group:memory" -and "memory_search" -in $expectedTools)
    }) | Sort-Object -Unique
    Invoke-OpenClaw config set "agents.list[$agentIndex].tools.profile" minimal | Out-Null
    Invoke-OpenClaw config set "agents.list[$agentIndex].tools.alsoAllow" ($expectedTools | ConvertTo-Json -Compress) --strict-json | Out-Null
    Invoke-OpenClaw config set "agents.list[$agentIndex].tools.deny" ($deny | ConvertTo-Json -Compress) --strict-json | Out-Null
    Invoke-OpenClaw config set "agents.list[$agentIndex].model" $Model | Out-Null
    Invoke-OpenClaw config validate | Out-Null
    Invoke-OpenClaw gateway restart | Out-Null

    $policyCheckKey = "agent:${AgentId}:calendar-policy-$([guid]::NewGuid().ToString('N'))"
    $smoke = ((Invoke-OpenClaw agent --agent $AgentId --session-key $policyCheckKey --timeout 60 `
        --message "Reply with exactly: signed calendar policy ready" --json) -join "`n") | ConvertFrom-Json
    $effectiveModel = "$($smoke.result.meta.agentMeta.provider)/$($smoke.result.meta.agentMeta.model)"
    $effectiveTools = @($smoke.result.meta.systemPromptReport.tools.entries | ForEach-Object name) | Sort-Object -Unique
    if ($effectiveModel -ne $Model) { throw "Voice smoke used '$effectiveModel' instead of '$Model'." }
    if (@(Compare-Object -ReferenceObject $expectedTools -DifferenceObject $effectiveTools).Count -gt 0) {
        throw "Voice-main tool surface is not exact. Expected [$($expectedTools -join ', ')], got [$($effectiveTools -join ', ')]."
    }
    if (@($effectiveTools | Where-Object { $_ -in @("nodes", "browser", "exec") }).Count -gt 0) {
        throw "A generic control tool leaked into voice-main."
    }
}

$status = ((Invoke-OpenClaw gateway call assistant.broker.status --json) -join "`n") | ConvertFrom-Json
if ($status.modelToolsRegistered -ne $registered.Count) {
    throw "Broker status tool count does not match runtime registration."
}
$state = if ($Disable) { "disabled" } else { "enabled" }
Write-Host "Signed private calendar reads and one-shot approved calendar creation are $state for '$AgentId'."
Write-Host "Broker tools: $($registered -join ', ')."
