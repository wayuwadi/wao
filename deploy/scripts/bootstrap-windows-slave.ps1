param(
    [Parameter(Mandatory = $true)][string]$SnapshotPath,
    [string]$ProjectRoot = 'D:\deploy\wao',
    [string]$Distribution = 'Ubuntu'
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\wsl-common.ps1"

$snapshot = [IO.Path]::GetFullPath($SnapshotPath)
if (-not (Test-Path -LiteralPath $snapshot -PathType Leaf)) {
    throw "Snapshot does not exist: $snapshot"
}

$linuxProjectRoot = ConvertTo-WaoWslPath $ProjectRoot
$linuxSnapshot = ConvertTo-WaoWslPath $snapshot
try {
    Invoke-WaoWsl `
        -WslArguments @('-d', $Distribution, '-u', 'root', '--', 'sh',
            "$linuxProjectRoot/deploy/scripts/bootstrap-mysql-slave.sh", $linuxSnapshot) `
        -FailureMessage 'MySQL replica bootstrap failed.'
    Invoke-WaoWsl `
        -WslArguments @('-d', $Distribution, '-u', 'root', '--', 'sh',
            "$linuxProjectRoot/deploy/scripts/validate-slave.sh") `
        -FailureMessage 'Slave validation failed after snapshot bootstrap.'
}
finally {
    Remove-Item -LiteralPath $snapshot -Force -ErrorAction SilentlyContinue
}
