param(
    [string]$ArchivePath = 'D:\deploy\wao-release.tgz',
    [string]$TargetPath = 'D:\deploy\wao'
)

$ErrorActionPreference = 'Stop'
$archive = [IO.Path]::GetFullPath($ArchivePath)
$target = [IO.Path]::GetFullPath($TargetPath)
$targetRoot = [IO.Path]::GetPathRoot($target)

if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) {
    throw "Release archive does not exist: $archive"
}

if ($target -eq $targetRoot) {
    throw 'TargetPath must not be a drive root.'
}

New-Item -ItemType Directory -Path $target -Force | Out-Null
& tar.exe -xzf $archive -C $target
if ($LASTEXITCODE -ne 0) { throw 'Release extraction failed.' }

$deployScript = Join-Path $target 'deploy\scripts\deploy-windows.ps1'
if (-not (Test-Path -LiteralPath $deployScript -PathType Leaf)) {
    throw "Deployment script was not found after extraction: $deployScript"
}

& $deployScript
