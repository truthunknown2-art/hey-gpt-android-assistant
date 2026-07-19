$helper = Join-Path (Split-Path -Parent $PSScriptRoot) "lib\windows-files-provisioning-transaction.ps1"
. $helper

Describe "Windows provisioner transaction restore" {
    BeforeEach {
        $script:priorTaskXml = '<?xml version="1.0"?><Task><Settings><Enabled>true</Enabled></Settings></Task>'
        $script:changedTaskXml = '<?xml version="1.0"?><Task><Settings><Enabled>false</Enabled></Settings></Task>'
        $script:taskXmlBackup = Join-Path $TestDrive "prior-task.xml"
        [IO.File]::WriteAllText($script:taskXmlBackup, $script:priorTaskXml)
    }

    It "captures successful output and terminates a hung external process at its deadline" {
        $pwsh = (Get-Command pwsh.exe -ErrorAction Stop).Source
        $probe = Join-Path $TestDrive "external-process-timeout-probe.ps1"
        $helperLiteral = ConvertTo-PowerShellSingleQuotedLiteral -Value $helper
        $pwshLiteral = ConvertTo-PowerShellSingleQuotedLiteral -Value $pwsh
        $probeContent = @"
. $helperLiteral
`$output = Invoke-ExternalProcessWithTimeout -FilePath $pwshLiteral -ArgumentList @('-NoProfile', '-Command', 'Write-Output ok') -TimeoutMilliseconds 5000 -DisplayName 'success probe'
if ((`$output -join '') -cne 'ok') { exit 10 }
`$stopwatch = [Diagnostics.Stopwatch]::StartNew()
try {
    Invoke-ExternalProcessWithTimeout -FilePath $pwshLiteral -ArgumentList @('-NoProfile', '-Command', 'Start-Sleep -Seconds 10') -TimeoutMilliseconds 250 -DisplayName 'timeout probe'
    exit 11
} catch {
    if (`$_.Exception.Message -notmatch 'exceeded its 250 millisecond deadline') { exit 12 }
    if (`$stopwatch.Elapsed.TotalSeconds -gt 5) { exit 13 }
}
"@
        [IO.File]::WriteAllText($probe, $probeContent, [Text.UTF8Encoding]::new($false))

        & $pwsh -NoProfile -File $probe
        $LASTEXITCODE | Should Be 0
    }

    It "waits for a stopped task to become restartable" {
        $script:restartStates = [Collections.Generic.Queue[string]]::new()
        $script:restartStates.Enqueue("Running")
        $script:restartStates.Enqueue("Ready")
        $script:restartWaits = 0

        Wait-ScheduledTaskReadyForRestart `
            -TaskLabel "test task" `
            -GetTaskState { $script:restartStates.Dequeue() } `
            -Attempts 2 `
            -Wait { $script:restartWaits += 1 }

        $script:restartWaits | Should Be 1
    }

    It "fails instead of racing a task that never reaches Ready" {
        {
            Wait-ScheduledTaskReadyForRestart `
                -TaskLabel "test task" `
                -GetTaskState { "Running" } `
                -Attempts 2 `
                -Wait {}
        } | Should Throw
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
        $script:stopCalls = 0
        {
            Restore-WindowsNodeRuntimePostcondition `
                -TaskLabel "test task" `
                -StartTask { throw "injected task-start failure" } `
                -StopRuntime { $script:stopCalls += 1 } `
                -GetTaskState { "Running" } `
                -TestLockHeld { $true } `
                -GetNodeConnectionMarker { [long]100 } `
                -TestNodeReady { param($PreviousConnectedAtMs) $true } `
                -Attempts 1 `
                -Wait {}
        } | Should Throw
        $script:stopCalls | Should Be 1
    }

    It "fails rollback when start returns but runtime postconditions never hold" {
        $script:startCalls = 0
        $script:stopTaskCalls = 0
        $script:stopProcessCalls = 0
        $script:previousConnectedAtMs = 0
        $script:restoredTaskState = "Running"
        $script:restoredLockHeld = $true

        {
            Restore-WindowsNodeRuntimePostcondition `
                -TaskLabel "test task" `
                -StartTask { $script:startCalls += 1 } `
                -StopRuntime {
                    Stop-WindowsNodeRuntimePostcondition `
                        -TaskLabel "test task" `
                        -CaptureProcesses { [pscustomobject]@{} } `
                        -StopTasks { $script:stopTaskCalls += 1; $script:restoredTaskState = "Ready" } `
                        -StopProcesses { $script:stopProcessCalls += 1; $script:restoredLockHeld = $false } `
                        -GetOwnerTaskState { $script:restoredTaskState } `
                        -GetInteractiveTaskState { "Ready" } `
                        -TestLockHeld { $script:restoredLockHeld } `
                        -TestNodeStopped { $true } `
                        -Attempts 1 `
                        -RequiredStableObservations 1 `
                        -Wait {}
                } `
                -GetTaskState { $script:restoredTaskState } `
                -TestLockHeld { $script:restoredLockHeld } `
                -GetNodeConnectionMarker { [long]1234 } `
                -TestNodeReady { param($PreviousConnectedAtMs) $script:previousConnectedAtMs = $PreviousConnectedAtMs; $false } `
                -Attempts 2 `
                -Wait {}
        } | Should Throw
        $script:startCalls | Should Be 1
        $script:stopTaskCalls | Should Be 1
        $script:stopProcessCalls | Should Be 1
        $script:restoredTaskState | Should Be "Ready"
        $script:restoredLockHeld | Should Be $false
        $script:previousConnectedAtMs | Should Be 1234
    }

    It "accepts rollback only after task, lock, and node postconditions hold" {
        $script:startCalls = 0
        $script:stopCalls = 0

        Restore-WindowsNodeRuntimePostcondition `
            -TaskLabel "test task" `
            -StartTask { $script:startCalls += 1 } `
            -StopRuntime { $script:stopCalls += 1 } `
            -GetTaskState { "Running" } `
            -TestLockHeld { $true } `
            -GetNodeConnectionMarker { [long]4102444800000 } `
            -TestNodeReady {
                param($PreviousConnectedAtMs)
                Test-WindowsNodeGatewayConnectionAdvanced `
                    -PreviousConnectedAtMs $PreviousConnectedAtMs `
                    -CurrentConnectedAtMs ([long]4102444800001)
            } `
            -Attempts 1 `
            -Wait {}

        $script:startCalls | Should Be 1
        $script:stopCalls | Should Be 0
    }

    It "reports when a failed restored runtime cannot be stopped again" {
        {
            Restore-WindowsNodeRuntimePostcondition `
                -TaskLabel "test task" `
                -StartTask {} `
                -StopRuntime { throw "injected task-stop failure" } `
                -GetTaskState { "Ready" } `
                -TestLockHeld { $false } `
                -GetNodeConnectionMarker { [long]100 } `
                -TestNodeReady { param($PreviousConnectedAtMs) $false } `
                -Attempts 1 `
                -Wait {}
        } | Should Throw
    }

    It "reports unknown cleanup state when the owner task or lock remains" {
        {
            Stop-WindowsNodeRuntimePostcondition `
                -TaskLabel "test task" `
                -CaptureProcesses { [pscustomobject]@{} } `
                -StopTasks {} `
                -StopProcesses {} `
                -GetOwnerTaskState { "Running" } `
                -GetInteractiveTaskState { "Ready" } `
                -TestLockHeld { $true } `
                -TestNodeStopped { $false } `
                -Attempts 1 `
                -RequiredStableObservations 1 `
                -Wait {}
        } | Should Throw
    }

    It "rejects a queued owner even when the lock is free and node is stopped" {
        {
            Stop-WindowsNodeRuntimePostcondition `
                -TaskLabel "test task" `
                -CaptureProcesses { [pscustomobject]@{} } `
                -StopTasks {} `
                -StopProcesses {} `
                -GetOwnerTaskState { "Queued" } `
                -GetInteractiveTaskState { "Ready" } `
                -TestLockHeld { $false } `
                -TestNodeStopped { $true } `
                -Attempts 1 `
                -RequiredStableObservations 1 `
                -Wait {}
        } | Should Throw
    }

    It "rejects an unknown interactive task state" {
        {
            Stop-WindowsNodeRuntimePostcondition `
                -TaskLabel "test task" `
                -CaptureProcesses { [pscustomobject]@{} } `
                -StopTasks {} `
                -StopProcesses {} `
                -GetOwnerTaskState { "Ready" } `
                -GetInteractiveTaskState { "Unknown" } `
                -TestLockHeld { $false } `
                -TestNodeStopped { $true } `
                -Attempts 1 `
                -RequiredStableObservations 1 `
                -Wait {}
        } | Should Throw
    }

    It "requires the exact node to disconnect before cleanup succeeds" {
        {
            Stop-WindowsNodeRuntimePostcondition `
                -TaskLabel "test task" `
                -CaptureProcesses { [pscustomobject]@{} } `
                -StopTasks {} `
                -StopProcesses {} `
                -GetOwnerTaskState { "Ready" } `
                -GetInteractiveTaskState { "Ready" } `
                -TestLockHeld { $false } `
                -TestNodeStopped { $false } `
                -Attempts 1 `
                -RequiredStableObservations 1 `
                -Wait {}
        } | Should Throw
    }

    It "rejects a connected hidden orphan when no command line or owned pid can be captured" {
        {
            Stop-WindowsNodeRuntimePostcondition `
                -TaskLabel "pre-provision Windows node runtime" `
                -CaptureProcesses { [pscustomobject]@{ Processes = @(); KnownRootProcessIds = @() } } `
                -StopTasks {} `
                -StopProcesses { param($ProcessCapture) } `
                -GetOwnerTaskState { "Absent" } `
                -GetInteractiveTaskState { "Ready" } `
                -TestLockHeld { $false } `
                -TestNodeStopped { $false } `
                -Attempts 2 `
                -RequiredStableObservations 2 `
                -Wait {}
        } | Should Throw
    }

    It "rejects a stale Gateway connection marker without using the Windows clock" {
        $script:cleanupCalls = 0
        {
            Restore-WindowsNodeRuntimePostcondition `
                -TaskLabel "test task" `
                -StartTask {} `
                -StopRuntime { $script:cleanupCalls += 1 } `
                -GetTaskState { "Running" } `
                -TestLockHeld { $true } `
                -GetNodeConnectionMarker { [long]1000 } `
                -TestNodeReady { param($PreviousConnectedAtMs) ([long]1000 -gt [long]$PreviousConnectedAtMs) } `
                -Attempts 1 `
                -Wait {}
        } | Should Throw
        $script:cleanupCalls | Should Be 1
    }

    It "accepts a newer Gateway marker when the Gateway clock is far behind Windows" {
        Test-WindowsNodeGatewayConnectionAdvanced `
            -PreviousConnectedAtMs ([long]946684800000) `
            -CurrentConnectedAtMs ([long]946684800001) | Should Be $true
    }

    It "accepts a newer Gateway marker when the Gateway clock is far ahead of Windows" {
        Test-WindowsNodeGatewayConnectionAdvanced `
            -PreviousConnectedAtMs ([long]4102444800000) `
            -CurrentConnectedAtMs ([long]4102444800001) | Should Be $true
    }

    It "rejects an unchanged Gateway marker" {
        Test-WindowsNodeGatewayConnectionAdvanced `
            -PreviousConnectedAtMs ([long]123456789) `
            -CurrentConnectedAtMs ([long]123456789) | Should Be $false
    }

    It "still stops tasks and fails closed when process capture is denied" {
        $script:captureStopTaskCalls = 0
        $script:captureStopProcessCalls = 0
        {
            Stop-WindowsNodeRuntimePostcondition `
                -TaskLabel "test task" `
                -CaptureProcesses { throw "injected capture access denied" } `
                -StopTasks { $script:captureStopTaskCalls += 1 } `
                -StopProcesses { param($ProcessCapture) $script:captureStopProcessCalls += 1 } `
                -GetOwnerTaskState { "Ready" } `
                -GetInteractiveTaskState { "Ready" } `
                -TestLockHeld { $false } `
                -TestNodeStopped { $true } `
                -Attempts 1 `
                -RequiredStableObservations 1 `
                -Wait {}
        } | Should Throw
        $script:captureStopTaskCalls | Should Be 1
        $script:captureStopProcessCalls | Should Be 1
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

    function New-TestGatewayTask {
        param(
            [string]$Execute = "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe",
            [string]$Arguments = (New-OpenClawGatewaySupervisorActionArguments `
                -BootstrapPath "C:\ProgramData\OpenClaw\Start-OpenClawGateway.ps1"),
            [object[]]$Triggers = @([pscustomobject]@{
                CimClass = [pscustomobject]@{ CimClassName = "MSFT_TaskBootTrigger" }
                Delay = "PT30S"
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
            [string]$LogonType = "S4U",
            [string]$RunLevel = "Limited"
        )

        return [pscustomobject]@{
            Actions = @([pscustomobject]@{
                Execute = $Execute
                Arguments = $Arguments
                WorkingDirectory = ""
            })
            Triggers = $Triggers
            Principal = [pscustomobject]@{
                UserId = "S-1-5-21-1-2-3-1001"
                LogonType = $LogonType
                RunLevel = $RunLevel
                ProcessTokenSidType = "Default"
                RequiredPrivilege = @()
            }
            Settings = [pscustomobject]@{
                MultipleInstances = "IgnoreNew"
                RestartCount = 3
                RestartInterval = "PT1M"
                ExecutionTimeLimit = "PT0S"
                StartWhenAvailable = $true
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

    It "accepts only the exact least-privilege Gateway supervisor definition" {
        $assessment = Get-OpenClawGatewaySupervisorTaskAssessment `
            -Task (New-TestGatewayTask) `
            -ExpectedUserSid "S-1-5-21-1-2-3-1001" `
            -PowerShellPath "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe" `
            -BootstrapPath "C:\ProgramData\OpenClaw\Start-OpenClawGateway.ps1"

        $assessment.IsOwned | Should Be $true
        $assessment.NeedsUpdate | Should Be $false
    }

    It "rejects Gateway executable, arguments, logon, run-level, and trigger drift" {
        $extraTrigger = [pscustomobject]@{
            CimClass = [pscustomobject]@{ CimClassName = "MSFT_TaskTimeTrigger" }
        }
        $disabledBoot = (New-TestGatewayTask).Triggers[0]
        $disabledBoot.Enabled = $false
        $driftedTasks = @(
            (New-TestGatewayTask -Execute "C:\Windows\System32\cmd.exe"),
            (New-TestGatewayTask -Arguments '-NoProfile -Command whoami'),
            (New-TestGatewayTask -LogonType "Interactive"),
            (New-TestGatewayTask -RunLevel "Highest"),
            (New-TestGatewayTask -Triggers @((New-TestGatewayTask).Triggers[0], $extraTrigger))
        )

        foreach ($task in $driftedTasks) {
            $assessment = Get-OpenClawGatewaySupervisorTaskAssessment `
                -Task $task `
                -ExpectedUserSid "S-1-5-21-1-2-3-1001" `
                -PowerShellPath "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe" `
                -BootstrapPath "C:\ProgramData\OpenClaw\Start-OpenClawGateway.ps1"
            $assessment.IsOwned | Should Be $false
        }

        $disabledAssessment = Get-OpenClawGatewaySupervisorTaskAssessment `
            -Task (New-TestGatewayTask -Triggers @($disabledBoot)) `
            -ExpectedUserSid "S-1-5-21-1-2-3-1001" `
            -PowerShellPath "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe" `
            -BootstrapPath "C:\ProgramData\OpenClaw\Start-OpenClawGateway.ps1"
        $disabledAssessment.IsOwned | Should Be $true
        $disabledAssessment.NeedsUpdate | Should Be $true
    }

    It "self-restores an abandoned cleanup hook before rejecting it after expiry" {
        $bootstrap = Join-Path $TestDrive "Start-OpenClawGateway.ps1"
        $backup = Join-Path $TestDrive "transaction\Start-OpenClawGateway.ps1"
        $marker = Join-Path $TestDrive "cleanup.done"
        New-Item -ItemType Directory -Path (Split-Path -Parent $backup) -Force | Out-Null
        $original = @'
$ErrorActionPreference = "Stop"
$script:gatewayOriginalReached = $true
'@
        [IO.File]::WriteAllText($bootstrap, $original, [Text.UTF8Encoding]::new($false))
        [IO.File]::WriteAllText($backup, $original, [Text.UTF8Encoding]::new($false))
        $expectedHash = (Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash
        $nonce = "0123456789abcdef0123456789abcdef"
        $payload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("[]"))
        $hook = New-AgenticWindowsNodeGatewayCleanupHook `
            -Nonce $nonce `
            -BootstrapPath $bootstrap `
            -BootstrapBackupPath $backup `
            -ExpectedBootstrapSha256 $expectedHash `
            -MarkerPath $marker `
            -PayloadBase64 $payload `
            -ExpiresUtcTicks ([DateTime]::UtcNow.AddMinutes(-1).Ticks)
        $hook | Should Match "hey-gpt-s4u-cleanup:${nonce}:start"
        $hook | Should Match "hey-gpt-s4u-cleanup:${nonce}:end"
        $temporary = $original.Insert(
            $original.IndexOf('$ErrorActionPreference = "Stop"') + '$ErrorActionPreference = "Stop"'.Length,
            "`r`n`r`n$hook"
        )

        # This is the state left by terminating the provisioner immediately after its temporary write.
        [IO.File]::WriteAllText($bootstrap, $temporary, [Text.UTF8Encoding]::new($false))
        { . $bootstrap } | Should Throw

        (Get-FileHash -LiteralPath $bootstrap -Algorithm SHA256).Hash | Should Be $expectedHash
        [IO.File]::ReadAllText($bootstrap) | Should Be $original
        [IO.File]::ReadAllText($bootstrap) | Should Not Match "hey-gpt-s4u-cleanup"
        Test-Path -LiteralPath $marker | Should Be $false
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

    It "keeps provisioner callbacks in the script session that owns their helper functions" {
        $provisioner = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) `
            "scripts\enable-assistant-windows-files.ps1"

        [IO.File]::ReadAllText($provisioner) | Should Not Match '\.GetNewClosure\('
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

    It "fails closed when process enumeration is denied" {
        {
            Stop-OwnedWindowsNodeProcesses `
                -NodeCommandPath "C:\State\node.cmd" `
                -GetProcesses { throw "injected access denied" }
        } | Should Throw
    }

    It "uses the exact scheduled-task pid when command lines are hidden" {
        $processes = @(
            [pscustomobject]@{ Name = "powershell.exe"; ProcessId = 700; ParentProcessId = 1; CommandLine = $null },
            [pscustomobject]@{ Name = "cmd.exe"; ProcessId = 701; ParentProcessId = 700; CommandLine = $null },
            [pscustomobject]@{ Name = "node.exe"; ProcessId = 702; ParentProcessId = 701; CommandLine = $null }
        )

        $ids = @(Get-OwnedWindowsNodeProcessIds `
            -Processes $processes `
            -NodeCommandPath "C:\State\node.cmd" `
            -KnownRootProcessIds @(700))

        $ids | Should Be @(702, 701, 700)
    }

    It "fails when a captured owned child survives termination" {
        $nodeCommand = "C:\State\node.cmd"
        $processes = @(
            [pscustomobject]@{ Name = "cmd.exe"; ProcessId = 800; ParentProcessId = 1; CommandLine = "cmd.exe /c $nodeCommand" },
            [pscustomobject]@{ Name = "node.exe"; ProcessId = 801; ParentProcessId = 800; CommandLine = "node openclaw node run" }
        )

        {
            Stop-OwnedWindowsNodeProcesses `
                -NodeCommandPath $nodeCommand `
                -GetProcesses { $processes } `
                -StopProcess { param($ProcessId) } `
                -TestProcessExists { param($ProcessId) $true } `
                -Attempts 1 `
                -Wait {}
        } | Should Throw
    }

    It "returns only after every captured owned pid exits" {
        $nodeCommand = "C:\State\node.cmd"
        $processes = @(
            [pscustomobject]@{ Name = "cmd.exe"; ProcessId = 900; ParentProcessId = 1; CommandLine = "cmd.exe /c $nodeCommand" },
            [pscustomobject]@{ Name = "node.exe"; ProcessId = 901; ParentProcessId = 900; CommandLine = "node openclaw node run" }
        )
        $script:alive = @{ 900 = $true; 901 = $true }

        $stopped = @(Stop-OwnedWindowsNodeProcesses `
            -NodeCommandPath $nodeCommand `
            -GetProcesses { $processes } `
            -StopProcess { param($ProcessId) $script:alive[$ProcessId] = $false } `
            -TestProcessExists { param($ProcessId) [bool]$script:alive[$ProcessId] } `
            -Attempts 1 `
            -Wait {})

        $stopped | Should Be @(901, 900)
        $script:alive[900] | Should Be $false
        $script:alive[901] | Should Be $false
    }

    It "passes the complete child-first capture to a token-boundary stop callback" {
        $nodeCommand = "C:\State\node.cmd"
        $script:setCapture = @()
        $script:setSnapshotCount = 0
        $script:setAlive = @{ 950 = $true; 951 = $true }
        $processes = @(
            [pscustomobject]@{ Name = "cmd.exe"; ProcessId = 950; ParentProcessId = 1; CommandLine = "cmd.exe /c $nodeCommand"; CreationDate = [datetime]::UtcNow },
            [pscustomobject]@{ Name = "node.exe"; ProcessId = 951; ParentProcessId = 950; CommandLine = "node openclaw node run"; CreationDate = [datetime]::UtcNow }
        )

        $stopped = @(Stop-OwnedWindowsNodeProcesses `
            -NodeCommandPath $nodeCommand `
            -ProcessSnapshot $processes `
            -StopProcessSet {
                param($ProcessIds, $ProcessSnapshot)
                $script:setCapture = @($ProcessIds)
                $script:setSnapshotCount = @($ProcessSnapshot).Count
                foreach ($processId in $ProcessIds) { $script:setAlive[$processId] = $false }
            } `
            -TestProcessExists { param($ProcessId) [bool]$script:setAlive[$ProcessId] } `
            -Attempts 1 `
            -Wait {})

        $stopped | Should Be @(951, 950)
        $script:setCapture | Should Be @(951, 950)
        $script:setSnapshotCount | Should Be 2
    }
}
