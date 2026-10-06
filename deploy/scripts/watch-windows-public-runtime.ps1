param(
    [Parameter(Mandatory)][string]$RuntimeDirectory,
    [ValidateRange(1,60)][int]$PollSeconds = 10,
    [switch]$Once,
    [switch]$NoSleepGuard
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'WindowsPublicRuntime.psm1') -Force
$RuntimeDirectory = (Resolve-Path -LiteralPath $RuntimeDirectory).Path
$pausePath = Join-Path $RuntimeDirectory 'public-runtime.paused'
if (Test-Path -LiteralPath $pausePath) { exit 0 }
try {
    $lease = [IO.File]::Open((Join-Path $RuntimeDirectory 'public-runtime.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
} catch [IO.IOException] { exit 0 }
$powerEnabled = $false
try {
    . (Join-Path $RuntimeDirectory 'environment.ps1')
    $runtimePwsh = (Get-Process -Id $PID).Path
    if (!$NoSleepGuard) {
        Add-Type @'
using System.Runtime.InteropServices;
public static class WaoRuntimePower {
 [DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags);
}
'@
        [void][WaoRuntimePower]::SetThreadExecutionState([uint32]2147483649)
        $powerEnabled = $true
    }
    Write-RuntimeJson (Join-Path $RuntimeDirectory 'public-supervisor.process.json') @{processId=$PID; startTime=(Get-Process -Id $PID).StartTime.ToUniversalTime().ToString('o'); name='public-supervisor'; host='windows-task-scheduler'}
    Write-RuntimeEvent $RuntimeDirectory 'supervisor-started' 'supervisor' $PID
    $tracked = @{}
    $retryAt = @{}
    $lastReady = $null
    $components = @('mysql','public-server','public-gateway','public-relay','backup-worker')
    if (Test-Path -LiteralPath (Join-Path $RuntimeDirectory 'public-artifacts/agent.jar')) { $components += 'public-agent' }
    $ports = @{mysql=3306; 'public-server'=18080; 'public-gateway'=19090; 'public-agent'=18081}
    do {
        if (Test-Path -LiteralPath $pausePath) { Write-RuntimeEvent $RuntimeDirectory 'supervisor-paused'; break }
        foreach ($component in $components) {
            $process = Get-OwnedRuntimeProcess $RuntimeDirectory $component
            if ($process) {
                if (!$tracked.ContainsKey($component) -or $tracked[$component] -ne $process.Id) {
                    Write-RuntimeEvent $RuntimeDirectory 'component-adopted' $component $process.Id
                    $tracked[$component] = $process.Id
                }
                continue
            }
            if ($tracked.ContainsKey($component)) {
                Write-RuntimeEvent $RuntimeDirectory 'component-exited' $component $tracked[$component]
                $tracked.Remove($component)
            }
            if ($retryAt.ContainsKey($component) -and [datetime]::UtcNow -lt $retryAt[$component]) { continue }
            if ($ports.ContainsKey($component) -and (Test-RuntimePortOccupied $ports[$component])) {
                Write-RuntimeEvent $RuntimeDirectory 'component-blocked' $component 0 'port-occupied-without-matching-owner'
                $retryAt[$component] = [datetime]::UtcNow.AddSeconds(60)
                continue
            }
            try {
                $process = Start-RuntimeComponent $RuntimeDirectory $component $runtimePwsh $WaoRepo
                $tracked[$component] = $process.Id
                Write-RuntimeEvent $RuntimeDirectory 'component-started' $component $process.Id
                $retryAt[$component] = [datetime]::UtcNow.AddSeconds(20)
            } catch {
                Write-RuntimeEvent $RuntimeDirectory 'component-start-failed' $component 0 $_.Exception.GetType().Name
                $retryAt[$component] = [datetime]::UtcNow.AddSeconds(60)
            }
        }
        $ready = $false
        try {
            $request = [Net.HttpWebRequest]::Create('http://127.0.0.1:19090/api/edge/ready')
            $request.Proxy = $null
            $request.Timeout = 3000
            $request.AllowAutoRedirect = $false
            $response = $request.GetResponse()
            try {
                $reader = [IO.StreamReader]::new($response.GetResponseStream())
                try { $result = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
            } finally { $response.Dispose() }
            $ready = $result.code -eq 0 -and $result.data.protocol -eq 1
        } catch {}
        if ($null -eq $lastReady -or $lastReady -ne $ready) {
            Write-RuntimeEvent $RuntimeDirectory $(if ($ready) {'gateway-ready'} else {'gateway-degraded'})
            $lastReady = $ready
        }
        Write-RuntimeJson (Join-Path $RuntimeDirectory 'public-runtime.status.json') @{checkedAt=[datetime]::UtcNow.ToString('o'); supervisorId=$PID; components=$tracked; ready=$ready}
        if (!$Once) { Start-Sleep -Seconds $PollSeconds }
    } while (!$Once)
} catch {
    Write-RuntimeEvent $RuntimeDirectory 'supervisor-failed' 'supervisor' $PID $_.Exception.GetType().Name
    exit 1
} finally {
    if ($powerEnabled) { [void][WaoRuntimePower]::SetThreadExecutionState([uint32]2147483648) }
    if ($null -ne $lease) { $lease.Dispose() }
}
