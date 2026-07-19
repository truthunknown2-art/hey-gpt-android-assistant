[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$StateDir,
    [switch]$RunOnce
)

$ErrorActionPreference = "Stop"
$resolvedStateDir = [IO.Path]::GetFullPath($StateDir).TrimEnd('\')
$nodeCommandPath = Join-Path $resolvedStateDir "node.cmd"
$lockPath = Join-Path $resolvedStateDir "agentic-node-supervisor.lock"
$lockStream = $null

if (-not (Test-Path -LiteralPath $nodeCommandPath -PathType Leaf)) {
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
        exit 0
    }

    do {
        & $env:ComSpec /d /c ('"' + $nodeCommandPath + '"')
        $exitCode = $LASTEXITCODE
        if ($RunOnce) { exit $exitCode }
        Start-Sleep -Seconds 10
    } while ($true)
} finally {
    if ($null -ne $lockStream) {
        $lockStream.Dispose()
    }
}
