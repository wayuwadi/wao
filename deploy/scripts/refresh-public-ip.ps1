param(
    [string]$Distribution = 'Ubuntu-24.04'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$linuxProjectRoot = (& wsl.exe -d $Distribution -u root -- wslpath -a $projectRoot).Trim()

& wsl.exe -d $Distribution -u root -- bash -lc "cd '$linuxProjectRoot' && sh deploy/scripts/refresh-public-ip.sh slave"
if ($LASTEXITCODE -ne 0) { throw 'Public IP refresh failed.' }
