function ConvertTo-BashSingleQuotedLiteral {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Value)

    $singleQuoteEscape = "'" + [char]34 + "'" + [char]34 + "'"
    return "'" + $Value.Replace("'", $singleQuoteEscape) + "'"
}

function ConvertTo-PowerShellSingleQuotedLiteral {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Value)

    return "'" + $Value.Replace("'", "''") + "'"
}

function New-AgenticWindowsNodeSupervisorActionArguments {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$SupervisorPath,
        [Parameter(Mandatory = $true)][string]$StateDir
    )

    return @(
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy", "Bypass",
        "-File", ('"' + $SupervisorPath + '"'),
        "-StateDir", ('"' + $StateDir + '"')
    ) -join " "
}

function New-OpenClawGatewaySupervisorActionArguments {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$BootstrapPath)

    return @(
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy", "Bypass",
        "-File", ('"' + $BootstrapPath + '"')
    ) -join " "
}

function Resolve-WindowsAccountSid {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$AccountId)

    if ($AccountId -match '^S-\d(?:-\d+)+$') {
        return ([Security.Principal.SecurityIdentifier]::new($AccountId)).Value
    }
    return ([Security.Principal.NTAccount]::new($AccountId)).Translate(
        [Security.Principal.SecurityIdentifier]
    ).Value
}

function Get-OptionalScheduledTaskExact {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$TaskPath,
        [Parameter(Mandatory = $true)][string]$TaskName
    )

    try {
        return Get-ScheduledTask -TaskPath $TaskPath -TaskName $TaskName -ErrorAction Stop
    } catch {
        if ($_.CategoryInfo.Category -eq [Management.Automation.ErrorCategory]::ObjectNotFound -and
            $_.FullyQualifiedErrorId -like "CmdletizationQuery_NotFound*") {
            return $null
        }
        throw
    }
}

function Get-AgenticWindowsNodeSupervisorTaskAssessment {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][object]$Task,
        [Parameter(Mandatory = $true)][string]$ExpectedUserSid,
        [Parameter(Mandatory = $true)][string]$PowerShellPath,
        [Parameter(Mandatory = $true)][string]$SupervisorPath,
        [Parameter(Mandatory = $true)][string]$StateDir
    )

    $actions = @($Task.Actions)
    $triggers = @($Task.Triggers)
    $bootTriggers = @($triggers | Where-Object { $_.CimClass.CimClassName -eq "MSFT_TaskBootTrigger" })
    $expectedArguments = New-AgenticWindowsNodeSupervisorActionArguments `
        -SupervisorPath $SupervisorPath `
        -StateDir $StateDir
    $executeMatches = $false
    if ($actions.Count -eq 1 -and [string]$actions[0].Execute) {
        try {
            $executeMatches = [IO.Path]::GetFullPath([string]$actions[0].Execute) -ieq `
                [IO.Path]::GetFullPath($PowerShellPath)
        } catch {
            $executeMatches = $false
        }
    }
    $principalSid = try {
        Resolve-WindowsAccountSid -AccountId ([string]$Task.Principal.UserId)
    } catch {
        $null
    }

    $isOwned = $actions.Count -eq 1 -and
        $executeMatches -and
        [string]$actions[0].Arguments -ceq $expectedArguments -and
        -not [string]$actions[0].WorkingDirectory -and
        $principalSid -eq $ExpectedUserSid -and
        [string]$Task.Principal.LogonType -eq "S4U" -and
        $triggers.Count -eq 1 -and
        $bootTriggers.Count -eq 1
    if (-not $isOwned) {
        return [pscustomobject]@{ IsOwned = $false; NeedsUpdate = $false }
    }

    $settings = $Task.Settings
    $needsUpdate = [string]$Task.Principal.RunLevel -ne "Limited" -or
        [string]$bootTriggers[0].Delay -ne "PT45S" -or
        [bool]$bootTriggers[0].Enabled -ne $true -or
        [string]$bootTriggers[0].StartBoundary -ne "" -or
        [string]$bootTriggers[0].EndBoundary -ne "" -or
        [string]$bootTriggers[0].ExecutionTimeLimit -ne "" -or
        [string]$bootTriggers[0].Repetition.Interval -ne "" -or
        [string]$bootTriggers[0].Repetition.Duration -ne "" -or
        [bool]$bootTriggers[0].Repetition.StopAtDurationEnd -ne $false -or
        [string]$settings.MultipleInstances -ne "IgnoreNew" -or
        [int]$settings.RestartCount -ne 999 -or
        [string]$settings.RestartInterval -ne "PT1M" -or
        [string]$settings.ExecutionTimeLimit -ne "PT0S" -or
        [bool]$settings.StartWhenAvailable -ne $true -or
        [bool]$settings.DisallowStartIfOnBatteries -ne $false -or
        [bool]$settings.StopIfGoingOnBatteries -ne $false -or
        [bool]$settings.Enabled -ne $true -or
        [bool]$settings.AllowDemandStart -ne $true -or
        [bool]$settings.AllowHardTerminate -ne $true -or
        [string]$settings.Compatibility -ne "Win7" -or
        [bool]$settings.Hidden -ne $false -or
        [int]$settings.Priority -ne 7 -or
        [bool]$settings.RunOnlyIfIdle -ne $false -or
        [bool]$settings.RunOnlyIfNetworkAvailable -ne $false -or
        [bool]$settings.WakeToRun -ne $false -or
        [bool]$settings.UseUnifiedSchedulingEngine -ne $true -or
        [bool]$settings.Volatile -ne $false -or
        [string]$settings.DeleteExpiredTaskAfter -ne "" -or
        $null -ne $settings.MaintenanceSettings -or
        [bool]$settings.IdleSettings.StopOnIdleEnd -ne $true -or
        [bool]$settings.IdleSettings.RestartOnIdle -ne $false -or
        [string]$settings.IdleSettings.IdleDuration -ne "PT10M" -or
        [string]$settings.IdleSettings.WaitTimeout -ne "PT1H" -or
        [string]$Task.Principal.ProcessTokenSidType -ne "Default" -or
        @($Task.Principal.RequiredPrivilege | Where-Object { $_ }).Count -ne 0

    return [pscustomobject]@{ IsOwned = $true; NeedsUpdate = $needsUpdate }
}

function Get-OpenClawGatewaySupervisorTaskAssessment {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][object]$Task,
        [Parameter(Mandatory = $true)][string]$ExpectedUserSid,
        [Parameter(Mandatory = $true)][string]$PowerShellPath,
        [Parameter(Mandatory = $true)][string]$BootstrapPath
    )

    $actions = @($Task.Actions)
    $triggers = @($Task.Triggers)
    $bootTriggers = @($triggers | Where-Object { $_.CimClass.CimClassName -eq "MSFT_TaskBootTrigger" })
    $expectedArguments = New-OpenClawGatewaySupervisorActionArguments -BootstrapPath $BootstrapPath
    $executeMatches = $false
    if ($actions.Count -eq 1 -and [string]$actions[0].Execute) {
        try {
            $executeMatches = [IO.Path]::GetFullPath([string]$actions[0].Execute) -ieq `
                [IO.Path]::GetFullPath($PowerShellPath)
        } catch {
            $executeMatches = $false
        }
    }
    $principalSid = try {
        Resolve-WindowsAccountSid -AccountId ([string]$Task.Principal.UserId)
    } catch {
        $null
    }

    $isOwned = $actions.Count -eq 1 -and
        $executeMatches -and
        [string]$actions[0].Arguments -ceq $expectedArguments -and
        -not [string]$actions[0].WorkingDirectory -and
        $principalSid -eq $ExpectedUserSid -and
        [string]$Task.Principal.LogonType -eq "S4U" -and
        [string]$Task.Principal.RunLevel -eq "Limited" -and
        $triggers.Count -eq 1 -and
        $bootTriggers.Count -eq 1
    if (-not $isOwned) {
        return [pscustomobject]@{ IsOwned = $false; NeedsUpdate = $false }
    }

    $settings = $Task.Settings
    $needsUpdate = [string]$bootTriggers[0].Delay -ne "PT30S" -or
        [bool]$bootTriggers[0].Enabled -ne $true -or
        [string]$bootTriggers[0].StartBoundary -ne "" -or
        [string]$bootTriggers[0].EndBoundary -ne "" -or
        [string]$bootTriggers[0].ExecutionTimeLimit -ne "" -or
        [string]$bootTriggers[0].Repetition.Interval -ne "" -or
        [string]$bootTriggers[0].Repetition.Duration -ne "" -or
        [bool]$bootTriggers[0].Repetition.StopAtDurationEnd -ne $false -or
        [string]$settings.MultipleInstances -ne "IgnoreNew" -or
        [int]$settings.RestartCount -ne 3 -or
        [string]$settings.RestartInterval -ne "PT1M" -or
        [string]$settings.ExecutionTimeLimit -ne "PT0S" -or
        [bool]$settings.StartWhenAvailable -ne $true -or
        [bool]$settings.DisallowStartIfOnBatteries -ne $false -or
        [bool]$settings.StopIfGoingOnBatteries -ne $false -or
        [bool]$settings.Enabled -ne $true -or
        [bool]$settings.AllowDemandStart -ne $true -or
        [bool]$settings.AllowHardTerminate -ne $true -or
        [string]$settings.Compatibility -ne "Win7" -or
        [bool]$settings.Hidden -ne $false -or
        [int]$settings.Priority -ne 7 -or
        [bool]$settings.RunOnlyIfIdle -ne $false -or
        [bool]$settings.RunOnlyIfNetworkAvailable -ne $false -or
        [bool]$settings.WakeToRun -ne $false -or
        [bool]$settings.UseUnifiedSchedulingEngine -ne $true -or
        [bool]$settings.Volatile -ne $false -or
        [string]$settings.DeleteExpiredTaskAfter -ne "" -or
        $null -ne $settings.MaintenanceSettings -or
        [bool]$settings.IdleSettings.StopOnIdleEnd -ne $true -or
        [bool]$settings.IdleSettings.RestartOnIdle -ne $false -or
        [string]$settings.IdleSettings.IdleDuration -ne "PT10M" -or
        [string]$settings.IdleSettings.WaitTimeout -ne "PT1H" -or
        [string]$Task.Principal.ProcessTokenSidType -ne "Default" -or
        @($Task.Principal.RequiredPrivilege | Where-Object { $_ }).Count -ne 0

    return [pscustomobject]@{ IsOwned = $true; NeedsUpdate = $needsUpdate }
}

function New-AgenticWindowsNodeGatewayCleanupHook {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Nonce,
        [Parameter(Mandatory = $true)][string]$BootstrapPath,
        [Parameter(Mandatory = $true)][string]$BootstrapBackupPath,
        [Parameter(Mandatory = $true)][string]$ExpectedBootstrapSha256,
        [Parameter(Mandatory = $true)][string]$MarkerPath,
        [Parameter(Mandatory = $true)][string]$PayloadBase64,
        [Parameter(Mandatory = $true)][long]$ExpiresUtcTicks
    )

    if ($Nonce -notmatch '^[a-f0-9]{32}$' -or $ExpectedBootstrapSha256 -notmatch '^[A-F0-9]{64}$') {
        throw "The cleanup hook identity or bootstrap hash is invalid."
    }
    if ($PayloadBase64 -notmatch '^[A-Za-z0-9+/]*={0,2}$') {
        throw "The cleanup hook payload is invalid."
    }

    $bootstrapLiteral = ConvertTo-PowerShellSingleQuotedLiteral -Value $BootstrapPath
    $backupLiteral = ConvertTo-PowerShellSingleQuotedLiteral -Value $BootstrapBackupPath
    $markerLiteral = ConvertTo-PowerShellSingleQuotedLiteral -Value $MarkerPath
    $restoreTempLiteral = ConvertTo-PowerShellSingleQuotedLiteral `
        -Value "$BootstrapPath.restore-$Nonce.tmp"
    $restorePreviousLiteral = ConvertTo-PowerShellSingleQuotedLiteral `
        -Value "$BootstrapPath.restore-$Nonce.previous"

    return @"
# hey-gpt-s4u-cleanup:${Nonce}:start
`$cleanupBootstrap = $bootstrapLiteral
`$cleanupBackup = $backupLiteral
`$cleanupRestoreTemp = $restoreTempLiteral
`$cleanupRestorePrevious = $restorePreviousLiteral
`$cleanupExpectedHash = '$ExpectedBootstrapSha256'
try {
    if (-not (Test-Path -LiteralPath `$cleanupBackup -PathType Leaf) -or
        (Get-FileHash -LiteralPath `$cleanupBackup -Algorithm SHA256 -ErrorAction Stop).Hash -cne `$cleanupExpectedHash) {
        throw "The persisted Gateway bootstrap backup is missing or corrupt."
    }
    [IO.File]::WriteAllBytes(`$cleanupRestoreTemp, [IO.File]::ReadAllBytes(`$cleanupBackup))
    if ((Get-FileHash -LiteralPath `$cleanupRestoreTemp -Algorithm SHA256 -ErrorAction Stop).Hash -cne `$cleanupExpectedHash) {
        throw "The staged Gateway bootstrap restoration is corrupt."
    }
    [IO.File]::Replace(`$cleanupRestoreTemp, `$cleanupBootstrap, `$cleanupRestorePrevious, `$true)
    if ((Get-FileHash -LiteralPath `$cleanupBootstrap -Algorithm SHA256 -ErrorAction Stop).Hash -cne `$cleanupExpectedHash) {
        throw "The Gateway bootstrap restoration could not be verified."
    }
} finally {
    Remove-Item -LiteralPath `$cleanupRestoreTemp -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath `$cleanupRestorePrevious -Force -ErrorAction SilentlyContinue
}
if ([DateTime]::UtcNow.Ticks -gt $ExpiresUtcTicks) {
    throw "Expired agentic Windows node cleanup request."
}
`$cleanupTargets = @(([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$PayloadBase64')) | ConvertFrom-Json))
foreach (`$cleanupTarget in `$cleanupTargets) {
    `$cleanupProcess = @(Get-CimInstance Win32_Process -Filter "ProcessId=`$([int]`$cleanupTarget.id)" -ErrorAction Stop)
    if (`$cleanupProcess.Count -eq 0) { continue }
    if (`$cleanupProcess.Count -ne 1 -or
        ([datetime]`$cleanupProcess[0].CreationDate).ToUniversalTime().Ticks -ne [long]`$cleanupTarget.startTicks) {
        throw "Agentic Windows node cleanup process identity changed."
    }
    Stop-Process -Id ([int]`$cleanupTarget.id) -Force -ErrorAction Stop
}
[IO.File]::WriteAllText($markerLiteral, "ok", [Text.UTF8Encoding]::new(`$false))
# hey-gpt-s4u-cleanup:${Nonce}:end
"@
}

function ConvertTo-CanonicalScheduledTaskXml {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Xml)

    $document = [Xml.XmlDocument]::new()
    $document.PreserveWhitespace = $false
    $document.LoadXml($Xml)
    return $document.DocumentElement.OuterXml
}

function Restore-AgenticWindowsNodeTaskDefinition {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][bool]$TaskExisted,
        [Parameter(Mandatory = $true)][string]$TaskXmlBackupPath,
        [Parameter(Mandatory = $true)][scriptblock]$GetTask,
        [Parameter(Mandatory = $true)][scriptblock]$UnregisterTask,
        [Parameter(Mandatory = $true)][scriptblock]$RegisterTaskXml,
        [Parameter(Mandatory = $true)][scriptblock]$ExportTaskXml
    )

    if ($null -ne (& $GetTask)) {
        & $UnregisterTask
    }
    if ($null -ne (& $GetTask)) {
        throw "The changed scheduled task is still registered after rollback removal."
    }
    if (-not $TaskExisted) { return }
    if (-not (Test-Path -LiteralPath $TaskXmlBackupPath -PathType Leaf)) {
        throw "The prior scheduled-task XML backup is missing."
    }

    $expectedXml = [IO.File]::ReadAllText($TaskXmlBackupPath)
    & $RegisterTaskXml $expectedXml
    if ($null -eq (& $GetTask)) {
        throw "The prior scheduled task was not registered during rollback."
    }
    $actualXml = [string](& $ExportTaskXml)
    if ((ConvertTo-CanonicalScheduledTaskXml -Xml $actualXml) -cne
        (ConvertTo-CanonicalScheduledTaskXml -Xml $expectedXml)) {
        throw "The restored scheduled task does not match its saved XML definition."
    }
}

function Restore-AgenticWindowsNodeFileBackup {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$TargetPath,
        [Parameter(Mandatory = $true)][string]$BackupPath,
        [Parameter(Mandatory = $true)][bool]$OriginallyExisted,
        [scriptblock]$CopyFile = { param($Source, $Destination) Copy-Item -LiteralPath $Source -Destination $Destination -Force -ErrorAction Stop },
        [scriptblock]$RemoveFile = { param($Path) Remove-Item -LiteralPath $Path -Force -ErrorAction Stop }
    )

    if ($OriginallyExisted) {
        if (-not (Test-Path -LiteralPath $BackupPath -PathType Leaf)) {
            throw "File rollback backup '$BackupPath' is missing."
        }
        & $CopyFile $BackupPath $TargetPath
        if (-not (Test-Path -LiteralPath $TargetPath -PathType Leaf) -or
            (Get-FileHash -LiteralPath $TargetPath -Algorithm SHA256).Hash -cne
            (Get-FileHash -LiteralPath $BackupPath -Algorithm SHA256).Hash) {
            throw "Restored file '$TargetPath' does not match its rollback backup."
        }
        return
    }

    if (Test-Path -LiteralPath $TargetPath) {
        & $RemoveFile $TargetPath
    }
    if (Test-Path -LiteralPath $TargetPath) {
        throw "File '$TargetPath' still exists after rollback removal."
    }
}

function Restore-WindowsNodeRuntimePostcondition {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$TaskLabel,
        [Parameter(Mandatory = $true)][scriptblock]$StartTask,
        [Parameter(Mandatory = $true)][scriptblock]$StopRuntime,
        [Parameter(Mandatory = $true)][scriptblock]$GetTaskState,
        [Parameter(Mandatory = $true)][scriptblock]$TestLockHeld,
        [Parameter(Mandatory = $true)][scriptblock]$GetNodeConnectionMarker,
        [Parameter(Mandatory = $true)][scriptblock]$TestNodeReady,
        [int]$Attempts = 45,
        [scriptblock]$Wait = { Start-Sleep -Seconds 1 }
    )

    # Both values come from the Gateway, so host/WSL clock skew cannot affect freshness.
    $previousNodeConnectionMarker = & $GetNodeConnectionMarker
    try {
        & $StartTask
        for ($attempt = 0; $attempt -lt $Attempts; $attempt++) {
            $taskState = [string](& $GetTaskState)
            $lockHeld = [bool](& $TestLockHeld)
            $nodeReady = [bool](& $TestNodeReady $previousNodeConnectionMarker)
            if ($taskState -eq "Running" -and $lockHeld -and $nodeReady) {
                return
            }
            if ($attempt + 1 -lt $Attempts) {
                & $Wait
            }
        }

        throw "Restored task '$TaskLabel' did not remain running with the supervisor lock and a fresh exact node connection."
    } catch {
        $runtimeError = $_
        try {
            & $StopRuntime
        } catch {
            throw "Runtime restoration failed: $($runtimeError.Exception.Message) Failed-runtime cleanup could not prove a stopped state: $($_.Exception.Message)"
        }
        throw $runtimeError
    }
}

function Test-WindowsNodeTaskSafeState {
    [CmdletBinding()]
    param([AllowEmptyString()][string]$State)

    return $State -in @("Ready", "Disabled", "Absent")
}

function Test-WindowsNodeGatewayConnectionAdvanced {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][long]$PreviousConnectedAtMs,
        [Parameter(Mandatory = $true)][long]$CurrentConnectedAtMs
    )

    return $CurrentConnectedAtMs -gt $PreviousConnectedAtMs
}

function Get-ScheduledTaskRunningProcessIds {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$TaskPath,
        [Parameter(Mandatory = $true)][string]$TaskName
    )

    $scheduler = New-Object -ComObject "Schedule.Service"
    try {
        $scheduler.Connect()
        $comTaskPath = $TaskPath.TrimEnd('\')
        if (-not $comTaskPath) { $comTaskPath = "\" }
        $folder = $scheduler.GetFolder($comTaskPath)
        $registeredTask = $folder.GetTask($TaskName)
        return @($registeredTask.GetInstances(0) | ForEach-Object {
            [int]$_.EnginePID
        } | Where-Object { $_ -gt 0 } | Sort-Object -Unique)
    } finally {
        if ($null -ne $scheduler -and [Runtime.InteropServices.Marshal]::IsComObject($scheduler)) {
            [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($scheduler)
        }
    }
}

function Get-OptionalScheduledTaskRunningProcessIds {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$TaskPath,
        [Parameter(Mandatory = $true)][string]$TaskName
    )

    if ($null -eq (Get-OptionalScheduledTaskExact -TaskPath $TaskPath -TaskName $TaskName)) {
        return @()
    }
    return @(Get-ScheduledTaskRunningProcessIds -TaskPath $TaskPath -TaskName $TaskName)
}

function Stop-WindowsNodeRuntimePostcondition {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$TaskLabel,
        [Parameter(Mandatory = $true)][scriptblock]$CaptureProcesses,
        [Parameter(Mandatory = $true)][scriptblock]$StopTasks,
        [Parameter(Mandatory = $true)][scriptblock]$StopProcesses,
        [Parameter(Mandatory = $true)][scriptblock]$GetOwnerTaskState,
        [Parameter(Mandatory = $true)][scriptblock]$GetInteractiveTaskState,
        [Parameter(Mandatory = $true)][scriptblock]$TestLockHeld,
        [Parameter(Mandatory = $true)][scriptblock]$TestNodeStopped,
        [int]$Attempts = 20,
        [int]$RequiredStableObservations = 2,
        [scriptblock]$Wait = { Start-Sleep -Milliseconds 250 }
    )

    if ($RequiredStableObservations -lt 1 -or $RequiredStableObservations -gt $Attempts) {
        throw "RequiredStableObservations must be between one and Attempts."
    }

    $cleanupErrors = [Collections.Generic.List[string]]::new()
    $processCapture = $null
    try {
        $processCapture = & $CaptureProcesses
    } catch {
        $cleanupErrors.Add("process capture: $($_.Exception.Message)")
    }
    try {
        & $StopTasks
    } catch {
        $cleanupErrors.Add("task stop: $($_.Exception.Message)")
    }
    try {
        & $StopProcesses $processCapture
    } catch {
        $cleanupErrors.Add("process stop: $($_.Exception.Message)")
    }

    $ownerTaskState = "Unknown"
    $interactiveTaskState = "Unknown"
    $lockHeld = $true
    $nodeStopped = $false
    $stableObservations = 0
    for ($attempt = 0; $attempt -lt $Attempts; $attempt++) {
        try {
            $ownerTaskState = [string](& $GetOwnerTaskState)
        } catch {
            $ownerTaskState = "Unknown"
            $cleanupErrors.Add("owner task state: $($_.Exception.Message)")
        }
        try {
            $interactiveTaskState = [string](& $GetInteractiveTaskState)
        } catch {
            $interactiveTaskState = "Unknown"
            $cleanupErrors.Add("interactive task state: $($_.Exception.Message)")
        }
        try {
            $lockHeld = [bool](& $TestLockHeld)
        } catch {
            $lockHeld = $true
            $cleanupErrors.Add("lock state: $($_.Exception.Message)")
        }
        try {
            $nodeStopped = [bool](& $TestNodeStopped)
        } catch {
            $nodeStopped = $false
            $cleanupErrors.Add("node state: $($_.Exception.Message)")
        }

        $safe = (Test-WindowsNodeTaskSafeState -State $ownerTaskState) -and
            (Test-WindowsNodeTaskSafeState -State $interactiveTaskState) -and
            -not $lockHeld -and
            $nodeStopped -and
            $cleanupErrors.Count -eq 0
        if ($safe) {
            $stableObservations++
            if ($stableObservations -ge $RequiredStableObservations) {
                return
            }
        } else {
            $stableObservations = 0
        }
        if ($attempt + 1 -lt $Attempts) {
            & $Wait
        }
    }

    $details = if ($cleanupErrors.Count -gt 0) {
        $cleanupErrors -join "; "
    } else {
        "ownerState=$ownerTaskState interactiveState=$interactiveTaskState lockHeld=$lockHeld nodeStopped=$nodeStopped stableObservations=$stableObservations"
    }
    throw "Failed runtime '$TaskLabel' could not be proven stopped; runtime state is unknown ($details)."
}

function New-AgenticWindowsNodeVbsContent {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$TaskPath,
        [Parameter(Mandatory = $true)][string]$TaskName
    )

    $folder = $TaskPath.TrimEnd('\').Replace('"', '""')
    $name = $TaskName.Replace('"', '""')
    return @"
' Managed by hey-gpt-android-assistant
Set scheduler = CreateObject("Schedule.Service")
scheduler.Connect
Set task = scheduler.GetFolder("$folder").GetTask("$name")
If task.State <> 4 Then
    task.Run Empty
End If
"@.TrimStart() + "`r`n"
}

function Remove-AgenticWindowsNodeGatewayBootstrapHook {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$CurrentContent)

    $startMarker = "# hey-gpt-agentic-windows-node:start"
    $endMarker = "# hey-gpt-agentic-windows-node:end"
    $startIndex = $CurrentContent.IndexOf($startMarker, [StringComparison]::Ordinal)
    $endIndex = $CurrentContent.IndexOf($endMarker, [StringComparison]::Ordinal)
    if (($startIndex -ge 0) -ne ($endIndex -ge 0) -or ($startIndex -ge 0 -and $endIndex -lt $startIndex)) {
        throw "The existing agentic Windows node bootstrap markers are malformed."
    }
    if ($startIndex -lt 0) { return $CurrentContent }

    $afterIndex = $endIndex + $endMarker.Length
    while ($afterIndex -lt $CurrentContent.Length -and $CurrentContent[$afterIndex] -in @("`r", "`n")) {
        $afterIndex++
    }
    $before = $CurrentContent.Substring(0, $startIndex).TrimEnd()
    $after = $CurrentContent.Substring($afterIndex).TrimStart()
    if (-not $before) { return $after }
    if (-not $after) { return $before + "`r`n" }
    return $before + "`r`n`r`n" + $after
}

function Test-AgenticWindowsNodeSupervisorLockHeld {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$LockPath)

    try {
        $stream = [IO.File]::Open(
            $LockPath,
            [IO.FileMode]::OpenOrCreate,
            [IO.FileAccess]::ReadWrite,
            [IO.FileShare]::None
        )
        $stream.Dispose()
        return $false
    } catch [IO.IOException] {
        return $true
    }
}

function Get-OwnedWindowsNodeRootProcessIds {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][object[]]$Processes,
        [Parameter(Mandatory = $true)][string]$NodeCommandPath
    )

    $normalizedCommandPath = [IO.Path]::GetFullPath($NodeCommandPath)
    return @($Processes | Where-Object {
        [string]$_.Name -ieq "cmd.exe" -and
        [string]$_.CommandLine -and
        ([string]$_.CommandLine).IndexOf($normalizedCommandPath, [StringComparison]::OrdinalIgnoreCase) -ge 0
    } | ForEach-Object { [int]$_.ProcessId })
}

function Get-OwnedWindowsNodeSupervisorProcessIds {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][object[]]$Processes,
        [Parameter(Mandatory = $true)][string]$SupervisorPath,
        [Parameter(Mandatory = $true)][string]$StateDir
    )

    $normalizedSupervisorPath = [IO.Path]::GetFullPath($SupervisorPath)
    $normalizedStateDir = [IO.Path]::GetFullPath($StateDir).TrimEnd('\')
    return @($Processes | Where-Object {
        [IO.Path]::GetFileName([string]$_.Name) -in @("pwsh.exe", "powershell.exe") -and
        [string]$_.CommandLine -and
        ([string]$_.CommandLine).IndexOf($normalizedSupervisorPath, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        ([string]$_.CommandLine).IndexOf($normalizedStateDir, [StringComparison]::OrdinalIgnoreCase) -ge 0
    } | ForEach-Object { [int]$_.ProcessId })
}

function Get-OwnedWindowsNodeProcessIds {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][object[]]$Processes,
        [Parameter(Mandatory = $true)][string]$NodeCommandPath,
        [string]$SupervisorPath = "",
        [string]$StateDir = "",
        [int[]]$KnownRootProcessIds = @()
    )

    $roots = @(Get-OwnedWindowsNodeRootProcessIds -Processes $Processes -NodeCommandPath $NodeCommandPath)
    $roots = @($roots) + @($KnownRootProcessIds | Where-Object { $_ -gt 0 })
    $roots = @($roots | Sort-Object -Unique)
    if ($SupervisorPath -and $StateDir) {
        $roots = @($roots) + @(Get-OwnedWindowsNodeSupervisorProcessIds `
            -Processes $Processes `
            -SupervisorPath $SupervisorPath `
            -StateDir $StateDir)
        $roots = @($roots | Sort-Object -Unique)
    }

    if ($roots.Count -eq 0) { return @() }

    $depthById = @{}
    $queue = [Collections.Generic.Queue[object]]::new()
    foreach ($root in $roots) {
        $depthById[$root] = 0
        $queue.Enqueue([pscustomobject]@{ Id = $root; Depth = 0 })
    }

    while ($queue.Count -gt 0) {
        $current = $queue.Dequeue()
        foreach ($child in @($Processes | Where-Object { [int]$_.ParentProcessId -eq [int]$current.Id })) {
            $childId = [int]$child.ProcessId
            if ($depthById.ContainsKey($childId)) { continue }
            $childDepth = [int]$current.Depth + 1
            $depthById[$childId] = $childDepth
            $queue.Enqueue([pscustomobject]@{ Id = $childId; Depth = $childDepth })
        }
    }

    return @($depthById.GetEnumerator() |
        Sort-Object -Property @{ Expression = { [int]$_.Value }; Descending = $true }, @{ Expression = { [int]$_.Key }; Descending = $false } |
        ForEach-Object { [int]$_.Key })
}

function Stop-OwnedWindowsNodeProcesses {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$NodeCommandPath,
        [string]$SupervisorPath = "",
        [string]$StateDir = "",
        [AllowNull()][object[]]$ProcessSnapshot = $null,
        [int[]]$KnownRootProcessIds = @(),
        [scriptblock]$GetProcesses = { @(Get-CimInstance Win32_Process -ErrorAction Stop) },
        [scriptblock]$StopProcess = { param($ProcessId) Stop-Process -Id $ProcessId -Force -ErrorAction Stop },
        [AllowNull()][scriptblock]$StopProcessSet = $null,
        [scriptblock]$TestProcessExists = { param($ProcessId) $null -ne (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue) },
        [int]$Attempts = 20,
        [scriptblock]$Wait = { Start-Sleep -Milliseconds 100 }
    )

    if ($null -eq $ProcessSnapshot) {
        $ProcessSnapshot = @(& $GetProcesses)
    }

    $processIds = @(Get-OwnedWindowsNodeProcessIds `
        -Processes $ProcessSnapshot `
        -NodeCommandPath $NodeCommandPath `
        -SupervisorPath $SupervisorPath `
        -StateDir $StateDir `
        -KnownRootProcessIds $KnownRootProcessIds)
    if ($null -ne $StopProcessSet -and $processIds.Count -gt 0) {
        & $StopProcessSet $processIds $ProcessSnapshot
    } else {
        foreach ($processId in $processIds) {
            try {
                & $StopProcess $processId
            } catch {
                if ([bool](& $TestProcessExists $processId)) {
                    throw "Failed to stop owned Windows node process $processId`: $($_.Exception.Message)"
                }
            }
        }
    }

    for ($attempt = 0; $attempt -lt $Attempts; $attempt++) {
        $survivors = @($processIds | Where-Object { [bool](& $TestProcessExists $_) })
        if ($survivors.Count -eq 0) {
            return $processIds
        }
        if ($attempt + 1 -lt $Attempts) {
            & $Wait
        }
    }

    throw "Owned Windows node processes did not exit (pids=$($survivors -join ','))."
}
