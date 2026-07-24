$ErrorActionPreference = 'Stop'
$Distribution = 'Ubuntu-24.04'
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$LinuxProjectRoot = (& wsl.exe -d $Distribution -u root -- wslpath -a $ProjectRoot).Trim()

& wsl.exe -d $Distribution -u root -- bash -lc "cd '$LinuxProjectRoot' && docker compose --env-file deploy/.env -f deploy/docker-compose-slave.yml exec -T mysql-slave sh /opt/wao/restore-drill.sh"
if ($LASTEXITCODE -ne 0) { throw 'MySQL restore drill failed.' }
