$helper = Join-Path (Split-Path -Parent $PSScriptRoot) "lib\windows-files-provisioning-transaction.ps1"
. $helper

Describe "Windows file provisioner task recovery" {
    It "does not restart the node when provisioning and rollback both fail" {
        $script:startCalls = 0

        $restarted = Complete-WindowsNodeTaskState `
            -TaskStopped $true `
            -ProvisioningSucceeded $false `
            -RollbackSucceeded $false `
            -StartTask { $script:startCalls += 1 }

        $restarted | Should Be $false
        $script:startCalls | Should Be 0
    }

    It "restarts the node after a successful rollback" {
        $script:startCalls = 0

        $restarted = Complete-WindowsNodeTaskState `
            -TaskStopped $true `
            -ProvisioningSucceeded $false `
            -RollbackSucceeded $true `
            -StartTask { $script:startCalls += 1 }

        $restarted | Should Be $true
        $script:startCalls | Should Be 1
    }
}

Describe "Windows node launcher rendering" {
    It "renders the logon fallback as a request to the owned scheduled task" {
        $content = New-AgenticWindowsNodeVbsContent `
            -TaskPath "\OpenClaw\" `
            -TaskName "Agentic Windows Node Supervisor"

        $content | Should Match 'CreateObject\("Schedule.Service"\)'
        $content | Should Match 'GetFolder\("\\OpenClaw"\)'
        $content | Should Match 'GetTask\("Agentic Windows Node Supervisor"\)'
        $content | Should Match 'If task.State <> 4 Then'
        $content | Should Not Match 'schtasks'
    }

    It "escapes single quotes in Bash literals" {
        ConvertTo-BashSingleQuotedLiteral "a'b" | Should Be "'a'`"'`"'b'"
    }

    It "escapes single quotes in PowerShell literals" {
        ConvertTo-PowerShellSingleQuotedLiteral "a'b" | Should Be "'a''b'"
    }

    It "removes the legacy detached Gateway hook idempotently" {
        $base = @"
`$LogPath = 'gateway.log'

# hey-gpt-agentic-windows-node:start
Start-Process pwsh.exe
# hey-gpt-agentic-windows-node:end

while (`$true) {
    Start-Sleep 10
}
"@
        $first = Remove-AgenticWindowsNodeGatewayBootstrapHook -CurrentContent $base
        $second = Remove-AgenticWindowsNodeGatewayBootstrapHook -CurrentContent $first

        $second | Should Be $first
        $second | Should Not Match "hey-gpt-agentic-windows-node"
        $second | Should Not Match "Start-Process pwsh.exe"
        $second | Should Match 'while \(\$true\)'
    }

    It "rejects malformed legacy Gateway hook markers" {
        $message = $null
        try {
            Remove-AgenticWindowsNodeGatewayBootstrapHook -CurrentContent "# hey-gpt-agentic-windows-node:start"
        } catch {
            $message = $_.Exception.Message
        }
        $message | Should Be "The existing agentic Windows node bootstrap markers are malformed."
    }

    It "reports an exclusively held supervisor lock" {
        $lockPath = Join-Path $TestDrive "node.lock"
        Test-AgenticWindowsNodeSupervisorLockHeld -LockPath $lockPath | Should Be $false
        $stream = [IO.File]::Open($lockPath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        try {
            Test-AgenticWindowsNodeSupervisorLockHeld -LockPath $lockPath | Should Be $true
        } finally {
            $stream.Dispose()
        }
    }
}

Describe "Windows file provisioner process ownership" {
    It "returns the dedicated command tree child-first and excludes unrelated nodes" {
        $nodeCommand = "C:\Users\tester\AppData\Local\OpenClaw\AgenticWindowsNode\node.cmd"
        $processes = @(
            [pscustomobject]@{ Name = "cmd.exe"; ProcessId = 100; ParentProcessId = 1; CommandLine = "cmd.exe /c `"`"$nodeCommand`"`"" },
            [pscustomobject]@{ Name = "node.exe"; ProcessId = 101; ParentProcessId = 100; CommandLine = "node openclaw node run" },
            [pscustomobject]@{ Name = "helper.exe"; ProcessId = 102; ParentProcessId = 101; CommandLine = "helper" },
            [pscustomobject]@{ Name = "cmd.exe"; ProcessId = 200; ParentProcessId = 1; CommandLine = "cmd.exe /c C:\other\node.cmd" },
            [pscustomobject]@{ Name = "node.exe"; ProcessId = 201; ParentProcessId = 200; CommandLine = "node openclaw node run" }
        )

        $ids = @(Get-OwnedWindowsNodeProcessIds -Processes $processes -NodeCommandPath $nodeCommand)
        $roots = @(Get-OwnedWindowsNodeRootProcessIds -Processes $processes -NodeCommandPath $nodeCommand)

        $ids | Should Be @(102, 101, 100)
        $roots | Should Be @(100)
    }

    It "does not match a similarly named state directory" {
        $nodeCommand = "C:\State\AgenticWindowsNode\node.cmd"
        $processes = @(
            [pscustomobject]@{ Name = "cmd.exe"; ProcessId = 300; ParentProcessId = 1; CommandLine = "cmd.exe /c C:\State\AgenticWindowsNode-old\node.cmd" },
            [pscustomobject]@{ Name = "node.exe"; ProcessId = 301; ParentProcessId = 300; CommandLine = "node openclaw node run" }
        )

        @(Get-OwnedWindowsNodeProcessIds -Processes $processes -NodeCommandPath $nodeCommand).Count | Should Be 0
    }

    It "owns the persistent PowerShell supervisor and its descendants" {
        $stateDir = "C:\State\AgenticWindowsNode"
        $supervisor = Join-Path $stateDir "agentic-windows-node-supervisor.ps1"
        $nodeCommand = Join-Path $stateDir "node.cmd"
        $processes = @(
            [pscustomobject]@{ Name = "pwsh.exe"; ProcessId = 400; ParentProcessId = 1; CommandLine = "pwsh.exe -File `"$supervisor`" -StateDir `"$stateDir`"" },
            [pscustomobject]@{ Name = "cmd.exe"; ProcessId = 401; ParentProcessId = 400; CommandLine = "cmd.exe /c `"$nodeCommand`"" },
            [pscustomobject]@{ Name = "node.exe"; ProcessId = 402; ParentProcessId = 401; CommandLine = "node openclaw node run" }
        )

        $supervisors = @(Get-OwnedWindowsNodeSupervisorProcessIds -Processes $processes -SupervisorPath $supervisor -StateDir $stateDir)
        $tree = @(Get-OwnedWindowsNodeProcessIds -Processes $processes -NodeCommandPath $nodeCommand -SupervisorPath $supervisor -StateDir $stateDir)

        $supervisors | Should Be @(400)
        $tree | Should Be @(402, 401, 400)
    }
}
