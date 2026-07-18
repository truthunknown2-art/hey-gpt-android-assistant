[CmdletBinding()]
param(
    [string]$Distro = "OpenClawGateway",
    [string]$AgentId = "locked-voice",
    [string]$Model = "openai/gpt-5.6-sol"
)

$ErrorActionPreference = "Stop"

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

$parsedAgents = ((Invoke-OpenClaw agents list --json) -join "`n") | ConvertFrom-Json
$agents = @($parsedAgents | ForEach-Object { $_ })
if (@($agents | ForEach-Object { [string]$_.id }) -notcontains $AgentId) {
    Invoke-OpenClaw agents add $AgentId `
        --workspace "/home/openclaw/.openclaw/workspace-$AgentId" `
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

# The locked lane is conversational text only. The effective tool list is empty:
# it cannot execute commands, browse, access files, message people, or invoke the
# Android node. Skills are also omitted to keep latency and prompt size down.
Invoke-OpenClaw config set "agents.list[$agentIndex].model" $Model | Out-Null
Invoke-OpenClaw config set "agents.list[$agentIndex].tools.profile" minimal | Out-Null
Invoke-OpenClaw config set "agents.list[$agentIndex].tools.deny" `
    '["gateway","cron","sessions_spawn","sessions_send","group:fs","group:runtime","group:web","group:messaging","group:openclaw"]' `
    --strict-json | Out-Null
Invoke-OpenClaw config set "agents.list[$agentIndex].skills" '[]' --strict-json | Out-Null
Invoke-OpenClaw config validate | Out-Null
Invoke-OpenClaw gateway restart | Out-Null

$parsedVerifiedAgents = ((Invoke-OpenClaw config get agents.list) -join "`n") | ConvertFrom-Json
$verifiedAgents = @($parsedVerifiedAgents | ForEach-Object { $_ })
$verifiedAgent = $verifiedAgents | Where-Object id -eq $AgentId
if ($null -eq $verifiedAgent -or $verifiedAgent.model -ne $Model) {
    throw "Locked voice agent model verification failed. Expected '$Model'."
}

# Validate the effective prompt after restart, not only the stored policy. This
# consumes one short subscription-backed agent turn and must report zero tools.
$policyCheckKey = "agent:${AgentId}:policy-check-$([guid]::NewGuid().ToString('N'))"
$smoke = ((Invoke-OpenClaw agent --agent $AgentId `
    --session-key $policyCheckKey --timeout 60 `
    --message "Reply with exactly: locked voice policy ready" --json) -join "`n") | ConvertFrom-Json
$effectiveModel = "$($smoke.result.meta.agentMeta.provider)/$($smoke.result.meta.agentMeta.model)"
$effectiveTools = @($smoke.result.meta.systemPromptReport.tools.entries)
if ($effectiveModel -ne $Model) {
    throw "Locked voice smoke test used '$effectiveModel' instead of '$Model'."
}
if ($effectiveTools.Count -ne 0) {
    throw "Locked voice smoke test exposed $($effectiveTools.Count) callable tool(s)."
}

Write-Host "Locked voice agent '$AgentId' is ready on $Distro with verified model $Model and zero callable tools."
