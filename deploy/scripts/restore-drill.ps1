param(
    [string]$Distribution = 'Ubuntu',
    [ValidateRange(0, 31)][int]$OnlyOnDayOfMonth = 0
)

$ErrorActionPreference = 'Stop'
if ($OnlyOnDayOfMonth -gt 0 -and (Get-Date).Day -ne $OnlyOnDayOfMonth) {
    Write-Host "Restore drill is scheduled for day $OnlyOnDayOfMonth; nothing to do today."
    exit 0
}

. "$PSScriptRoot\wsl-common.ps1"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$LinuxProjectRoot = ConvertTo-WaoWslPath $ProjectRoot

Invoke-WaoWsl `
    -WslArguments @('-d', $Distribution, '-u', 'root', '--', 'bash', '-lc', "cd '$LinuxProjectRoot' && docker compose --env-file deploy/.env -f deploy/docker-compose-slave.yml exec -T mysql-slave sh /opt/wao/restore-drill.sh") `
    -FailureMessage 'MySQL restore drill failed.'
