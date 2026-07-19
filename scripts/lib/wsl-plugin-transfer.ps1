function Send-PluginBundleToWsl {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Distro,
        [Parameter(Mandatory = $true)][string]$SourcePath,
        [Parameter(Mandatory = $true)][string]$DestinationPath,
        [Parameter(Mandatory = $true)][string[]]$Entries
    )

    if ($Distro -notmatch '^[A-Za-z0-9._-]+$') {
        throw "Distro contains unsupported characters."
    }
    if ($DestinationPath -notmatch '^/home/openclaw/\.openclaw/plugin-dev/[a-z0-9-]+\.next$') {
        throw "DestinationPath must be a temporary OpenClaw plugin stage."
    }

    $resolvedSource = (Resolve-Path -LiteralPath $SourcePath).Path
    foreach ($entry in $Entries) {
        if ($entry -notmatch '^[A-Za-z0-9._/-]+$' -or $entry.Contains('..')) {
            throw "Plugin entry '$entry' is unsafe."
        }
        if (-not (Test-Path -LiteralPath (Join-Path $resolvedSource $entry))) {
            throw "Plugin entry '$entry' does not exist under '$resolvedSource'."
        }
    }

    if ($PSVersionTable.PSVersion -lt [version]"7.4") {
        throw "Binary-safe WSL plugin transfer requires PowerShell 7.4 or newer (run this script with pwsh)."
    }

    $tar = (Get-Command tar.exe -ErrorAction Stop).Source
    Get-Command wsl.exe -ErrorAction Stop | Out-Null
    $prepare = "set -euo pipefail; rm -rf '$DestinationPath'; mkdir -p '$DestinationPath'; tar -xf - -C '$DestinationPath'"
    $previousNativePreference = $PSNativeCommandUseErrorActionPreference
    try {
        $PSNativeCommandUseErrorActionPreference = $true
        & $tar -cf - -C $resolvedSource @Entries |
            & wsl.exe -d $Distro -- bash -lc $prepare |
            Out-Null
    } finally {
        $PSNativeCommandUseErrorActionPreference = $previousNativePreference
    }
}
