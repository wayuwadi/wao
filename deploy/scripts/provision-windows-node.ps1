param(
    [string]$Distribution = 'Ubuntu-24.04',
    [string]$InstallLocation = 'D:\WSL\Ubuntu-24.04'
)

$ErrorActionPreference = 'Stop'

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script from an elevated PowerShell session.'
}

$restartRequired = $false
foreach ($featureName in @('Microsoft-Windows-Subsystem-Linux', 'VirtualMachinePlatform')) {
    $feature = Get-WindowsOptionalFeature -Online -FeatureName $featureName
    if ($feature.State -ne 'Enabled') {
        Enable-WindowsOptionalFeature -Online -FeatureName $featureName -All -NoRestart | Out-Null
        $restartRequired = $true
    }
}

if ($restartRequired) {
    Write-Warning 'WSL features were enabled. Restart Windows, then run this script again.'
    exit 3010
}

$installed = @(& wsl.exe --list --quiet) -replace "`0", '' | Where-Object { $_.Trim() -eq $Distribution }
if (-not $installed) {
    New-Item -ItemType Directory -Path $InstallLocation -Force | Out-Null
    & wsl.exe --install --distribution $Distribution --no-launch --web-download --location $InstallLocation
    if ($LASTEXITCODE -ne 0) { throw 'WSL distribution installation failed.' }
}

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$LinuxProjectRoot = (& wsl.exe -d $Distribution -u root -- wslpath -a $ProjectRoot).Trim()
& wsl.exe -d $Distribution -u root -- bash -lc "printf '[boot]\nsystemd=true\n' >/etc/wsl.conf"
& wsl.exe --shutdown
Start-Sleep -Seconds 3
& wsl.exe -d $Distribution -u root -- bash -lc "cd '$LinuxProjectRoot' && sh deploy/scripts/install-docker-engine.sh"
if ($LASTEXITCODE -ne 0) { throw 'Docker Engine installation failed.' }

Write-Host 'WSL2 and Docker Engine are ready.'
