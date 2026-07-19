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
$ToolName = "assistant_contacts_search"
$PresenceCommand = "assistant.presence.v1"
$ExecuteCommand = "assistant.execute.v1"
$BaseVoiceTools = @(
    "android_media_play",
    "messenger_notifications_read",
    "web_fetch",
    "web_search"
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

function Set-ContactPolicy {
    param([string]$Workspace, [bool]$Enabled)

    $agentsPath = "$Workspace/AGENTS.md"
    $current = (& wsl.exe -d $Distro -- cat $agentsPath) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Could not read $agentsPath" }
    $startMarker = "<!-- signed-private-contacts-policy:start -->"
    $endMarker = "<!-- signed-private-contacts-policy:end -->"
    $startIndex = $current.IndexOf($startMarker, [StringComparison]::Ordinal)
    $endIndex = $current.IndexOf($endMarker, [StringComparison]::Ordinal)
    if (($startIndex -ge 0) -ne ($endIndex -ge 0) -or ($startIndex -ge 0 -and $endIndex -lt $startIndex)) {
        throw "Existing signed contact policy markers are malformed."
    }
    if ($startIndex -ge 0) {
        $before = $current.Substring(0, $startIndex).TrimEnd()
        $after = $current.Substring($endIndex + $endMarker.Length).TrimStart()
        $current = $before + $(if ($after) { "`n`n" + $after } else { "" })
    }
    if ($Enabled) {
        $policy = @'
## Signed Private Contact Reads

- Use `assistant_contacts_search` when the user asks to find a contact or phone number.
- Matching names and phone numbers are spoken privately by the unlocked phone and never returned to this model. Do not ask for or invent those private fields after the tool runs.
- The first private read in a voice session requires approval on the phone. A `COMPLETED` receipt with `privateDelivery=spoken_on_phone` proves local speech finished; any other status means it was not delivered.
- Never use generic node, contacts, filesystem, browser-control, or shell tools as a workaround.
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
    Invoke-OpenClaw config set "plugins.entries.$PluginId.config.privateReadsEnabled" false --strict-json | Out-Null
    $nodeConfig = ((Invoke-OpenClaw config get gateway.nodes) -join "`n") | ConvertFrom-Json
    $allowCommands = @($nodeConfig.allowCommands | Where-Object { $_ -notin @($PresenceCommand, $ExecuteCommand) }) | Sort-Object -Unique
    Invoke-OpenClaw config set gateway.nodes.allowCommands ($allowCommands | ConvertTo-Json -Compress) --strict-json | Out-Null
    $existingAllow = @($configuredAgents[$agentIndex].tools.alsoAllow | ForEach-Object { [string]$_ })
    $existingDeny = @($configuredAgents[$agentIndex].tools.deny | ForEach-Object { [string]$_ })
    $nextAllow = @($existingAllow | Where-Object { $_ -ne $ToolName }) | Sort-Object -Unique
    $nextDeny = @($existingDeny + $ToolName) | Sort-Object -Unique
    Invoke-OpenClaw config set "agents.list[$agentIndex].tools.alsoAllow" ($nextAllow | ConvertTo-Json -Compress) --strict-json | Out-Null
    Invoke-OpenClaw config set "agents.list[$agentIndex].tools.deny" ($nextDeny | ConvertTo-Json -Compress) --strict-json | Out-Null
} else {
    $nodeConfig = ((Invoke-OpenClaw config get gateway.nodes) -join "`n") | ConvertFrom-Json
    $allowCommands = @(@($nodeConfig.allowCommands) + $PresenceCommand + $ExecuteCommand) | Sort-Object -Unique
    $denyCommands = @($nodeConfig.denyCommands | Where-Object { $_ -notin @($PresenceCommand, $ExecuteCommand) }) | Sort-Object -Unique
    Invoke-OpenClaw config set gateway.nodes.allowCommands ($allowCommands | ConvertTo-Json -Compress) --strict-json | Out-Null
    Invoke-OpenClaw config set gateway.nodes.denyCommands ($denyCommands | ConvertTo-Json -Compress) --strict-json | Out-Null
    Invoke-OpenClaw config validate | Out-Null
    Invoke-OpenClaw gateway restart | Out-Null
    $resolvedNodeId = Resolve-AssistantNodeId -RequestedNodeId $NodeId
    Invoke-OpenClaw config set "plugins.entries.$PluginId.config.androidNodeId" ('"' + $resolvedNodeId + '"') --strict-json | Out-Null
    Invoke-OpenClaw config set "plugins.entries.$PluginId.config.privateReadAgentId" ('"' + $AgentId + '"') --strict-json | Out-Null
    Invoke-OpenClaw config set "plugins.entries.$PluginId.config.privateReadsEnabled" true --strict-json | Out-Null
}

$agents = @((((Invoke-OpenClaw agents list --json) -join "`n") | ConvertFrom-Json) | ForEach-Object { $_ })
$agent = @($agents | Where-Object id -eq $AgentId)
if ($agent.Count -ne 1 -or -not [string]$agent[0].workspace) {
    throw "Expected one runtime agent named '$AgentId' with a trusted workspace."
}
Set-ContactPolicy -Workspace ([string]$agent[0].workspace) -Enabled (-not $Disable)

Invoke-OpenClaw config validate | Out-Null
Invoke-OpenClaw gateway restart | Out-Null

$runtime = ((Invoke-OpenClaw plugins inspect $PluginId --runtime --json) -join "`n") | ConvertFrom-Json
$registered = @($runtime.plugin.toolNames | ForEach-Object { [string]$_ }) | Sort-Object -Unique
if (-not $Disable -and $ToolName -notin $registered) { throw "Broker did not register '$ToolName'." }
if ($Disable -and $ToolName -in $registered) { throw "Broker still registered '$ToolName' after disable." }

if (-not $Disable) {
    $brokerVoiceTools = @($registered | Where-Object {
        $_ -in @($ToolName, "assistant_memory_remember", "assistant_memory_forget")
    })
    $registeredMemoryTools = @($brokerVoiceTools | Where-Object {
        $_ -in @("assistant_memory_remember", "assistant_memory_forget")
    })
    if ($registeredMemoryTools.Count -notin @(0, 2)) {
        throw "Broker registered an incomplete explicit-memory tool pair."
    }
    $memoryTools = if ($registeredMemoryTools.Count -eq 2) {
        @("memory_get", "memory_search")
    } else {
        @()
    }
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

    $policyCheckKey = "agent:${AgentId}:contact-policy-$([guid]::NewGuid().ToString('N'))"
    $smoke = ((Invoke-OpenClaw agent --agent $AgentId --session-key $policyCheckKey --timeout 60 `
        --message "Reply with exactly: signed contacts policy ready" --json) -join "`n") | ConvertFrom-Json
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
Write-Host "Signed private contact search is $state for '$AgentId'."
Write-Host "Broker tools: $($registered -join ', ')."
