param(
    [string]$Distribution = 'Ubuntu-24.04'
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$LinuxProjectRoot = (& wsl.exe -d $Distribution -u root -- wslpath -a $ProjectRoot).Trim()

if (-not $LinuxProjectRoot) {
    throw "Unable to translate project path for WSL distribution $Distribution."
}

& wsl.exe -d $Distribution -u root -- bash -lc "cd '$LinuxProjectRoot' && sh deploy/scripts/deploy-linux.sh slave"
if ($LASTEXITCODE -ne 0) { throw 'WSL Docker Compose deployment failed.' }

& "$PSScriptRoot\refresh-wsl-portproxy.ps1" -Distribution $Distribution
