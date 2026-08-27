param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9.-]+$')]
    [string]$JumpServerHost
)

$ErrorActionPreference = 'Stop'
$ruleName = 'WAO-JumpServer-RDP'

[System.Net.IPAddress]$parsedAddress = $null
$remoteAddresses = if ([System.Net.IPAddress]::TryParse($JumpServerHost, [ref]$parsedAddress)) {
    @($parsedAddress.IPAddressToString)
} else {
    @(Resolve-DnsName -Name $JumpServerHost -Type A -ErrorAction Stop |
        Where-Object { $_.IPAddress } |
        Select-Object -ExpandProperty IPAddress -Unique)
}

if ($remoteAddresses.Count -eq 0) {
    throw "JumpServer host did not resolve to an IPv4 address: $JumpServerHost"
}

Set-ItemProperty -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\Terminal Server' -Name 'fDenyTSConnections' -Value 0

$existingRule = Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue
if ($null -eq $existingRule) {
    $firewallParams = @{
        Name = $ruleName
        DisplayName = 'WAO JumpServer RDP'
        Description = 'Allow Windows RDP only from the current JumpServer host.'
        Enabled = 'True'
        Direction = 'Inbound'
        Action = 'Allow'
        Profile = 'Any'
        Protocol = 'TCP'
        LocalPort = 3389
        RemoteAddress = $remoteAddresses
    }
    New-NetFirewallRule @firewallParams | Out-Null
} else {
    $existingRule | Set-NetFirewallRule -Enabled True -Direction Inbound -Action Allow -Profile Any
    $existingRule | Get-NetFirewallAddressFilter | Set-NetFirewallAddressFilter -RemoteAddress $remoteAddresses
    $existingRule | Get-NetFirewallPortFilter | Set-NetFirewallPortFilter -Protocol TCP -LocalPort 3389
}

Set-Service -Name TermService -StartupType Automatic
Start-Service -Name TermService

$deadline = (Get-Date).AddSeconds(20)
do {
    $listener = Get-NetTCPConnection -State Listen -LocalPort 3389 -ErrorAction SilentlyContinue
    if ($listener) {
        break
    }
    Start-Sleep -Seconds 1
} while ((Get-Date) -lt $deadline)

if (-not $listener) {
    throw 'Windows Remote Desktop did not listen on port 3389.'
}

Write-Host "Windows RDP is enabled for JumpServer addresses only: $($remoteAddresses -join ', ')"
