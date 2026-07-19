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

function Get-OwnedWindowsNodeProcessIds {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][object[]]$Processes,
        [Parameter(Mandatory = $true)][string]$NodeCommandPath
    )

    $normalizedCommandPath = [IO.Path]::GetFullPath($NodeCommandPath)
    $roots = @($Processes | Where-Object {
        [string]$_.Name -ieq "cmd.exe" -and
        [string]$_.CommandLine -and
        ([string]$_.CommandLine).IndexOf($normalizedCommandPath, [StringComparison]::OrdinalIgnoreCase) -ge 0
    } | ForEach-Object { [int]$_.ProcessId })

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
    param([Parameter(Mandatory = $true)][string]$NodeCommandPath)

    $processes = @(Get-CimInstance Win32_Process)
    $processIds = @(Get-OwnedWindowsNodeProcessIds -Processes $processes -NodeCommandPath $NodeCommandPath)
    foreach ($processId in $processIds) {
        Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
    }

    if ($processIds.Count -gt 0) {
        Start-Sleep -Milliseconds 300
    }
    return $processIds
}
