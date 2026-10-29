$ErrorActionPreference = 'Stop'
$root = Join-Path ([IO.Path]::GetTempPath()) ('wao-monitor-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$root/inbox","$root/runtime/queue" -Force | Out-Null
$monitor = Join-Path $PSScriptRoot '../../deploy/scripts/watch-windows-operations.ps1'
@{ services=@(); runtimeRoot="$root/runtime"; inbox="$root/inbox"; endpoints=@(@{
    name='WAO';url='http://example.invalid/ready';kind='edge';deploymentSensitive=$true
}) } | ConvertTo-Json -Depth 5 | Set-Content "$root/config.json" -Encoding UTF8
$global:waoMonitorTestHealthy=$false
function Invoke-WebRequest {
    param($Uri,$TimeoutSec,[switch]$UseBasicParsing)
    if (!$global:waoMonitorTestHealthy) { throw 'Synthetic outage' }
    return @{StatusCode=200; Content=[Text.Encoding]::UTF8.GetBytes('{"code":0,"data":{"protocol":1}}')}
}
function Assert-Count($Expected) {
    $count=@(Get-ChildItem "$root/inbox" -Filter '*.json').Count
    if($count -ne $Expected){throw "Expected $Expected events, got $count"}
}
& $monitor -ConfigPath "$root/config.json"
Assert-Count 0
& $monitor -ConfigPath "$root/config.json"
Assert-Count 1
& $monitor -ConfigPath "$root/config.json"
Assert-Count 1
$global:waoMonitorTestHealthy=$true
& $monitor -ConfigPath "$root/config.json"
Assert-Count 1
& $monitor -ConfigPath "$root/config.json"
Assert-Count 2
$events=@(Get-ChildItem "$root/inbox" -Filter '*.json' | ForEach-Object {Get-Content $_.FullName -Raw -Encoding UTF8 | ConvertFrom-Json})
if('FAILURE' -notin $events.status -or 'RECOVERY' -notin $events.status){throw 'Missing transition'}
# Planned deployment suppresses application probes, then expires after ten minutes.
$global:waoMonitorTestHealthy=$false
@{id='pending'} | ConvertTo-Json | Set-Content "$root/runtime/queue/request.json"
& $monitor -ConfigPath "$root/config.json"
& $monitor -ConfigPath "$root/config.json"
Assert-Count 2
(Get-Item "$root/runtime/queue/request.json").LastWriteTimeUtc=[DateTime]::UtcNow.AddMinutes(-11)
& $monitor -ConfigPath "$root/config.json"
& $monitor -ConfigPath "$root/config.json"
Assert-Count 3
& $monitor -ConfigPath "$root/config.json" -TestNotification
Assert-Count 4
# Replaying a persisted pending event uses the same filename, not a new notification ID.
$state=Get-Content "$root/state.json" -Raw -Encoding UTF8 | ConvertFrom-Json
$state.pending=@($events[0])
$state | ConvertTo-Json -Depth 10 | Set-Content "$root/state.json"
& $monitor -ConfigPath "$root/config.json"
Assert-Count 4
Write-Host 'Windows operations monitor tests passed.'
# Remove only this test's verified unique directory.
if ((Split-Path $root -Leaf) -like 'wao-monitor-test-*' -and (Split-Path $root -Parent) -eq ([IO.Path]::GetTempPath()).TrimEnd('\','/')) {
    Remove-Item -LiteralPath $root -Recurse -Force
}
