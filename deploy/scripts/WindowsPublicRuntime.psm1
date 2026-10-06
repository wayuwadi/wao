Set-StrictMode -Version Latest

function Write-RuntimeJson {
    param([string]$Path, $Value)
    $temporaryPath = "$Path.$PID.tmp"
    [IO.File]::WriteAllText($temporaryPath, ($Value | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporaryPath -Destination $Path -Force
}

function Write-RuntimeEvent {
    param([string]$Directory, [string]$Event, [string]$Component = '', [int]$ProcessId = 0, [string]$Reason = '')
    $value = @{at = [datetime]::UtcNow.ToString('o'); event = $Event; component = $Component; processId = $ProcessId; reason = $Reason}
    [IO.File]::AppendAllText((Join-Path $Directory 'public-runtime.events.jsonl'), (($value | ConvertTo-Json -Compress) + [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
}

function Get-OwnedRuntimeProcess {
    param([string]$Directory, [string]$Component)
    $path = Join-Path $Directory "$Component.process.json"
    if (!(Test-Path -LiteralPath $path)) { return $null }
    try {
        $state = Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
        $process = Get-Process -Id $state.processId -ErrorAction Stop
        if ($process.StartTime.ToUniversalTime().Ticks -ne ([datetime]$state.startTime).ToUniversalTime().Ticks) { return $null }
        $details = Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)" -ErrorAction Stop
        if ($Component -eq 'mysql') {
            if ($process.ProcessName -ne 'mysqld' -or !$details.CommandLine.Replace('/','\').Contains((Join-Path $Directory 'my.ini').Replace('/','\'))) { return $null }
        } else {
            if ($process.ProcessName -notin 'pwsh','powershell') { return $null }
            $launcher = if ($Component -eq 'public-relay') { 'run-relay.ps1' } elseif ($Component -eq 'backup-worker') { 'run-backup-worker.ps1' } else { 'run-public-component.ps1' }
            $expectedPath = Join-Path $Directory $launcher
            # Runtime launchers historically use both slash forms.
            $command = $details.CommandLine.Replace('/','\')
            if (!$command.Contains($expectedPath.Replace('/','\'))) { return $null }
            if ($Component -in 'public-server','public-gateway','public-agent') {
                $name = $Component.Substring(7)
                if ($command -notmatch "(?i)-Component\s+$name(?:\s|$)") { return $null }
            }
        }
        return $process
    } catch { return $null }
}

function Test-RuntimePortOccupied {
    param([int]$Port)
    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

function Start-RuntimeComponent {
    param([string]$Directory, [string]$Component, [string]$Pwsh, [string]$WorkingDirectory)
    if ($Component -eq 'mysql') {
        & (Join-Path $Directory 'start.ps1') -Component mysql | Out-Null
        $process = Get-OwnedRuntimeProcess $Directory mysql
        if (!$process) { throw 'Database launcher did not register an owned process' }
        return $process
    }
    $arguments = if ($Component -eq 'public-relay') {
        "-NoProfile -File `"$(Join-Path $Directory 'run-relay.ps1')`" -LocalGatewayPort 19090"
    } elseif ($Component -eq 'backup-worker') {
        "-NoProfile -File `"$(Join-Path $Directory 'run-backup-worker.ps1')`""
    } else {
        "-NoProfile -File `"$(Join-Path $Directory 'run-public-component.ps1')`" -Component $($Component.Substring(7))"
    }
    foreach ($kind in 'stdout','stderr') {
        $log = Join-Path $Directory "$Component.$kind.log"
        if (Test-Path -LiteralPath $log) {
            $history = Join-Path $Directory 'runtime-log-history'
            [IO.Directory]::CreateDirectory($history) | Out-Null
            Copy-Item -LiteralPath $log -Destination (Join-Path $history "$Component-$([datetime]::UtcNow.ToString('yyyyMMddTHHmmssfffffffZ')).$kind.log")
        }
    }
    $process = Start-Process -FilePath $Pwsh -ArgumentList $arguments -WorkingDirectory $WorkingDirectory -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $Directory "$Component.stdout.log") -RedirectStandardError (Join-Path $Directory "$Component.stderr.log")
    Write-RuntimeJson (Join-Path $Directory "$Component.process.json") @{processId = $process.Id; startTime = $process.StartTime.ToUniversalTime().ToString('o'); name = $Component}
    return $process
}

function Stop-OwnedRuntimeComponent {
    param([string]$Directory, [string]$Component)
    if ($Component -notin 'public-server','public-gateway','public-relay','public-agent') { throw 'Only public application components may be stopped' }
    $process = Get-OwnedRuntimeProcess $Directory $Component
    if (!$process) { return }
    $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($process.Id)" | Where-Object { $_.Name -in 'java.exe','ssh.exe' } | ForEach-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue })
    Stop-Process -InputObject $process -Force -ErrorAction Stop
    foreach ($child in $children) {
        $current = Get-Process -Id $child.Id -ErrorAction SilentlyContinue
        if ($current -and $current.StartTime.ToUniversalTime().Ticks -eq $child.StartTime.ToUniversalTime().Ticks) { Stop-Process -InputObject $current -Force -ErrorAction Stop }
    }
    Write-RuntimeEvent $Directory 'component-explicitly-stopped' $Component $process.Id
}

Export-ModuleMember -Function Write-RuntimeJson,Write-RuntimeEvent,Get-OwnedRuntimeProcess,Test-RuntimePortOccupied,Start-RuntimeComponent,Stop-OwnedRuntimeComponent
