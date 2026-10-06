$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'WindowsPublicRuntime.psm1') -Force
$testDirectory = Join-Path ([IO.Path]::GetTempPath()) ('wao-runtime-test-' + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($testDirectory) | Out-Null
$owned = [Collections.Generic.List[Diagnostics.Process]]::new()
$pwsh = (Get-Process -Id $PID).Path
$checks = [Collections.Generic.List[string]]::new()
function Assert-Check([bool]$Condition,[string]$Name) { if (!$Condition) { throw "Failed: $Name" }; $checks.Add($Name) }
function Wait-Check([scriptblock]$Check,[int]$Seconds = 30) {
    $deadline = [datetime]::UtcNow.AddSeconds($Seconds)
    while ([datetime]::UtcNow -lt $deadline) { if (& $Check) { return }; Start-Sleep -Milliseconds 250 }
    throw 'Timed out waiting for isolated runtime'
}
try {
    [IO.File]::WriteAllText((Join-Path $testDirectory 'environment.ps1'), ('$WaoRepo = ' + "'$testDirectory'"))
    $worker = 'param([string]$Component,[int]$LocalGatewayPort); while ($true) { Start-Sleep -Seconds 1 }'
    foreach ($file in 'run-public-component.ps1','run-relay.ps1','run-backup-worker.ps1') { [IO.File]::WriteAllText((Join-Path $testDirectory $file),$worker) }
    # An isolated database launcher must never connect to or initialize a database.
    [IO.File]::WriteAllText((Join-Path $testDirectory 'start.ps1'), "throw 'Test database launch deliberately blocked'")
    $server = Start-RuntimeComponent $testDirectory public-server $pwsh $testDirectory
    $owned.Add($server)
    Wait-Check { Get-OwnedRuntimeProcess $testDirectory public-server }
    Assert-Check ((Get-OwnedRuntimeProcess $testDirectory public-server).Id -eq $server.Id) 'adopt-owned-script-with-exact-start-time'
    $record = Get-Content -Raw (Join-Path $testDirectory 'public-server.process.json') | ConvertFrom-Json
    $record.startTime = ([datetime]$record.startTime).AddSeconds(-1).ToString('o')
    Write-RuntimeJson (Join-Path $testDirectory 'public-server.process.json') $record
    Assert-Check ($null -eq (Get-OwnedRuntimeProcess $testDirectory public-server)) 'reject-reused-pid-or-stale-start-time'
    Write-RuntimeJson (Join-Path $testDirectory 'public-server.process.json') @{processId=$PID;startTime=(Get-Process -Id $PID).StartTime.ToUniversalTime().ToString('o')}
    Assert-Check ($null -eq (Get-OwnedRuntimeProcess $testDirectory public-server)) 'reject-wrong-launcher-even-with-valid-pid'
    Write-RuntimeJson (Join-Path $testDirectory 'public-server.process.json') @{processId=$server.Id;startTime=$server.StartTime.ToUniversalTime().ToString('o')}
    $watcher = Start-Process -FilePath $pwsh -ArgumentList "-NoProfile -File `"$PSScriptRoot/watch-windows-public-runtime.ps1`" -RuntimeDirectory `"$testDirectory`" -PollSeconds 1 -NoSleepGuard" -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $testDirectory 'test.stdout.log') -RedirectStandardError (Join-Path $testDirectory 'test.stderr.log')
    $owned.Add($watcher)
    Wait-Check { Test-Path -LiteralPath (Join-Path $testDirectory 'public-runtime.status.json') }
    Assert-Check ((Get-OwnedRuntimeProcess $testDirectory public-server).Id -eq $server.Id) 'watcher-adopts-existing-process-without-restart'
    $second = Start-Process -FilePath $pwsh -ArgumentList "-NoProfile -File `"$PSScriptRoot/watch-windows-public-runtime.ps1`" -RuntimeDirectory `"$testDirectory`" -Once -NoSleepGuard" -WindowStyle Hidden -PassThru
    $owned.Add($second); $second.WaitForExit(10000) | Out-Null
    Assert-Check ($second.HasExited -and $second.ExitCode -eq 0) 'exclusive-lease-rejects-second-watcher'
    $supervisorState = Get-Content -Raw (Join-Path $testDirectory 'public-supervisor.process.json') | ConvertFrom-Json
    Assert-Check ($supervisorState.processId -eq $watcher.Id) 'second-watcher-does-not-overwrite-owner'
    $relay = Get-OwnedRuntimeProcess $testDirectory public-relay
    Assert-Check ($null -ne $relay) 'missing-relay-started'
    Stop-Process -Id $relay.Id -Force
    Wait-Check { $next = Get-OwnedRuntimeProcess $testDirectory public-relay; $next -and $next.Id -ne $relay.Id } 35
    $newRelay = Get-OwnedRuntimeProcess $testDirectory public-relay
    $owned.Add($newRelay)
    Assert-Check ($newRelay.Id -ne $relay.Id) 'terminated-relay-recovers-after-backoff'
    $events = @(Get-Content (Join-Path $testDirectory 'public-runtime.events.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
    Assert-Check ([bool]($events | Where-Object { $_.event -eq 'component-exited' -and $_.component -eq 'public-relay' })) 'component-exit-evidence-recorded'
    # This test uses no database config; its failed/blocked start must not end a listening process.
    Assert-Check ([bool]($events | Where-Object { $_.component -eq 'mysql' -and $_.event -in 'component-blocked','component-start-failed' })) 'database-failure-is-contained'
    [IO.File]::WriteAllText((Join-Path $testDirectory 'public-runtime.paused'),'test')
    Wait-Check { $watcher.Refresh(); $watcher.HasExited } 10
    Assert-Check ([bool](Get-OwnedRuntimeProcess $testDirectory public-relay)) 'pause-retains-live-components'
    $paused = Start-Process -FilePath $pwsh -ArgumentList "-NoProfile -File `"$PSScriptRoot/watch-windows-public-runtime.ps1`" -RuntimeDirectory `"$testDirectory`" -Once -NoSleepGuard" -WindowStyle Hidden -PassThru
    $owned.Add($paused);$paused.WaitForExit(10000) | Out-Null
    Assert-Check ($paused.HasExited -and $paused.ExitCode -eq 0) 'scheduled-trigger-respects-explicit-pause'
    Stop-OwnedRuntimeComponent $testDirectory public-relay
    Assert-Check ($null -eq (Get-OwnedRuntimeProcess $testDirectory public-relay)) 'explicit-stop-ends-owned-component'
    Assert-Check ([bool](Get-OwnedRuntimeProcess $testDirectory public-server)) 'explicit-stop-preserves-other-components'
    [pscustomobject]@{passed=$checks.Count;checks=$checks;testDirectory=$testDirectory} | ConvertTo-Json -Depth 3
} finally {
    foreach ($component in 'public-relay','public-gateway','public-server','backup-worker') {
        $process = Get-OwnedRuntimeProcess $testDirectory $component
        if ($process) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
    }
    foreach ($process in $owned) { $process.Refresh(); if (!$process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue } }
    # Keep logs for evidence. Never recursively delete a computed directory.
}
