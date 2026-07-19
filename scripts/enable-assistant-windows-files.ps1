[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$NodeId,
    [Parameter(Mandatory = $true)][string]$AndroidNodeId,
    [Parameter(Mandatory = $true)][string]$BrokerKeyId,
    [Parameter(Mandatory = $true)][string]$BrokerPublicKeyBase64Url,
    [string]$Distro = "OpenClawGateway",
    [string]$AgentId = "voice-main",
    [string]$ReadRoot = [Environment]::GetFolderPath("MyDocuments"),
    [string]$StateDir = (Join-Path $env:LOCALAPPDATA "OpenClaw\AgenticWindowsNode"),
    [string]$TaskName = "OpenClaw Node"
)

$ErrorActionPreference = "Stop"
$PluginId = "assistant-capability-broker"
$WindowsCommand = "assistant.windows.execute.v1"
$WindowsTools = @("assistant_windows_files_read", "assistant_windows_files_search")
$RestrictedNodeCommands = @(
    "browser.proxy",
    "system.execApprovals.get",
    "system.execApprovals.set",
    "system.run",
    "system.run.prepare",
    "system.which"
)

. (Join-Path $PSScriptRoot "lib\windows-files-provisioning-transaction.ps1")

function Register-AgenticWindowsNodeSupervisorTask {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$UserId,
        [Parameter(Mandatory = $true)][string]$PowerShellPath,
        [Parameter(Mandatory = $true)][string]$SupervisorPath,
        [Parameter(Mandatory = $true)][string]$DedicatedStateDir
    )

    $arguments = @(
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy", "Bypass",
        "-File", ('"' + $SupervisorPath + '"'),
        "-StateDir", ('"' + $DedicatedStateDir + '"')
    ) -join " "
    $action = New-ScheduledTaskAction -Execute $PowerShellPath -Argument $arguments
    $bootTrigger = New-ScheduledTaskTrigger -AtStartup
    $bootTrigger.Delay = "PT45S"
    $principal = New-ScheduledTaskPrincipal -UserId $UserId -LogonType S4U -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet `
        -MultipleInstances IgnoreNew `
        -RestartCount 999 `
        -RestartInterval (New-TimeSpan -Minutes 1) `
        -ExecutionTimeLimit ([TimeSpan]::Zero) `
        -StartWhenAvailable `
        -AllowStartIfOnBatteries `
        -DontStopIfGoingOnBatteries

    Register-ScheduledTask `
        -TaskPath $Path `
        -TaskName $Name `
        -Action $action `
        -Trigger $bootTrigger `
        -Principal $principal `
        -Settings $settings `
        -Description "Runs the signed, least-privilege Windows file node before interactive logon." `
        -Force | Out-Null
}

function Invoke-LocalOpenClaw {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    $output = & openclaw @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Local OpenClaw command failed: openclaw $($Arguments -join ' ')"
    }
    return $output
}

function Invoke-GatewayBash {
    param([Parameter(Mandatory = $true)][string]$Command)

    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Command))
    $runner = "printf '%s' '$encoded' | base64 -d | bash"
    $output = & wsl.exe -d $Distro -- bash -lc $runner
    if ($LASTEXITCODE -ne 0) {
        throw "Gateway command failed."
    }
    return $output
}

function Invoke-GatewayOpenClaw {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    $quotedArguments = @($Arguments | ForEach-Object {
        "'" + $_.Replace("'", "'\''") + "'"
    })
    $command = "openclaw " + ($quotedArguments -join " ")
    return Invoke-GatewayBash -Command $command
}

function Assert-ChildPath {
    param([string]$Path, [string]$Parent, [string]$Label)

    $fullPath = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $fullParent = [IO.Path]::GetFullPath($Parent).TrimEnd('\')
    if (-not $fullPath.StartsWith("$fullParent\", [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label must stay under '$fullParent'."
    }
    return $fullPath
}

if ($NodeId -notmatch '^[a-f0-9]{64}$' -or $AndroidNodeId -notmatch '^[a-f0-9]{64}$') {
    throw "NodeId and AndroidNodeId must each be exactly 64 lowercase hexadecimal characters."
}
if ($Distro -notmatch '^[A-Za-z0-9._-]+$' -or $AgentId -notmatch '^[a-z0-9][a-z0-9_-]{0,63}$') {
    throw "Distro or AgentId has an invalid format."
}
if ($BrokerKeyId -notmatch '^[A-Za-z0-9._-]{1,128}$') {
    throw "BrokerKeyId has an invalid format."
}
if ($BrokerPublicKeyBase64Url -notmatch '^[A-Za-z0-9_-]{43}$') {
    throw "BrokerPublicKeyBase64Url must be a 32-byte base64url Ed25519 public key."
}

$openClawRoot = Join-Path $env:LOCALAPPDATA "OpenClaw"
$resolvedStateDir = Assert-ChildPath -Path $StateDir -Parent $openClawRoot -Label "StateDir"
$resolvedReadRoot = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $ReadRoot).Path).TrimEnd('\')
if (-not (Test-Path -LiteralPath $resolvedReadRoot -PathType Container)) {
    throw "ReadRoot must be an existing directory."
}

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction Stop
$nodeVbsPath = Join-Path $resolvedStateDir "node.vbs"
$nodeCommandPath = Join-Path $resolvedStateDir "node.cmd"
$supervisorSource = (Resolve-Path (Join-Path $PSScriptRoot "lib\agentic-windows-node-supervisor.ps1")).Path
$supervisorPath = Join-Path $resolvedStateDir "agentic-windows-node-supervisor.ps1"
$taskActions = @($task.Actions)
if ($taskActions.Count -ne 1 -or
    [IO.Path]::GetFullPath([string]$taskActions[0].Execute) -ine $nodeVbsPath) {
    throw "Scheduled task '$TaskName' does not belong to the dedicated state directory."
}
$currentWindowsUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$currentShortUser = $currentWindowsUser.Split('\')[-1]
$taskUser = [string]$task.Principal.UserId
if ($taskUser -notin @($currentWindowsUser, $currentShortUser)) {
    throw "Scheduled task '$TaskName' belongs to a different Windows user."
}
if (-not (Test-Path -LiteralPath $nodeCommandPath -PathType Leaf)) {
    throw "The dedicated node command is missing from '$resolvedStateDir'."
}
$powerShellPath = @(
    (Get-Command pwsh.exe -ErrorAction SilentlyContinue).Source,
    (Get-Command powershell.exe -ErrorAction SilentlyContinue).Source
) | Where-Object { $_ } | Select-Object -First 1
if (-not $powerShellPath) {
    throw "PowerShell is required for the Windows node supervisor."
}
$agenticTaskPath = "\OpenClaw\"
$agenticTaskName = "Agentic Windows Node Supervisor"
$agenticTask = Get-ScheduledTask -TaskPath $agenticTaskPath -TaskName $agenticTaskName -ErrorAction SilentlyContinue
if ($agenticTask) {
    $agenticTaskActions = @($agenticTask.Actions)
    $agenticTaskUser = [string]$agenticTask.Principal.UserId
    $agenticBootTriggers = @($agenticTask.Triggers | Where-Object { $_.CimClass.CimClassName -eq "MSFT_TaskBootTrigger" })
    $agenticLogonTriggers = @($agenticTask.Triggers | Where-Object { $_.CimClass.CimClassName -eq "MSFT_TaskLogonTrigger" })
    $agenticTaskNeedsUpdate = $agenticLogonTriggers.Count -ne 0 -or
        [string]$agenticTask.Principal.RunLevel -ne "Limited"
    if ($agenticTaskActions.Count -ne 1 -or
        [IO.Path]::GetFullPath([string]$agenticTaskActions[0].Execute) -ine [IO.Path]::GetFullPath($powerShellPath) -or
        ([string]$agenticTaskActions[0].Arguments).IndexOf($supervisorPath, [StringComparison]::OrdinalIgnoreCase) -lt 0 -or
        ([string]$agenticTaskActions[0].Arguments).IndexOf($resolvedStateDir, [StringComparison]::OrdinalIgnoreCase) -lt 0 -or
        $agenticTaskUser -notin @($currentWindowsUser, $currentShortUser) -or
        [string]$agenticTask.Principal.LogonType -ne "S4U" -or
        $agenticBootTriggers.Count -ne 1 -or $agenticLogonTriggers.Count -gt 1) {
        throw "Scheduled task '$agenticTaskPath$agenticTaskName' is not the expected owned task."
    }
} else {
    $agenticTaskNeedsUpdate = $false
}
$gatewayBootstrapPath = Join-Path $env:ProgramData "OpenClaw\Start-OpenClawGateway.ps1"
if (-not (Test-Path -LiteralPath $gatewayBootstrapPath -PathType Leaf)) {
    throw "The owned OpenClaw Gateway boot supervisor is missing."
}
$gatewayTask = Get-ScheduledTask -TaskPath "\OpenClaw\" -TaskName "OpenClaw Gateway Supervisor" -ErrorAction Stop
$gatewayTaskActions = @($gatewayTask.Actions)
if ($gatewayTaskActions.Count -ne 1 -or
    ([string]$gatewayTaskActions[0].Arguments).IndexOf($gatewayBootstrapPath, [StringComparison]::OrdinalIgnoreCase) -lt 0 -or
    @($gatewayTask.Triggers | Where-Object { $_.CimClass.CimClassName -eq "MSFT_TaskBootTrigger" }).Count -ne 1) {
    throw "The OpenClaw Gateway boot supervisor task is not the expected owned task."
}
$gatewayTaskUser = [string]$gatewayTask.Principal.UserId
if ($gatewayTaskUser -notin @($currentWindowsUser, $currentShortUser)) {
    throw "The OpenClaw Gateway boot supervisor belongs to a different Windows user."
}

$pluginSource = (Resolve-Path (Join-Path $PSScriptRoot "..\integrations\$PluginId")).Path
$pluginParent = Join-Path $resolvedStateDir "plugin-dev"
$pluginStage = Assert-ChildPath -Path (Join-Path $pluginParent $PluginId) -Parent $resolvedStateDir -Label "Plugin stage"
$pluginNext = Assert-ChildPath -Path "$pluginStage.next" -Parent $resolvedStateDir -Label "Plugin next stage"
$pluginPrevious = Assert-ChildPath -Path "$pluginStage.previous" -Parent $resolvedStateDir -Label "Plugin previous stage"
$transactionId = [Guid]::NewGuid().ToString("N")
$nodeVbsBackup = [IO.File]::ReadAllBytes($nodeVbsPath)
$gatewayBootstrapBackup = [IO.File]::ReadAllBytes($gatewayBootstrapPath)
$agenticTaskExisted = $null -ne $agenticTask
$agenticTaskBackup = if ($agenticTaskExisted) {
    Export-ScheduledTask -TaskPath $agenticTaskPath -TaskName $agenticTaskName
} else {
    $null
}
$supervisorExisted = Test-Path -LiteralPath $supervisorPath -PathType Leaf
$supervisorBackup = if ($supervisorExisted) { [IO.File]::ReadAllBytes($supervisorPath) } else { $null }
$localConfigPath = Assert-ChildPath -Path (Join-Path $resolvedStateDir "openclaw.json") -Parent $resolvedStateDir -Label "Local config"
$localConfigBackup = Assert-ChildPath -Path "$localConfigPath.rollback.$transactionId" -Parent $resolvedStateDir -Label "Local config backup"
$gatewayConfigPath = "/home/openclaw/.openclaw/openclaw.json"
$gatewayConfigBackup = "$gatewayConfigPath.rollback.$transactionId"
$gatewayPluginStage = "/home/openclaw/.openclaw/plugin-dev/$PluginId"
$gatewayPluginBackup = "$gatewayPluginStage.rollback.$transactionId"
$voiceSessionKey = "agent:${AgentId}:voice-android-$($AndroidNodeId.Substring(0, 32))"
$pluginStageExisted = Test-Path -LiteralPath $pluginStage
$localConfigExisted = Test-Path -LiteralPath $localConfigPath -PathType Leaf
$taskStopped = $false
$promoted = $false
$localSnapshotReady = $false
$gatewaySnapshotReady = $false
$gatewayPluginExisted = $false
$rollbackFailed = $false
$provisioningSucceeded = $false
$rollbackSucceeded = $false
$launchersChanged = $false
$agenticTaskChanged = $false
$agenticTaskStopped = $false

try {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if ($agenticTaskExisted) {
        Stop-ScheduledTask -TaskPath $agenticTaskPath -TaskName $agenticTaskName -ErrorAction SilentlyContinue
        $agenticTaskStopped = $true
    }
    Stop-OwnedWindowsNodeProcesses `
        -NodeCommandPath $nodeCommandPath `
        -SupervisorPath $supervisorPath `
        -StateDir $resolvedStateDir | Out-Null
    $lockPath = Join-Path $resolvedStateDir "agentic-node-supervisor.lock"
    for ($attempt = 0; $attempt -lt 20 -and (Test-AgenticWindowsNodeSupervisorLockHeld -LockPath $lockPath); $attempt++) {
        Start-Sleep -Milliseconds 250
    }
    if (Test-AgenticWindowsNodeSupervisorLockHeld -LockPath $lockPath) {
        throw "A legacy detached Windows node supervisor still owns the state lock. Stop the pre-logon Gateway task or reboot once, then rerun provisioning."
    }
    $taskStopped = $true
    $launchersChanged = $true
    Copy-Item -LiteralPath $supervisorSource -Destination $supervisorPath -Force
    $vbsContent = New-AgenticWindowsNodeVbsContent `
        -TaskPath $agenticTaskPath `
        -TaskName $agenticTaskName
    [IO.File]::WriteAllText($nodeVbsPath, $vbsContent, [Text.UTF8Encoding]::new($false))
    $gatewayBootstrapContent = Remove-AgenticWindowsNodeGatewayBootstrapHook `
        -CurrentContent ([IO.File]::ReadAllText($gatewayBootstrapPath))
    [IO.File]::WriteAllText($gatewayBootstrapPath, $gatewayBootstrapContent, [Text.UTF8Encoding]::new($false))
    if (-not $agenticTaskExisted -or $agenticTaskNeedsUpdate) {
        Register-AgenticWindowsNodeSupervisorTask `
            -Path $agenticTaskPath `
            -Name $agenticTaskName `
            -UserId $currentWindowsUser `
            -PowerShellPath $powerShellPath `
            -SupervisorPath $supervisorPath `
            -DedicatedStateDir $resolvedStateDir
        $agenticTaskChanged = $true
    }
    if ($localConfigExisted) {
        Copy-Item -LiteralPath $localConfigPath -Destination $localConfigBackup -Force
    }
    $localSnapshotReady = $true

$gatewaySnapshot = @"
set -euo pipefail
trap 'rm -rf "$gatewayConfigBackup" "$gatewayPluginBackup"' ERR
test -f '$gatewayConfigPath'
cp -a '$gatewayConfigPath' '$gatewayConfigBackup'
rm -rf '$gatewayPluginBackup'
if [ -e '$gatewayPluginStage' ]; then
  cp -a '$gatewayPluginStage' '$gatewayPluginBackup'
  printf 'present'
else
  printf 'absent'
fi
"@
    $gatewayPluginExisted = (((Invoke-GatewayBash -Command $gatewaySnapshot) -join "").Trim() -eq "present")
    $gatewaySnapshotReady = $true

    foreach ($path in @($pluginNext, $pluginPrevious)) {
        if (Test-Path -LiteralPath $path) {
            Remove-Item -LiteralPath $path -Recurse -Force
        }
    }

    New-Item -ItemType Directory -Path $pluginNext -Force | Out-Null
    foreach ($entry in @("package.json", "openclaw.plugin.json", "README.md", "dist", "test")) {
        Copy-Item -LiteralPath (Join-Path $pluginSource $entry) -Destination $pluginNext -Recurse -Force
    }
    Push-Location $pluginNext
    try {
        & npm.cmd test
        if ($LASTEXITCODE -ne 0) { throw "Windows node plugin tests failed." }
    } finally {
        Pop-Location
    }

    if (Test-Path -LiteralPath $pluginStage) {
        Move-Item -LiteralPath $pluginStage -Destination $pluginPrevious
    }
    Move-Item -LiteralPath $pluginNext -Destination $pluginStage
    $promoted = $true

    $env:OPENCLAW_STATE_DIR = $resolvedStateDir
    $pluginList = ((Invoke-LocalOpenClaw plugins list --json) -join "`n") | ConvertFrom-Json
    $existing = @($pluginList.plugins | Where-Object id -eq $PluginId)
    if ($existing.Count -eq 0) {
        Invoke-LocalOpenClaw plugins install --link $pluginStage | Out-Null
    } elseif ($existing.Count -ne 1 -or $existing[0].rootDir -ne $pluginStage) {
        throw "Plugin '$PluginId' is registered from an unexpected path in the dedicated node state."
    }
    Invoke-LocalOpenClaw plugins enable $PluginId | Out-Null

    $nodeConfig = [ordered]@{
        windowsNodeEnabled = $true
        windowsNodeId = $NodeId
        windowsVoiceSessionKey = $voiceSessionKey
        windowsBrokerKeyId = $BrokerKeyId
        windowsBrokerPublicKeyBase64Url = $BrokerPublicKeyBase64Url
        windowsReadRoots = [ordered]@{ documents = $resolvedReadRoot }
    }
    foreach ($entry in $nodeConfig.GetEnumerator()) {
        $json = $entry.Value | ConvertTo-Json -Compress -Depth 6
        Invoke-LocalOpenClaw config set "plugins.entries.$PluginId.config.$($entry.Key)" $json --strict-json | Out-Null
    }
    Invoke-LocalOpenClaw config set nodeHost.browserProxy.enabled false --strict-json | Out-Null
    Invoke-LocalOpenClaw config validate | Out-Null
    $configuredVoiceSessionKey = ((Invoke-LocalOpenClaw config get "plugins.entries.$PluginId.config.windowsVoiceSessionKey") -join "`n").Trim().Trim('"')
    if ($configuredVoiceSessionKey -ne $voiceSessionKey) {
        throw "Dedicated Windows node voice-session binding does not match the Android session key."
    }

    & (Join-Path $PSScriptRoot "configure-assistant-capability-broker.ps1") -Distro $Distro

    $gatewayNodes = ((Invoke-GatewayOpenClaw config get gateway.nodes) -join "`n") | ConvertFrom-Json
    $allowCommands = @(@($gatewayNodes.allowCommands) + $WindowsCommand) |
        Where-Object { $_ -notin $RestrictedNodeCommands } |
        Sort-Object -Unique
    $denyCommands = @(@($gatewayNodes.denyCommands) + $RestrictedNodeCommands) | Sort-Object -Unique
    Invoke-GatewayOpenClaw config set gateway.nodes.allowCommands ($allowCommands | ConvertTo-Json -Compress) --strict-json | Out-Null
    Invoke-GatewayOpenClaw config set gateway.nodes.denyCommands ($denyCommands | ConvertTo-Json -Compress) --strict-json | Out-Null

    $brokerPrefix = "plugins.entries.$PluginId.config"
    Invoke-GatewayOpenClaw config set "$brokerPrefix.windowsFileSearchEnabled" true --strict-json | Out-Null
    Invoke-GatewayOpenClaw config set "$brokerPrefix.windowsFileReadEnabled" true --strict-json | Out-Null
    Invoke-GatewayOpenClaw config set "$brokerPrefix.windowsFileAgentId" ('"' + $AgentId + '"') --strict-json | Out-Null
    Invoke-GatewayOpenClaw config set "$brokerPrefix.windowsNodeId" ('"' + $NodeId + '"') --strict-json | Out-Null

    $configuredAgents = @((((Invoke-GatewayOpenClaw config get agents.list) -join "`n") | ConvertFrom-Json))
    $agentIndex = -1
    for ($index = 0; $index -lt $configuredAgents.Count; $index++) {
        if ([string]$configuredAgents[$index].id -eq $AgentId) { $agentIndex = $index; break }
    }
    if ($agentIndex -lt 0) { throw "Agent '$AgentId' is missing from agents.list." }
    $alsoAllow = @(@($configuredAgents[$agentIndex].tools.alsoAllow) + $WindowsTools) | Sort-Object -Unique
    Invoke-GatewayOpenClaw config set "agents.list[$agentIndex].tools.alsoAllow" ($alsoAllow | ConvertTo-Json -Compress) --strict-json | Out-Null
    Invoke-GatewayOpenClaw config validate | Out-Null
    Invoke-GatewayOpenClaw gateway restart | Out-Null

    Start-ScheduledTask -TaskPath $agenticTaskPath -TaskName $agenticTaskName
    Start-Sleep -Seconds 10
    $runningAgenticTask = Get-ScheduledTask -TaskPath $agenticTaskPath -TaskName $agenticTaskName
    if ([string]$runningAgenticTask.State -ne "Running" -or
        -not (Test-AgenticWindowsNodeSupervisorLockHeld -LockPath $lockPath)) {
        throw "The owned S4U Windows node supervisor did not remain running or acquire its state lock."
    }
    $agenticTaskStopped = $false

    # The existing logon task now asks Task Scheduler to run the same owned S4U task.
    Start-ScheduledTask -TaskName $TaskName
    Start-Sleep -Seconds 2
    $runningAgenticTask = Get-ScheduledTask -TaskPath $agenticTaskPath -TaskName $agenticTaskName
    if ([string]$runningAgenticTask.State -ne "Running" -or
        -not (Test-AgenticWindowsNodeSupervisorLockHeld -LockPath $lockPath)) {
        throw "The logon fallback disrupted the owned S4U Windows node task."
    }
    $taskStopped = $false

    $status = ((Invoke-GatewayOpenClaw nodes status --json) -join "`n") | ConvertFrom-Json
    $node = @($status.nodes | Where-Object nodeId -eq $NodeId)
    if ($node.Count -ne 1 -or $node[0].connected -ne $true) {
        throw "Dedicated Windows node did not reconnect after provisioning. Check 'openclaw nodes pending'."
    }
    $commands = @($node[0].commands | Sort-Object -Unique)
    if ($commands.Count -ne 1 -or $commands[0] -ne $WindowsCommand) {
        throw "Dedicated Windows node exposed an unexpected command surface: $($commands -join ', ')."
    }

    if (Test-Path -LiteralPath $pluginPrevious) {
        Remove-Item -LiteralPath $pluginPrevious -Recurse -Force
    }
    $provisioningSucceeded = $true
    Write-Host "Windows file bridge is active for '$AgentId' on node $NodeId with root alias 'documents'."
} catch {
    $provisioningError = $_
    $rollbackError = $null
    try {
        Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        Stop-ScheduledTask -TaskPath $agenticTaskPath -TaskName $agenticTaskName -ErrorAction SilentlyContinue
        $agenticTaskStopped = $true
        Stop-OwnedWindowsNodeProcesses `
            -NodeCommandPath $nodeCommandPath `
            -SupervisorPath $supervisorPath `
            -StateDir $resolvedStateDir | Out-Null
        $taskStopped = $true
        if ($promoted) {
            if (Test-Path -LiteralPath $pluginStage) {
                Remove-Item -LiteralPath $pluginStage -Recurse -Force
            }
            if ($pluginStageExisted -and (Test-Path -LiteralPath $pluginPrevious)) {
                Move-Item -LiteralPath $pluginPrevious -Destination $pluginStage
            }
        }

        if ($localSnapshotReady) {
            if ($localConfigExisted) {
                if (-not (Test-Path -LiteralPath $localConfigBackup -PathType Leaf)) {
                    throw "Local config rollback snapshot is missing."
                }
                Copy-Item -LiteralPath $localConfigBackup -Destination $localConfigPath -Force
            } elseif (Test-Path -LiteralPath $localConfigPath) {
                Remove-Item -LiteralPath $localConfigPath -Force
            }
        }

        if ($gatewaySnapshotReady) {
$restoreGateway = @"
set -euo pipefail
test -f '$gatewayConfigBackup'
rm -rf '$gatewayPluginStage' '$gatewayPluginStage.next' '$gatewayPluginStage.previous'
if [ '$($gatewayPluginExisted.ToString().ToLowerInvariant())' = 'true' ]; then
  test -d '$gatewayPluginBackup'
  mv '$gatewayPluginBackup' '$gatewayPluginStage'
fi
cp -a '$gatewayConfigBackup' '$gatewayConfigPath'
"@
            Invoke-GatewayBash -Command $restoreGateway | Out-Null
            Invoke-GatewayOpenClaw config validate | Out-Null
            Invoke-GatewayOpenClaw gateway restart | Out-Null
        }

        if ($launchersChanged) {
            [IO.File]::WriteAllBytes($nodeVbsPath, $nodeVbsBackup)
            [IO.File]::WriteAllBytes($gatewayBootstrapPath, $gatewayBootstrapBackup)
            if ($supervisorExisted) {
                [IO.File]::WriteAllBytes($supervisorPath, $supervisorBackup)
            } elseif (Test-Path -LiteralPath $supervisorPath) {
                Remove-Item -LiteralPath $supervisorPath -Force
            }
        }
        if ($agenticTaskChanged) {
            Unregister-ScheduledTask -TaskPath $agenticTaskPath -TaskName $agenticTaskName -Confirm:$false -ErrorAction SilentlyContinue
            if ($agenticTaskExisted) {
                Register-ScheduledTask -TaskPath $agenticTaskPath -TaskName $agenticTaskName -Xml $agenticTaskBackup -Force | Out-Null
                Start-ScheduledTask -TaskPath $agenticTaskPath -TaskName $agenticTaskName
                $agenticTaskStopped = $false
            } else {
                Stop-ScheduledTask -TaskPath "\OpenClaw\" -TaskName "OpenClaw Gateway Supervisor" -ErrorAction SilentlyContinue
                Start-ScheduledTask -TaskPath "\OpenClaw\" -TaskName "OpenClaw Gateway Supervisor"
            }
        }
        $rollbackSucceeded = $true
    } catch {
        $rollbackError = $_
    }

    if ($rollbackError) {
        $rollbackFailed = $true
        Write-Warning "Rollback failed. Scheduled task '$TaskName' and the owned S4U node task will remain stopped."
        Write-Warning "Preserved local config snapshot: $localConfigBackup"
        Write-Warning "Preserved Gateway config snapshot: ${Distro}:$gatewayConfigBackup"
        Write-Warning "Preserved Gateway plugin snapshot: ${Distro}:$gatewayPluginBackup"
        Write-Warning "After manual restoration, validate and restart the Gateway, then run '$agenticTaskPath$agenticTaskName'."
        throw "Provisioning failed: $($provisioningError.Exception.Message) Rollback also failed: $($rollbackError.Exception.Message)"
    }
    throw $provisioningError
} finally {
    if (-not $rollbackFailed -and (Test-Path -LiteralPath $localConfigBackup)) {
        Remove-Item -LiteralPath $localConfigBackup -Force -ErrorAction SilentlyContinue
    }
    if ($gatewaySnapshotReady -and -not $rollbackFailed) {
        try {
            Invoke-GatewayBash -Command "rm -rf '$gatewayConfigBackup' '$gatewayPluginBackup'" | Out-Null
        } catch {
            Write-Warning "Could not remove Gateway rollback snapshots: $($_.Exception.Message)"
        }
    }
    if (Test-Path -LiteralPath $pluginNext) {
        Remove-Item -LiteralPath $pluginNext -Recurse -Force -ErrorAction SilentlyContinue
    }
    Complete-WindowsNodeTaskState `
        -TaskStopped $taskStopped `
        -ProvisioningSucceeded $provisioningSucceeded `
        -RollbackSucceeded $rollbackSucceeded `
        -StartTask { Start-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue } |
        Out-Null
    if ($agenticTaskExisted) {
        Complete-WindowsNodeTaskState `
            -TaskStopped $agenticTaskStopped `
            -ProvisioningSucceeded $provisioningSucceeded `
            -RollbackSucceeded $rollbackSucceeded `
            -StartTask { Start-ScheduledTask -TaskPath $agenticTaskPath -TaskName $agenticTaskName -ErrorAction SilentlyContinue } |
            Out-Null
    }
}
