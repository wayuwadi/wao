param()

$ErrorActionPreference = 'Stop'

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script from an elevated PowerShell session.'
}

function Invoke-PowerCfg {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    & powercfg.exe @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "powercfg failed: $($Arguments -join ' ')"
    }
}

# The slave is production infrastructure even when the laptop changes from AC to battery.
# Reassert both power modes on every release because Windows upgrades and power-plan changes
# can replace the active scheme after the one-time provisioning step.
foreach ($powerMode in @('ac', 'dc')) {
    Invoke-PowerCfg -Arguments @('/change', "standby-timeout-$powerMode", '0')
    Invoke-PowerCfg -Arguments @('/change', "hibernate-timeout-$powerMode", '0')
}

$sshd = Get-Service -Name 'sshd' -ErrorAction Stop
if ($sshd.StartType -ne 'Automatic') {
    Set-Service -Name 'sshd' -StartupType Automatic
}
if ($sshd.Status -ne 'Running') {
    Start-Service -Name 'sshd'
}

# A stopped OpenSSH service otherwise presents exactly like an offline node to Jenkins.
# Let the service manager recover three times, then reset the failure counter after one day.
& sc.exe failure sshd reset= 86400 actions= restart/60000/restart/60000/restart/60000 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Failed to configure sshd recovery actions.' }
& sc.exe failureflag sshd 1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Failed to enable sshd recovery for non-crash failures.' }

Write-Host 'Windows AC/DC sleep, hibernation and OpenSSH recovery are configured for continuous availability.'
