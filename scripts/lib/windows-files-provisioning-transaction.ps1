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
