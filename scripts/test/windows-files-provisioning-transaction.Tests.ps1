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
