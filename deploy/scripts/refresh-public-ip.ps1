param(
    [string]$Distribution = 'Ubuntu'
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\wsl-common.ps1"
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$linuxProjectRoot = ConvertTo-WaoWslPath $projectRoot

Invoke-WaoWsl `
    -WslArguments @('-d', $Distribution, '-u', 'root', '--', 'bash', '-lc', "cd '$linuxProjectRoot' && sh deploy/scripts/refresh-public-ip.sh slave") `
    -FailureMessage 'Public IP refresh failed.'
