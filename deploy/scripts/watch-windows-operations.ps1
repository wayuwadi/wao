param(
    [Parameter(Mandatory=$true)][string]$ConfigPath,
    [switch]$TestNotification
)
$ErrorActionPreference = 'Stop'
# Scheduled once a minute under SYSTEM. Config/state/inbox must be private, never in the source checkout.
function Write-AtomicJson($Path, $Value) {
    $temporary = "$Path.$([guid]::NewGuid().ToString('N')).tmp"
    [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 15), (New-Object Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}
function Test-Endpoint([string]$Url, [string]$Kind) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 8
        if ($response.StatusCode -ne 200) { return $false }
        if ($Kind -eq 'edge') {
            $payload = $response.Content | ConvertFrom-Json
            return ($payload.code -eq 0 -and $payload.data.protocol -eq 1)
        }
        if ($Kind -eq 'actuator') { return (($response.Content | ConvertFrom-Json).status -eq 'UP') }
        return $true
    } catch { return $false }
}
function Add-Event([string]$Status, [string]$Title, [string]$Body) {
    $script:state.pending = @($script:state.pending) + @(@{
        id=[guid]::NewGuid().ToString(); status=$Status; title=$Title; body=$Body;
        occurredAt=[DateTimeOffset]::UtcNow.ToString('o')
    })
}
$config = Get-Content -LiteralPath $ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
$directory = Split-Path -Parent ([IO.Path]::GetFullPath($ConfigPath))
$statePath = Join-Path $directory 'state.json'
$lock = $null
try {
    $lock = [IO.File]::Open((Join-Path $directory 'monitor.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    if (Test-Path $statePath) {
        $saved = Get-Content $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
        $script:state = @{ checks=@{}; pending=@($saved.pending); lastBuild=$saved.lastBuild }
        foreach ($p in $saved.checks.PSObject.Properties) {
            $script:state.checks[$p.Name] = @{ failures=$p.Value.failures; successes=$p.Value.successes; alerted=$p.Value.alerted }
        }
    } else { $script:state = @{ checks=@{}; pending=@(); lastBuild='' } }
    if ($TestNotification) {
        Add-Event 'TEST' '运维通知链路测试' '这是明确标记的测试通知。用于验证 Windows 监控到 WAO 通知中心的持久化投递，不代表服务故障。'
    } else {
        $checks = @{}
        foreach ($name in $config.services) {
            $service = Get-Service -Name $name -ErrorAction SilentlyContinue
            $checks["服务 $name"] = ($null -ne $service -and $service.Status -eq 'Running')
        }
        $deploying = $false
        $requestPath = Join-Path $config.runtimeRoot 'queue/request.json'
        $resultPath = Join-Path $config.runtimeRoot 'queue/result.json'
        if (Test-Path $requestPath) {
            $request = Get-Content $requestPath -Raw -Encoding UTF8 | ConvertFrom-Json
            $result = if (Test-Path $resultPath) { Get-Content $resultPath -Raw -Encoding UTF8 | ConvertFrom-Json } else { $null }
            $deploying = ($request.id -ne $result.id -and (Get-Item $requestPath).LastWriteTimeUtc -gt [DateTime]::UtcNow.AddMinutes(-10))
            if ($result -and $result.status -eq 'FAILED') { $checks['最近一次部署'] = $false }
            elseif ($result -and $result.status -eq 'SUCCESS') { $checks['最近一次部署'] = $true }
        }
        foreach ($endpoint in $config.endpoints) {
            if ($deploying -and $endpoint.deploymentSensitive) { continue }
            $checks[$endpoint.name] = Test-Endpoint $endpoint.url $endpoint.kind
        }
        if ($config.jenkinsAccessPath) {
            try {
                $access = Get-Content $config.jenkinsAccessPath -Raw -Encoding UTF8 | ConvertFrom-Json
                $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($access.username + ':' + $access.password))
                $headers = @{ Authorization="Basic $encoded" }
                $job = Invoke-RestMethod -Uri ($config.jenkinsJobUrl.TrimEnd('/') + '/lastCompletedBuild/api/json?tree=number,result') -Headers $headers -TimeoutSec 8
                $checks['Jenkins 构建状态读取'] = $true
                $checks['最近一次 Jenkins 构建'] = ($job.result -eq 'SUCCESS' -or $job.result -eq 'ABORTED' -or $job.result -eq 'NOT_BUILT')
                $script:state.lastBuild = [string]$job.number
            } catch { $checks['Jenkins 构建状态读取'] = $false }
            finally { $headers=$null; $encoded=$null; $access=$null }
        }
        foreach ($key in $checks.Keys) {
            if (!$script:state.checks.ContainsKey($key)) { $script:state.checks[$key] = @{ failures=0; successes=0; alerted=$false } }
            $check = $script:state.checks[$key]
            if ($checks[$key]) {
                $check.failures=0; $check.successes++
                if ($check.alerted -and $check.successes -ge 2) {
                    Add-Event 'RECOVERY' "已恢复：$key" '连续两次检查恢复正常。'
                    $check.alerted=$false
                }
            } else {
                $check.successes=0; $check.failures++
                if (!$check.alerted -and $check.failures -ge 2) {
                    Add-Event 'FAILURE' "异常：$key" '连续两次检查异常，请查看服务器或 Jenkins 日志。监控不会强制重启数据库或覆盖部署。'
                    $check.alerted=$true
                }
            }
        }
    }
    # Persist transition and stable event IDs before delivery. A crash can replay, never lose, an event.
    Write-AtomicJson $statePath $script:state
    foreach ($event in @($script:state.pending)) {
        Write-AtomicJson (Join-Path $config.inbox ($event.id + '.json')) $event
    }
    $script:state.pending=@()
    Write-AtomicJson $statePath $script:state
} finally { if ($lock) { $lock.Dispose() } }
