param(
    [ValidateSet('stop', 'start')]
    [string]$Action,
    [string]$Distribution = 'Ubuntu',
    [string]$ProjectRoot = '/mnt/d/deploy/wao',
    [string]$ScriptPath = '/mnt/d/deploy/wao-quiesce-server.sh'
)

$ErrorActionPreference = 'Stop'
& wsl.exe -d $Distribution -u root -- sh $ScriptPath slave $Action $ProjectRoot
if ($LASTEXITCODE -ne 0) {
    throw "Failed to $Action the WAO server in WSL."
}
