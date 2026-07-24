param(
    [string]$Distribution = 'Ubuntu-24.04'
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$LinuxProjectRoot = (& wsl.exe -d $Distribution -u root -- wslpath -a $ProjectRoot).Trim()

& wsl.exe -d $Distribution -u root -- bash -lc "cd '$LinuxProjectRoot' && sh deploy/scripts/generate-gateway-cert.sh"
if ($LASTEXITCODE -ne 0) { throw 'Gateway certificate generation failed.' }
