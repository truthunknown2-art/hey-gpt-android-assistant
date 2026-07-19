[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$StateDir,
    [switch]$RunOnce
)

$ErrorActionPreference = "Stop"
$resolvedStateDir = [IO.Path]::GetFullPath($StateDir).TrimEnd('\')
$nodeCommandPath = Join-Path $resolvedStateDir "node.cmd"
$lockPath = Join-Path $resolvedStateDir "agentic-node-supervisor.lock"
$logDirectory = Join-Path $resolvedStateDir "logs"
$logPath = Join-Path $logDirectory "agentic-node-supervisor.log"
$lockStream = $null

function Write-AgenticNodeSupervisorLog {
    param([Parameter(Mandatory = $true)][string]$Message)

    try {
        New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
        if ((Test-Path -LiteralPath $logPath) -and (Get-Item -LiteralPath $logPath).Length -gt 2MB) {
            Move-Item -LiteralPath $logPath -Destination "$logPath.old" -Force
        }
        $timestamp = Get-Date -Format "yyyy-MM-ddTHH:mm:ssK"
        Add-Content -LiteralPath $logPath -Value "$timestamp $Message"
    } catch {
        # Diagnostics must never prevent the owned node from running.
    }
}

Write-AgenticNodeSupervisorLog "Supervisor process started"

if (-not (Test-Path -LiteralPath $nodeCommandPath -PathType Leaf)) {
    Write-AgenticNodeSupervisorLog "Dedicated node command is missing"
    throw "The dedicated OpenClaw node command is missing."
}

try {
    try {
        $lockStream = [IO.File]::Open(
            $lockPath,
            [IO.FileMode]::OpenOrCreate,
            [IO.FileAccess]::ReadWrite,
            [IO.FileShare]::None
        )
    } catch [IO.IOException] {
        # Another tracked supervisor already owns this dedicated node.
        Write-AgenticNodeSupervisorLog "Duplicate supervisor detected; exiting"
        exit 0
    }

    Write-AgenticNodeSupervisorLog "Supervisor lock acquired"
    do {
        Write-AgenticNodeSupervisorLog "Node process starting"
        & $env:ComSpec /d /c ('"' + $nodeCommandPath + '"')
        $exitCode = $LASTEXITCODE
        Write-AgenticNodeSupervisorLog "Node process exited code=$exitCode"
        if ($RunOnce) { exit $exitCode }
        Start-Sleep -Seconds 10
    } while ($true)
} finally {
    if ($null -ne $lockStream) {
        $lockStream.Dispose()
    }
    Write-AgenticNodeSupervisorLog "Supervisor process stopped"
}
