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
$deployAttempt = 1
while ($deployAttempt -le 2) {
    & wsl.exe -d $Distribution -u root -- bash -lc "cd '$LinuxProjectRoot' && ${buildPrefix}sh deploy/scripts/deploy-linux.sh slave"
    if ($LASTEXITCODE -eq 0) { break }
    if ($deployAttempt -ge 2) { throw 'WSL Docker Compose deployment failed after restarting the distribution.' }

    # BuildKit 偶发 EOF 后 Docker daemon 与 DrvFS 可能一起进入异常状态，直接重跑只会连续出现
    # Segmentation fault / Missing /mnt/d/.../.env。先终止发行版，让 systemd 和 Docker 干净重启；
    # 已构建的层缓存和 deploy/.env 均在持久磁盘上，下一次仍从原发布目录继续。
    Write-Warning 'WSL Docker deployment failed; restarting the distribution before one retry.'
    & wsl.exe --terminate $Distribution
    Start-Sleep -Seconds 10
    $deployAttempt++
}

& "$PSScriptRoot\refresh-wsl-portproxy.ps1" -Distribution $Distribution
