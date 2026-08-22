param(
    [string]$Distribution = 'Ubuntu'
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\wsl-common.ps1"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$LinuxProjectRoot = ConvertTo-WaoWslPath $ProjectRoot

Invoke-WaoWsl `
    -WslArguments @('-d', $Distribution, '-u', 'root', '--', 'bash', '-lc', "cd '$LinuxProjectRoot' && docker compose --env-file deploy/.env -f deploy/docker-compose-slave.yml exec -T mysql-slave sh /opt/wao/backup-mysql.sh") `
    -FailureMessage 'MySQL backup failed.'
