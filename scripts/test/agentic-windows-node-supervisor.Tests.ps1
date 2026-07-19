$supervisor = Join-Path (Split-Path -Parent $PSScriptRoot) "lib\agentic-windows-node-supervisor.ps1"

Describe "Agentic Windows node supervisor" {
    It "allows only one owner of the dedicated node command" {
        $stateDir = Join-Path $TestDrive "node-state"
        $marker = Join-Path $stateDir "starts.txt"
        $nodeCommand = Join-Path $stateDir "node.cmd"
        New-Item -ItemType Directory -Path $stateDir | Out-Null
        @"
@echo off
echo started>>"$marker"
ping -n 3 127.0.0.1 >nul
exit /b 0
"@ | Set-Content -LiteralPath $nodeCommand -Encoding Ascii

        $arguments = @(
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy", "Bypass",
            "-File", ('"' + $supervisor + '"'),
            "-StateDir", ('"' + $stateDir + '"'),
            "-RunOnce"
        )
        $first = Start-Process powershell.exe -ArgumentList $arguments -WindowStyle Hidden -PassThru
        try {
            $lockPath = Join-Path $stateDir "agentic-node-supervisor.lock"
            for ($attempt = 0; $attempt -lt 20 -and -not (Test-Path -LiteralPath $lockPath); $attempt++) {
                Start-Sleep -Milliseconds 100
            }
            Test-Path -LiteralPath $lockPath | Should Be $true

            $second = Start-Process powershell.exe -ArgumentList $arguments -WindowStyle Hidden -PassThru -Wait
            $second.ExitCode | Should Be 0
            $first.WaitForExit(5000) | Should Be $true
            @(Get-Content -LiteralPath $marker).Count | Should Be 1

            $log = Get-Content -LiteralPath (Join-Path $stateDir "logs\agentic-node-supervisor.log") -Raw
            $log | Should Match "Supervisor process started"
            $log | Should Match "Supervisor lock acquired"
            $log | Should Match "Duplicate supervisor detected; exiting"
            $log | Should Match "Node process starting"
            $log | Should Match "Node process exited code=0"
        } finally {
            if (-not $first.HasExited) { $first.Kill() }
        }
    }
}
