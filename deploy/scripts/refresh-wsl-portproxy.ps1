param(
    [string]$Distribution = 'Ubuntu-24.04'
)

$ErrorActionPreference = 'Stop'
$ports = @(3306, 6379, 8080, 9000, 9876, 26379)
$wslAddress = ((& wsl.exe -d $Distribution -u root -- hostname -I).Trim() -split '\s+')[0]
$tailscaleAddress = (& tailscale.exe ip -4).Trim()

if (-not $wslAddress) { throw 'Unable to determine the WSL IPv4 address.' }
if (-not $tailscaleAddress) { throw 'Unable to determine the Tailscale IPv4 address.' }

foreach ($port in $ports) {
    & netsh.exe interface portproxy delete v4tov4 listenaddress=$tailscaleAddress listenport=$port | Out-Null
    & netsh.exe interface portproxy add v4tov4 listenaddress=$tailscaleAddress listenport=$port connectaddress=$wslAddress connectport=$port
}

$ruleName = 'WAO WSL services over Tailscale'
Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule `
    -DisplayName $ruleName `
    -Direction Inbound `
    -Action Allow `
    -Protocol TCP `
    -LocalAddress $tailscaleAddress `
    -RemoteAddress '100.64.0.0/10' `
    -LocalPort $ports | Out-Null

Write-Host "Forwarding WAO ports from $tailscaleAddress to WSL $wslAddress."
