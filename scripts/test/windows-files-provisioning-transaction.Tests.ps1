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

        $ids | Should Be @(102, 101, 100)
    }

    It "does not match a similarly named state directory" {
        $nodeCommand = "C:\State\AgenticWindowsNode\node.cmd"
        $processes = @(
            [pscustomobject]@{ Name = "cmd.exe"; ProcessId = 300; ParentProcessId = 1; CommandLine = "cmd.exe /c C:\State\AgenticWindowsNode-old\node.cmd" },
            [pscustomobject]@{ Name = "node.exe"; ProcessId = 301; ParentProcessId = 300; CommandLine = "node openclaw node run" }
        )

        @(Get-OwnedWindowsNodeProcessIds -Processes $processes -NodeCommandPath $nodeCommand).Count | Should Be 0
    }
}
