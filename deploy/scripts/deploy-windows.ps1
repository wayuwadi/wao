param(
    [string]$Distribution = 'Ubuntu',
    [switch]$SkipImageBuild
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\wsl-common.ps1"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$LinuxProjectRoot = ConvertTo-WaoWslPath $ProjectRoot

if (-not $LinuxProjectRoot) {
    throw "Unable to translate project path for WSL distribution $Distribution."
}

$buildPrefix = if ($SkipImageBuild) { 'SKIP_IMAGE_BUILD=true ' } else { '' }
& wsl.exe -d $Distribution -u root -- bash -lc "cd '$LinuxProjectRoot' && ${buildPrefix}sh deploy/scripts/deploy-linux.sh slave"
if ($LASTEXITCODE -ne 0) { throw 'WSL Docker Compose deployment failed.' }

& "$PSScriptRoot\refresh-wsl-portproxy.ps1" -Distribution $Distribution
