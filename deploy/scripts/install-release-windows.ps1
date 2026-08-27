param(
    [string]$ArchivePath = 'D:\deploy\wao-release.zip',
    [string]$TargetPath = 'D:\deploy\wao',
    [string]$PrivateEnvPath = 'D:\deploy\wao-secrets.env',
    [string]$SharedEnvPath,
    [string]$GatewayCertificatePath,
    [string]$ImageArchivePath,
    [string]$ImageTag = '',
    [string]$Distribution = 'Ubuntu',
    [string]$LockPath = 'D:\deploy\wao-deploy.lock'
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
if ($ImageTag -and $ImageTag -notmatch '^[A-Za-z0-9_.-]+$') {
    throw 'ImageTag may contain only letters, digits, dots, underscores, and hyphens.'
}
foreach ($requiredTransfer in @(
    @{ Name = 'SharedEnvPath'; Path = $SharedEnvPath },
    @{ Name = 'GatewayCertificatePath'; Path = $GatewayCertificatePath },
    @{ Name = 'ImageArchivePath'; Path = $ImageArchivePath }
)) {
    if ($requiredTransfer.Path -and -not (Test-Path -LiteralPath $requiredTransfer.Path -PathType Leaf)) {
        throw "$($requiredTransfer.Name) does not exist: $($requiredTransfer.Path)"
    }
}

# GitHub 取消旧流水线时，远端 PowerShell 进程可能仍在收尾。跨进程独占锁确保两次发布不会
# 同时解压到同一目录或重建同一组容器；每次流水线的独立压缩包则避免上传阶段互相覆盖。
$lockStream = $null
$lockDeadline = [DateTime]::UtcNow.AddMinutes(10)
while ($null -eq $lockStream -and [DateTime]::UtcNow -lt $lockDeadline) {
    try {
        $lockStream = [IO.File]::Open($LockPath, [IO.FileMode]::OpenOrCreate,
            [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    }
    catch [IO.IOException] {
        Start-Sleep -Seconds 5
    }
}
if ($null -eq $lockStream) {
    throw 'Another Windows deployment is still running after waiting 10 minutes.'
}

try {

New-Item -ItemType Directory -Path $target -Force | Out-Null

# 解压前先清掉上一版源码：Expand-Archive 只覆盖同名文件，删除 / 改名过的文件会留在节点上，
# 混进 Docker 构建上下文（V18 撞号那次就是被留下的旧 migration 打挂的）。
# deploy\ 整个保留：节点私有的 .env / .env.secrets / certs / data 都在里面，
# 其中被 git 跟踪的文件随即被这次解压整份覆盖。
Get-ChildItem -LiteralPath $target -Force |
    Where-Object { $_.Name -ne 'deploy' } |
    Remove-Item -Recurse -Force

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

if (Test-Path -LiteralPath $PrivateEnvPath -PathType Leaf) {
    $privateEnv = Join-Path $target 'deploy\.env.secrets'
    Copy-Item -LiteralPath $PrivateEnvPath -Destination $privateEnv -Force
}

if ($SharedEnvPath) {
    Copy-Item -LiteralPath $SharedEnvPath -Destination (Join-Path $target 'deploy\.env') -Force
}

if ($GatewayCertificatePath) {
    $certificateDirectory = Join-Path $target 'deploy\certs'
    New-Item -ItemType Directory -Path $certificateDirectory -Force | Out-Null
    Copy-Item -LiteralPath $GatewayCertificatePath `
        -Destination (Join-Path $certificateDirectory 'gateway.p12') -Force
}

# Do not rely on the one-time provisioning state: Windows updates or a changed power plan can
# restore battery sleep, and a stopped sshd would block every later Jenkins repair deployment.
& (Join-Path $target 'deploy\scripts\configure-windows-node-availability.ps1')
& (Join-Path $target 'deploy\scripts\install-windows-ops-tasks.ps1') `
    -Distribution $Distribution `
    -ProjectRoot $target

$imageLoaded = $false
if ($ImageArchivePath) {
    . (Join-Path $target 'deploy\scripts\wsl-common.ps1')
    $linuxImageArchive = ConvertTo-WaoWslPath $ImageArchivePath
    & wsl.exe -d $Distribution -u root -- docker load -i $linuxImageArchive
    if ($LASTEXITCODE -ne 0) { throw 'Application image import failed.' }
    $imageLoaded = $true
}

& $deployScript -Distribution $Distribution -SkipImageBuild:$imageLoaded -ImageTag $ImageTag
if ($LASTEXITCODE -ne 0) { throw 'Windows deployment script failed.' }

Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue
}
finally {
    foreach ($privateTransfer in @($PrivateEnvPath, $SharedEnvPath, $GatewayCertificatePath, $ImageArchivePath)) {
        if ($privateTransfer) {
            Remove-Item -LiteralPath $privateTransfer -Force -ErrorAction SilentlyContinue
        }
    }
    $lockStream.Dispose()
}
