$helper = Join-Path (Split-Path -Parent $PSScriptRoot) "lib\windows-files-provisioning-transaction.ps1"
. $helper

Describe "Windows provisioner transaction restore" {
    BeforeEach {
        $script:priorTaskXml = '<?xml version="1.0"?><Task><Settings><Enabled>true</Enabled></Settings></Task>'
        $script:changedTaskXml = '<?xml version="1.0"?><Task><Settings><Enabled>false</Enabled></Settings></Task>'
        $script:taskXmlBackup = Join-Path $TestDrive "prior-task.xml"
        [IO.File]::WriteAllText($script:taskXmlBackup, $script:priorTaskXml)
    }

    It "fails when task unregistration fails" {
        $script:rollbackTask = [pscustomobject]@{ Name = "changed" }

        {
            Restore-AgenticWindowsNodeTaskDefinition `
                -TaskExisted $true `
                -TaskXmlBackupPath $script:taskXmlBackup `
                -GetTask { $script:rollbackTask } `
                -UnregisterTask { throw "injected unregister failure" } `
                -RegisterTaskXml { param($Xml) } `
                -ExportTaskXml { $script:priorTaskXml }
        } | Should Throw
    }

    It "fails when prior task XML registration fails" {
        $script:rollbackTask = [pscustomobject]@{ Name = "changed" }

        {
            Restore-AgenticWindowsNodeTaskDefinition `
                -TaskExisted $true `
                -TaskXmlBackupPath $script:taskXmlBackup `
                -GetTask { $script:rollbackTask } `
                -UnregisterTask { $script:rollbackTask = $null } `
                -RegisterTaskXml { param($Xml) throw "injected XML registration failure" } `
                -ExportTaskXml { $script:priorTaskXml }
        } | Should Throw
    }

    It "fails when restored task XML differs from the persisted definition" {
        $script:rollbackTask = [pscustomobject]@{ Name = "changed" }

        {
            Restore-AgenticWindowsNodeTaskDefinition `
                -TaskExisted $true `
                -TaskXmlBackupPath $script:taskXmlBackup `
                -GetTask { $script:rollbackTask } `
                -UnregisterTask { $script:rollbackTask = $null } `
                -RegisterTaskXml { param($Xml) $script:rollbackTask = [pscustomobject]@{ Name = "restored" } } `
                -ExportTaskXml { $script:changedTaskXml }
        } | Should Throw
    }

    It "removes and confirms absence of a newly created task after a later failure" {
        $script:rollbackTask = [pscustomobject]@{ Name = "new" }
        $script:unregisterCalls = 0

        Restore-AgenticWindowsNodeTaskDefinition `
            -TaskExisted $false `
            -TaskXmlBackupPath (Join-Path $TestDrive "not-required.xml") `
            -GetTask { $script:rollbackTask } `
            -UnregisterTask { $script:unregisterCalls += 1; $script:rollbackTask = $null } `
            -RegisterTaskXml { param($Xml) throw "registration must not run" } `
            -ExportTaskXml { throw "export must not run" }

        $script:rollbackTask | Should BeNullOrEmpty
        $script:unregisterCalls | Should Be 1
    }

    It "fails closed when launcher restoration fails or produces different bytes" {
        $backup = Join-Path $TestDrive "node.vbs.backup"
        $target = Join-Path $TestDrive "node.vbs"
        [IO.File]::WriteAllText($backup, "expected")
        [IO.File]::WriteAllText($target, "changed")

        {
            Restore-AgenticWindowsNodeFileBackup `
                -TargetPath $target `
                -BackupPath $backup `
                -OriginallyExisted $true `
                -CopyFile { param($Source, $Destination) throw "injected launcher restore failure" }
        } | Should Throw
        {
            Restore-AgenticWindowsNodeFileBackup `
                -TargetPath $target `
                -BackupPath $backup `
                -OriginallyExisted $true `
                -CopyFile { param($Source, $Destination) [IO.File]::WriteAllText($Destination, "corrupt") }
        } | Should Throw
    }

    It "fails rollback when the restored task cannot start" {
        {
            Restore-WindowsNodeRuntimePostcondition `
                -TaskLabel "test task" `
                -StartTask { throw "injected task-start failure" } `
                -GetTaskState { "Running" } `
                -TestLockHeld { $true } `
                -TestNodeReady { $true } `
                -Attempts 1 `
                -Wait {}
        } | Should Throw
    }

    It "fails rollback when start returns but runtime postconditions never hold" {
        $script:startCalls = 0

        {
            Restore-WindowsNodeRuntimePostcondition `
                -TaskLabel "test task" `
                -StartTask { $script:startCalls += 1 } `
                -GetTaskState { "Ready" } `
                -TestLockHeld { $false } `
                -TestNodeReady { $false } `
                -Attempts 2 `
                -Wait {}
        } | Should Throw
        $script:startCalls | Should Be 1
    }

    It "accepts rollback only after task, lock, and node postconditions hold" {
        $script:startCalls = 0

        Restore-WindowsNodeRuntimePostcondition `
            -TaskLabel "test task" `
            -StartTask { $script:startCalls += 1 } `
            -GetTaskState { "Running" } `
            -TestLockHeld { $true } `
            -TestNodeReady { $true } `
            -Attempts 1 `
            -Wait {}

        $script:startCalls | Should Be 1
    }
}

Describe "Windows node launcher rendering" {
    function New-TestAgenticTask {
        param(
            [string]$Arguments = (New-AgenticWindowsNodeSupervisorActionArguments `
                -SupervisorPath "C:\State\supervisor.ps1" `
                -StateDir "C:\State"),
            [object[]]$Triggers = @([pscustomobject]@{
                CimClass = [pscustomobject]@{ CimClassName = "MSFT_TaskBootTrigger" }
                Delay = "PT45S"
                Enabled = $true
                StartBoundary = ""
                EndBoundary = ""
                ExecutionTimeLimit = ""
                Repetition = [pscustomobject]@{
                    Interval = ""
                    Duration = ""
                    StopAtDurationEnd = $false
                }
            }),
            [string]$MultipleInstances = "IgnoreNew",
            [int]$RestartCount = 999,
            [string]$RestartInterval = "PT1M",
            [string]$ExecutionTimeLimit = "PT0S",
            [bool]$StartWhenAvailable = $true
        )

        return [pscustomobject]@{
            Actions = @([pscustomobject]@{
                Execute = "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe"
                Arguments = $Arguments
                WorkingDirectory = ""
            })
            Triggers = $Triggers
            Principal = [pscustomobject]@{
                UserId = "S-1-5-21-1-2-3-1001"
                LogonType = "S4U"
                RunLevel = "Limited"
                ProcessTokenSidType = "Default"
                RequiredPrivilege = @()
            }
            Settings = [pscustomobject]@{
                MultipleInstances = $MultipleInstances
                RestartCount = $RestartCount
                RestartInterval = $RestartInterval
                ExecutionTimeLimit = $ExecutionTimeLimit
                StartWhenAvailable = $StartWhenAvailable
                DisallowStartIfOnBatteries = $false
                StopIfGoingOnBatteries = $false
                Enabled = $true
                AllowDemandStart = $true
                AllowHardTerminate = $true
                Compatibility = "Win7"
                Hidden = $false
                Priority = 7
                RunOnlyIfIdle = $false
                RunOnlyIfNetworkAvailable = $false
                WakeToRun = $false
                UseUnifiedSchedulingEngine = $true
                Volatile = $false
                DeleteExpiredTaskAfter = ""
                MaintenanceSettings = $null
                IdleSettings = [pscustomobject]@{
                    StopOnIdleEnd = $true
                    RestartOnIdle = $false
                    IdleDuration = "PT10M"
                    WaitTimeout = "PT1H"
                }
            }
        }
    }

    It "accepts an exact owned S4U boot task without requiring repair" {
        $assessment = Get-AgenticWindowsNodeSupervisorTaskAssessment `
            -Task (New-TestAgenticTask) `
            -ExpectedUserSid "S-1-5-21-1-2-3-1001" `
            -PowerShellPath "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe" `
            -SupervisorPath "C:\State\supervisor.ps1" `
            -StateDir "C:\State"

        $assessment.IsOwned | Should Be $true
        $assessment.NeedsUpdate | Should Be $false
    }

    It "rejects altered action arguments and additional triggers" {
        $extraTrigger = [pscustomobject]@{ CimClass = [pscustomobject]@{ CimClassName = "MSFT_TaskTimeTrigger" } }
        $alteredAction = Get-AgenticWindowsNodeSupervisorTaskAssessment `
            -Task (New-TestAgenticTask -Arguments "-NoProfile -Command whoami") `
            -ExpectedUserSid "S-1-5-21-1-2-3-1001" `
            -PowerShellPath "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe" `
            -SupervisorPath "C:\State\supervisor.ps1" `
            -StateDir "C:\State"
        $additionalTrigger = Get-AgenticWindowsNodeSupervisorTaskAssessment `
            -Task (New-TestAgenticTask -Triggers @(
                [pscustomobject]@{ CimClass = [pscustomobject]@{ CimClassName = "MSFT_TaskBootTrigger" }; Delay = "PT45S"; Enabled = $true },
                $extraTrigger
            )) `
            -ExpectedUserSid "S-1-5-21-1-2-3-1001" `
            -PowerShellPath "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe" `
            -SupervisorPath "C:\State\supervisor.ps1" `
            -StateDir "C:\State"

        $alteredAction.IsOwned | Should Be $false
        $additionalTrigger.IsOwned | Should Be $false
    }

    It "compares task ownership by SID" {
        $assessment = Get-AgenticWindowsNodeSupervisorTaskAssessment `
            -Task (New-TestAgenticTask) `
            -ExpectedUserSid "S-1-5-21-9-9-9-1001" `
            -PowerShellPath "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe" `
            -SupervisorPath "C:\State\supervisor.ps1" `
            -StateDir "C:\State"

        $assessment.IsOwned | Should Be $false
    }

    It "repairs drift in boot-critical settings" {
        $restartDrift = Get-AgenticWindowsNodeSupervisorTaskAssessment `
            -Task (New-TestAgenticTask -RestartCount 0) `
            -ExpectedUserSid "S-1-5-21-1-2-3-1001" `
            -PowerShellPath "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe" `
            -SupervisorPath "C:\State\supervisor.ps1" `
            -StateDir "C:\State"
        $executionDrift = Get-AgenticWindowsNodeSupervisorTaskAssessment `
            -Task (New-TestAgenticTask -ExecutionTimeLimit "PT1H") `
            -ExpectedUserSid "S-1-5-21-1-2-3-1001" `
            -PowerShellPath "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe" `
            -SupervisorPath "C:\State\supervisor.ps1" `
            -StateDir "C:\State"

        $restartDrift.IsOwned | Should Be $true
        $restartDrift.NeedsUpdate | Should Be $true
        $executionDrift.IsOwned | Should Be $true
        $executionDrift.NeedsUpdate | Should Be $true
    }

    It "repairs every explicitly reviewed boot-task drift" {
        $disabledBoot = [pscustomobject]@{
            CimClass = [pscustomobject]@{ CimClassName = "MSFT_TaskBootTrigger" }
            Delay = "PT45S"
            Enabled = $false
            StartBoundary = ""
            EndBoundary = ""
            ExecutionTimeLimit = ""
            Repetition = [pscustomobject]@{ Interval = ""; Duration = ""; StopAtDurationEnd = $false }
        }
        $wrongDelay = [pscustomobject]@{
            CimClass = [pscustomobject]@{ CimClassName = "MSFT_TaskBootTrigger" }
            Delay = "PT5M"
            Enabled = $true
            StartBoundary = ""
            EndBoundary = ""
            ExecutionTimeLimit = ""
            Repetition = [pscustomobject]@{ Interval = ""; Duration = ""; StopAtDurationEnd = $false }
        }
        $driftedTasks = @(
            (New-TestAgenticTask -Triggers @($disabledBoot)),
            (New-TestAgenticTask -Triggers @($wrongDelay)),
            (New-TestAgenticTask -MultipleInstances "Parallel"),
            (New-TestAgenticTask -RestartCount 0 -RestartInterval ""),
            (New-TestAgenticTask -ExecutionTimeLimit "PT1H")
        )

        foreach ($task in $driftedTasks) {
            $assessment = Get-AgenticWindowsNodeSupervisorTaskAssessment `
                -Task $task `
                -ExpectedUserSid "S-1-5-21-1-2-3-1001" `
                -PowerShellPath "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe" `
                -SupervisorPath "C:\State\supervisor.ps1" `
                -StateDir "C:\State"
            $assessment.IsOwned | Should Be $true
            $assessment.NeedsUpdate | Should Be $true
        }
    }

    It "repairs volatile, bounded, and repeating boot tasks" {
        $volatile = New-TestAgenticTask
        $volatile.Settings.Volatile = $true
        $future = New-TestAgenticTask
        $future.Triggers[0].StartBoundary = "2099-01-01T00:00:00"
        $expired = New-TestAgenticTask
        $expired.Triggers[0].EndBoundary = "2020-01-01T00:00:00"
        $repeating = New-TestAgenticTask
        $repeating.Triggers[0].Repetition.Interval = "PT5M"

        foreach ($task in @($volatile, $future, $expired, $repeating)) {
            $assessment = Get-AgenticWindowsNodeSupervisorTaskAssessment `
                -Task $task `
                -ExpectedUserSid "S-1-5-21-1-2-3-1001" `
                -PowerShellPath "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe" `
                -SupervisorPath "C:\State\supervisor.ps1" `
                -StateDir "C:\State"
            $assessment.IsOwned | Should Be $true
            $assessment.NeedsUpdate | Should Be $true
        }
    }

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
