param(
    [string]$Distribution = 'Ubuntu-24.04',
    [string]$ListenAddress = '0.0.0.0'
)

$ErrorActionPreference = 'Stop'
$ports = @(3306, 6379, 8080, 9000, 9876, 26379)
$wslAddress = ((& wsl.exe -d $Distribution -u root -- hostname -I).Trim() -split '\s+')[0]

if (-not $wslAddress) { throw 'Unable to determine the WSL IPv4 address.' }

foreach ($port in $ports) {
    & netsh.exe interface portproxy delete v4tov4 listenaddress=$ListenAddress listenport=$port | Out-Null
    & netsh.exe interface portproxy add v4tov4 listenaddress=$ListenAddress listenport=$port connectaddress=$wslAddress connectport=$port
}

$ruleName = 'WAO WSL services over public network'
Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule `
    -DisplayName $ruleName `
    -Direction Inbound `
    -Action Allow `
    -Protocol TCP `
    -LocalPort $ports | Out-Null

Write-Warning 'WAO service ports are reachable from any network allowed by upstream router/firewall rules.'
Write-Host "Forwarding WAO ports from $ListenAddress to WSL $wslAddress."
