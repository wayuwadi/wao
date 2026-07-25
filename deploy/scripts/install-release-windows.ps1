param(
    [string]$ArchivePath = 'D:\deploy\wao-release.zip',
    [string]$TargetPath = 'D:\deploy\wao',
    [string]$FeishuEnvPath = 'D:\deploy\wao-feishu.env',
    [string]$ImageArchivePath,
    [string]$Distribution = 'Ubuntu'
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
switch ([IO.Path]::GetExtension($archive).ToLowerInvariant()) {
    '.zip' {
        Expand-Archive -LiteralPath $archive -DestinationPath $target -Force
    }
    '.tgz' {
        & tar.exe -xzf $archive -C $target
        if ($LASTEXITCODE -ne 0) { throw 'Release extraction failed.' }
    }
    default {
        throw "Unsupported release archive: $archive"
    }
}

$deployScript = Join-Path $target 'deploy\scripts\deploy-windows.ps1'
if (-not (Test-Path -LiteralPath $deployScript -PathType Leaf)) {
    throw "Deployment script was not found after extraction: $deployScript"
}

if (Test-Path -LiteralPath $FeishuEnvPath -PathType Leaf) {
    $privateEnv = Join-Path $target 'deploy\.env.feishu'
    Copy-Item -LiteralPath $FeishuEnvPath -Destination $privateEnv -Force
    Remove-Item -LiteralPath $FeishuEnvPath -Force
}

$imageLoaded = $false
if ($ImageArchivePath -and (Test-Path -LiteralPath $ImageArchivePath -PathType Leaf)) {
    . (Join-Path $target 'deploy\scripts\wsl-common.ps1')
    $linuxImageArchive = ConvertTo-WaoWslPath $ImageArchivePath
    & wsl.exe -d $Distribution -u root -- docker load -i $linuxImageArchive
    if ($LASTEXITCODE -ne 0) { throw 'Application image import failed.' }
    $imageLoaded = $true
}

& $deployScript -Distribution $Distribution -SkipImageBuild:$imageLoaded
