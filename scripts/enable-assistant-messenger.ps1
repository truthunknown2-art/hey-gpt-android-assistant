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
$ToolName = "messenger_notifications_read"
$PresenceCommand = "assistant.presence.v1"
$ExecuteCommand = "assistant.execute.v1"
$BlockedRawCommands = @(
    "notifications.actions",
    "notifications.list",
    "notifications.list_package"
)
$BaseVoiceTools = @("android_media_play", "web_fetch", "web_search")
$BrokerTools = @(
    "assistant_contacts_search",
    "assistant_phone_call",
    "assistant_sms_send",
    "assistant_calendar_next",
    "assistant_calendar_create",
    "assistant_memory_remember",
    "assistant_memory_forget",
    "assistant_windows_files_search",
    "assistant_windows_files_read",
    $ToolName
)
$GenericToolDeny = @(
    "agents_list", "browser", "canvas", "codex_threads", "cron", "gateway",
    "group:fs", "group:memory", "group:messaging", "group:nodes", "group:runtime",
    "image_generate", "memory_get", "memory_search", "message", "music_generate", "nodes",
    "session_status", "sessions_history", "sessions_list", "sessions_send", "sessions_spawn",
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
    $quoted = @($Arguments | ForEach-Object { "'" + $_.Replace("'", "'\''") + "'" })
    $command = "openclaw " + ($quoted -join " ")
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
        if ($RequestedNodeId -notmatch '^[a-f0-9]{64}$') { throw "NodeId is invalid." }
        $eligible = @($eligible | Where-Object nodeId -eq $RequestedNodeId)
    }
    if ($eligible.Count -ne 1) {
        throw "Expected one connected Android node exposing the signed assistant commands; found $($eligible.Count)."
    }
    return [string]$eligible[0].nodeId
}

function Set-MessengerPolicy {
    param([string]$Workspace, [bool]$Enabled)
    $path = "$Workspace/AGENTS.md"
    $current = (& wsl.exe -d $Distro -- cat $path) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Could not read $path" }
    $start = "<!-- signed-private-messenger-policy:start -->"
    $end = "<!-- signed-private-messenger-policy:end -->"
    $startIndex = $current.IndexOf($start, [StringComparison]::Ordinal)
    $endIndex = $current.IndexOf($end, [StringComparison]::Ordinal)
    if (($startIndex -ge 0) -ne ($endIndex -ge 0) -or ($startIndex -ge 0 -and $endIndex -lt $startIndex)) {
        throw "Existing signed Messenger policy markers are malformed."
    }
    if ($startIndex -ge 0) {
        $before = $current.Substring(0, $startIndex).TrimEnd()
        $after = $current.Substring($endIndex + $end.Length).TrimStart()
        $current = $before + $(if ($after) { "`n`n$after" } else { "" })
    }
    if ($Enabled) {
        $policy = @'
## Signed Private Messenger Notifications

- Use `messenger_notifications_read` only when the user asks about Messenger messages or notifications.
- This is retained notification-preview history for at most seven days, not full Messenger inbox history. State that limitation when it matters.
- Preview sender and text are spoken only by an installed offline Android voice and never returned to this model, the Gateway, Pocket TTS, or cloud TTS. Do not ask for, infer, repeat, or invent the private preview after the sanitized receipt.
- A Messenger read requires an on-phone grant bound to the normalized sender and maximum approved count for up to 10 minutes. A changed sender or larger count must prompt again. Only `COMPLETED` with `privateDelivery=spoken_on_phone` proves local speech finished.
- Never use raw notification, node, browser-control, Phone Link, filesystem, or shell access as a workaround.
'@
        $current = $current.TrimEnd() + "`n`n$start`n$($policy.Trim())`n$end"
    }
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($current.TrimEnd() + "`n"))
    Invoke-WslBash "printf '%s' '$encoded' | base64 -d > '$path'" | Out-Null
}

& (Join-Path $PSScriptRoot "configure-assistant-capability-broker.ps1") -Distro $Distro

$configuredAgents = @((((Invoke-OpenClaw config get agents.list) -join "`n") | ConvertFrom-Json) | ForEach-Object { $_ })
$agentIndex = -1
for ($index = 0; $index -lt $configuredAgents.Count; $index++) {
    if ([string]$configuredAgents[$index].id -eq $AgentId) { $agentIndex = $index; break }
}
if ($agentIndex -lt 0) { throw "Agent '$AgentId' is missing from agents.list." }

$nodeConfig = ((Invoke-OpenClaw config get gateway.nodes) -join "`n") | ConvertFrom-Json
$allowCommands = @($nodeConfig.allowCommands | Where-Object { $_ -notin $BlockedRawCommands })
$denyCommands = @(@($nodeConfig.denyCommands) + $BlockedRawCommands) | Sort-Object -Unique

if ($Disable) {
    Invoke-OpenClaw config set "plugins.entries.$PluginId.config.messengerReadsEnabled" false --strict-json | Out-Null
    $remaining = ((Invoke-OpenClaw config get "plugins.entries.$PluginId.config") -join "`n") | ConvertFrom-Json
    $signedCommandsStillNeeded =
        $remaining.privateReadsEnabled -eq $true -or
        $remaining.phoneCallsEnabled -eq $true -or
        $remaining.smsSendEnabled -eq $true -or
        $remaining.calendarReadsEnabled -eq $true -or
        $remaining.calendarWritesEnabled -eq $true -or
        $remaining.windowsFileSearchEnabled -eq $true -or
        $remaining.windowsFileReadEnabled -eq $true
    if (-not $signedCommandsStillNeeded) {
        $allowCommands = @($allowCommands | Where-Object { $_ -notin @($PresenceCommand, $ExecuteCommand) })
    }
} else {
    $allowCommands = @($allowCommands + $PresenceCommand + $ExecuteCommand) | Sort-Object -Unique
    $denyCommands = @($denyCommands | Where-Object { $_ -notin @($PresenceCommand, $ExecuteCommand) })
}
Invoke-OpenClaw config set gateway.nodes.allowCommands ($allowCommands | ConvertTo-Json -Compress) --strict-json | Out-Null
Invoke-OpenClaw config set gateway.nodes.denyCommands ($denyCommands | ConvertTo-Json -Compress) --strict-json | Out-Null
Invoke-OpenClaw config validate | Out-Null
Invoke-OpenClaw gateway restart | Out-Null

if (-not $Disable) {
    $resolvedNodeId = Resolve-AssistantNodeId -RequestedNodeId $NodeId
    Invoke-OpenClaw config set "plugins.entries.$PluginId.config.androidNodeId" ('"' + $resolvedNodeId + '"') --strict-json | Out-Null
    Invoke-OpenClaw config set "plugins.entries.$PluginId.config.privateReadAgentId" ('"' + $AgentId + '"') --strict-json | Out-Null
    Invoke-OpenClaw config set "plugins.entries.$PluginId.config.messengerReadsEnabled" true --strict-json | Out-Null
}

$agents = @((((Invoke-OpenClaw agents list --json) -join "`n") | ConvertFrom-Json) | ForEach-Object { $_ })
$agent = @($agents | Where-Object id -eq $AgentId)
if ($agent.Count -ne 1 -or -not [string]$agent[0].workspace) {
    throw "Expected one runtime agent named '$AgentId' with a trusted workspace."
}
Set-MessengerPolicy -Workspace ([string]$agent[0].workspace) -Enabled (-not $Disable)

Invoke-OpenClaw config validate | Out-Null
Invoke-OpenClaw gateway restart | Out-Null
$runtime = ((Invoke-OpenClaw plugins inspect $PluginId --runtime --json) -join "`n") | ConvertFrom-Json
$registered = @($runtime.plugin.toolNames | ForEach-Object { [string]$_ }) | Sort-Object -Unique
if (-not $Disable -and $ToolName -notin $registered) { throw "Broker did not register '$ToolName'." }
if ($Disable -and $ToolName -in $registered) { throw "Broker still registered '$ToolName' after disable." }

$brokerVoiceTools = @($registered | Where-Object { $_ -in $BrokerTools })
$memoryPair = @($brokerVoiceTools | Where-Object { $_ -in @("assistant_memory_remember", "assistant_memory_forget") })
if ($memoryPair.Count -notin @(0, 2)) { throw "Broker registered an incomplete explicit-memory tool pair." }
$memoryTools = if ($memoryPair.Count -eq 2) { @("memory_get", "memory_search") } else { @() }
$expectedTools = @($BaseVoiceTools + $brokerVoiceTools + $memoryTools) | Sort-Object -Unique
$deny = @($GenericToolDeny | Where-Object {
    $_ -notin $expectedTools -and -not ($_ -eq "group:memory" -and "memory_search" -in $expectedTools)
}) | Sort-Object -Unique
Invoke-OpenClaw config set "agents.list[$agentIndex].tools.profile" minimal | Out-Null
Invoke-OpenClaw config set "agents.list[$agentIndex].tools.alsoAllow" ($expectedTools | ConvertTo-Json -Compress) --strict-json | Out-Null
Invoke-OpenClaw config set "agents.list[$agentIndex].tools.deny" ($deny | ConvertTo-Json -Compress) --strict-json | Out-Null
Invoke-OpenClaw config set "agents.list[$agentIndex].model" $Model | Out-Null
Invoke-OpenClaw config validate | Out-Null
Invoke-OpenClaw gateway restart | Out-Null

if (-not $Disable) {
    $key = "agent:${AgentId}:messenger-policy-$([guid]::NewGuid().ToString('N'))"
    $smoke = ((Invoke-OpenClaw agent --agent $AgentId --session-key $key --timeout 60 `
        --message "Reply with exactly: signed Messenger policy ready" --json) -join "`n") | ConvertFrom-Json
    $effectiveModel = "$($smoke.result.meta.agentMeta.provider)/$($smoke.result.meta.agentMeta.model)"
    $effectiveTools = @($smoke.result.meta.systemPromptReport.tools.entries | ForEach-Object name) | Sort-Object -Unique
    if ($effectiveModel -ne $Model) { throw "Voice smoke used '$effectiveModel' instead of '$Model'." }
    if (@(Compare-Object -ReferenceObject $expectedTools -DifferenceObject $effectiveTools).Count -gt 0) {
        throw "Voice-main tool surface is not exact. Expected [$($expectedTools -join ', ')], got [$($effectiveTools -join ', ')]."
    }
}

Write-Host "Signed private Messenger notifications are $($(if ($Disable) { 'disabled' } else { 'enabled' })) for '$AgentId'."
