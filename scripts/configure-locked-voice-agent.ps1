[CmdletBinding()]
param(
    [string]$Distro = "OpenClawGateway",
    [string]$AgentId = "locked-voice",
    [string]$Model = "openai/gpt-5.6-sol"
)

$ErrorActionPreference = "Stop"

function Invoke-OpenClaw {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $output = & wsl.exe -d $Distro -- openclaw @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "OpenClaw command failed: openclaw $($Arguments -join ' ')"
    }
    return $output
}

$agents = (Invoke-OpenClaw agents list --json) | ConvertFrom-Json
if (-not ($agents | Where-Object id -eq $AgentId)) {
    Invoke-OpenClaw agents add $AgentId `
        --workspace "/home/openclaw/.openclaw/workspace-$AgentId" `
        --model $Model `
        --non-interactive `
        --json | Out-Null
}

$configuredAgents = (Invoke-OpenClaw config get agents.list) | ConvertFrom-Json
$agentIndex = [Array]::IndexOf([string[]]$configuredAgents.id, $AgentId)
if ($agentIndex -lt 0) {
    throw "Agent '$AgentId' was created but is missing from agents.list."
}

# The locked lane is conversational text only. The effective tool list is empty:
# it cannot execute commands, browse, access files, message people, or invoke the
# Android node. Skills are also omitted to keep latency and prompt size down.
Invoke-OpenClaw config set "agents.list[$agentIndex].tools.profile" minimal | Out-Null
Invoke-OpenClaw config set "agents.list[$agentIndex].tools.deny" `
    '["gateway","cron","sessions_spawn","sessions_send","group:fs","group:runtime","group:web","group:messaging","group:openclaw"]' `
    --strict-json | Out-Null
Invoke-OpenClaw config set "agents.list[$agentIndex].skills" '[]' --strict-json | Out-Null
Invoke-OpenClaw config validate | Out-Null
Invoke-OpenClaw gateway restart | Out-Null

Write-Host "Locked voice agent '$AgentId' is ready on $Distro with model $Model and no callable tools."
