function Complete-WindowsNodeTaskState {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][bool]$TaskStopped,
        [Parameter(Mandatory = $true)][bool]$ProvisioningSucceeded,
        [Parameter(Mandatory = $true)][bool]$RollbackSucceeded,
        [Parameter(Mandatory = $true)][scriptblock]$StartTask
    )

    if (-not $TaskStopped) { return $false }
    if (-not ($ProvisioningSucceeded -or $RollbackSucceeded)) { return $false }
    & $StartTask
    return $true
}

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
        [string]$StateDir = ""
    )

    $roots = @(Get-OwnedWindowsNodeRootProcessIds -Processes $Processes -NodeCommandPath $NodeCommandPath)
    if ($SupervisorPath -and $StateDir) {
        $roots = @($roots + @(Get-OwnedWindowsNodeSupervisorProcessIds `
            -Processes $Processes `
            -SupervisorPath $SupervisorPath `
            -StateDir $StateDir)) | Sort-Object -Unique
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
        [string]$StateDir = ""
    )

    $processes = @(Get-CimInstance Win32_Process)
    $processIds = @(Get-OwnedWindowsNodeProcessIds `
        -Processes $processes `
        -NodeCommandPath $NodeCommandPath `
        -SupervisorPath $SupervisorPath `
        -StateDir $StateDir)
    foreach ($processId in $processIds) {
        Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
    }

    if ($processIds.Count -gt 0) {
        Start-Sleep -Milliseconds 300
    }
    return $processIds
}
