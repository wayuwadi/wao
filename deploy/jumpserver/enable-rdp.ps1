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
    throw "JumpServer 主机未解析到 IPv4 地址：$JumpServerHost"
}

Set-ItemProperty -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\Terminal Server' -Name 'fDenyTSConnections' -Value 0

$existingRule = Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue
if ($null -eq $existingRule) {
    $firewallParams = @{
        Name = $ruleName
        DisplayName = 'WAO JumpServer RDP'
        Description = '仅允许当前 JumpServer 主节点访问 Windows 远程桌面。'
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
    throw 'Windows 远程桌面服务未监听 3389 端口。'
}

Write-Host "Windows RDP 已启用，3389 仅允许 JumpServer 地址：$($remoteAddresses -join ', ')"
