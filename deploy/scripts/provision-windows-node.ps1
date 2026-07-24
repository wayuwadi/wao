param(
    [string]$Distribution = 'Ubuntu',
    [string]$InstallLocation = 'D:\WSL\Ubuntu-24.04'
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\wsl-common.ps1"

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

$wslConfigPath = Join-Path $env:USERPROFILE '.wslconfig'
if (-not (Test-Path -LiteralPath $wslConfigPath)) {
    $wslConfig = @'
[wsl2]
memory=8GB
processors=4
swap=2GB
vmIdleTimeout=-1

[experimental]
autoMemoryReclaim=gradual
'@
    [IO.File]::WriteAllText(
        $wslConfigPath,
        ($wslConfig.Replace("`r`n", "`n") + "`n"),
        [Text.UTF8Encoding]::new($false)
    )
}

& wsl.exe --version *> $null
if ($LASTEXITCODE -ne 0) {
    & winget.exe install --id 9P9TQF7MRM4R --exact --source msstore `
        --accept-package-agreements --accept-source-agreements --silent --disable-interactivity
    if ($LASTEXITCODE -ne 0) { throw 'WSL runtime installation failed.' }
}

$installed = @(& wsl.exe --list --quiet) -replace "`0", '' | Where-Object { $_.Trim() -eq $Distribution }
if (-not $installed) {
    New-Item -ItemType Directory -Path $InstallLocation -Force | Out-Null
    & wsl.exe --install --distribution $Distribution --no-launch --web-download --location $InstallLocation
    if ($LASTEXITCODE -ne 0 -and $Distribution -eq 'Ubuntu') {
        Write-Warning 'The WSL distribution catalog is unavailable; falling back to the Microsoft Store package.'
        & winget.exe install --id 9PDXGNCFSCZV --exact --source msstore `
            --accept-package-agreements --accept-source-agreements --silent --disable-interactivity
        if ($LASTEXITCODE -ne 0) { throw 'Ubuntu Store package installation failed.' }
        & ubuntu.exe install --root
        if ($LASTEXITCODE -ne 0) { throw 'Ubuntu distribution initialization failed.' }
        & wsl.exe --shutdown
        & wsl.exe --manage Ubuntu --move $InstallLocation
    }
    if ($LASTEXITCODE -ne 0) { throw 'WSL distribution installation failed.' }
}

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$LinuxProjectRoot = ConvertTo-WaoWslPath $ProjectRoot
& wsl.exe -d $Distribution -u root -- bash -lc "printf '[boot]\nsystemd=true\n' >/etc/wsl.conf"
& wsl.exe --shutdown
Start-Sleep -Seconds 3
& wsl.exe -d $Distribution -u root -- bash -lc "cd '$LinuxProjectRoot' && sh deploy/scripts/install-docker-engine.sh"
if ($LASTEXITCODE -ne 0) { throw 'Docker Engine installation failed.' }

& "$PSScriptRoot\install-wsl-keepalive.ps1" -Distribution $Distribution

Write-Host 'WSL2 and Docker Engine are ready.'
