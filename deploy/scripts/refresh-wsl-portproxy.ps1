param(
    [string]$Distribution = 'Ubuntu',
    [string]$ListenAddress = '0.0.0.0'
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\wsl-common.ps1"
$ports = @(3306, 6379, 8080, 9000, 9876, 26379)
$publicPorts = @(3306, 6379, 9000, 26379)
$localPorts = @(8080, 9876)
$wslOutput = Invoke-WaoWslCapture `
    -WslArguments @('-d', $Distribution, '-u', 'root', '--', 'hostname', '-I') `
    -FailureMessage 'Unable to query the WSL IPv4 address.'
$wslAddress = (($wslOutput -join ' ').Trim() -split '\s+')[0]

if (-not $wslAddress) { throw 'Unable to determine the WSL IPv4 address.' }

$ipHelper = Get-Service -Name iphlpsvc
if ($ipHelper.StartType -ne 'Automatic') {
    Set-Service -Name iphlpsvc -StartupType Automatic
}
if ($ipHelper.Status -ne 'Running') {
    Start-Service -Name iphlpsvc
}

foreach ($port in $ports) {
    & netsh.exe interface portproxy delete v4tov4 listenaddress=$ListenAddress listenport=$port | Out-Null
    & netsh.exe interface portproxy add v4tov4 listenaddress=$ListenAddress listenport=$port connectaddress=$wslAddress connectport=$port
}

$legacyRuleName = 'WAO WSL services over public network'
Get-NetFirewallRule -DisplayName $legacyRuleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
foreach ($ruleName in @('WAO WSL public services', 'WAO WSL local diagnostics')) {
    Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
}
New-NetFirewallRule `
    -DisplayName 'WAO WSL public services' `
    -Direction Inbound `
    -Action Allow `
    -Protocol TCP `
    -LocalPort $publicPorts | Out-Null
New-NetFirewallRule `
    -DisplayName 'WAO WSL local diagnostics' `
    -Direction Inbound `
    -Action Allow `
    -Protocol TCP `
    -LocalPort $localPorts `
    -RemoteAddress LocalSubnet | Out-Null

Write-Warning 'WAO service ports are reachable from any network allowed by upstream router/firewall rules.'
Write-Host "Forwarding WAO ports from $ListenAddress to WSL $wslAddress."
