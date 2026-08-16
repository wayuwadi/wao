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

    # BuildKit 偶发 EOF 后 Docker daemon、DrvFS 乃至 WSL 实例服务可能一起进入异常状态，
    # 只终止发行版无法恢复 Wsl/Service/CreateInstance/E_FAIL。专用从节点的 SSH 会话以管理员
    # 身份运行，优先重启 WSL 服务；找不到服务时再让 wsl.exe 关闭全部发行版。
    Write-Warning 'WSL Docker deployment failed; restarting the WSL runtime before one retry.'
    $wslService = Get-Service -Name 'WslService', 'LxssManager' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -ne $wslService) {
        Restart-Service -InputObject $wslService -Force
    } else {
        & wsl.exe --shutdown
    }
    Start-Sleep -Seconds 10
    $deployAttempt++
}

& "$PSScriptRoot\refresh-wsl-portproxy.ps1" -Distribution $Distribution
