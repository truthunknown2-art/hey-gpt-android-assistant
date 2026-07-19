[CmdletBinding()]
param(
    [string]$Distro = "OpenClawGateway",
    [int]$Port = 18789,
    [string]$ListenAddress = "0.0.0.0"
)

$ErrorActionPreference = "Stop"

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Run this script from an elevated PowerShell window."
}

$wslIp = (& wsl.exe -d $Distro -- sh -lc "hostname -I | awk '{print `$1}'").Trim()
if ($LASTEXITCODE -ne 0 -or $wslIp -notmatch '^\d{1,3}(\.\d{1,3}){3}$') {
    throw "Could not determine the IPv4 address for WSL distro '$Distro'."
}

# Recreate only this port-proxy entry so rerunning the script is safe after a
# WSL restart changes the distro's private address.
& netsh.exe interface portproxy delete v4tov4 listenport=$Port listenaddress=$ListenAddress | Out-Null
& netsh.exe interface portproxy add v4tov4 listenport=$Port listenaddress=$ListenAddress connectport=$Port connectaddress=$wslIp
if ($LASTEXITCODE -ne 0) {
    throw "netsh failed to create the OpenClaw port proxy."
}

$ruleName = "OpenClaw Gateway $Port (Private LAN)"
Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue |
    Remove-NetFirewallRule -ErrorAction SilentlyContinue

New-NetFirewallRule `
    -DisplayName $ruleName `
    -Direction Inbound `
    -Action Allow `
    -Protocol TCP `
    -LocalPort $Port `
    -Profile Private `
    -RemoteAddress LocalSubnet | Out-Null

Write-Host "OpenClaw gateway forwarding is ready: Windows TCP $Port -> $wslIp`:$Port"
Write-Host "Rerun this script after a WSL networking reset or if the WSL address changes."
