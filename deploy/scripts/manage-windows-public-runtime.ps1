param(
    [Parameter(Mandatory)][ValidateSet('Install','Status','Pause','Resume','Stop')][string]$Action,
    [Parameter(Mandatory)][string]$RuntimeDirectory,
    [string]$TaskName = 'WAO-Public-Runtime',
    [switch]$RegisterOnly,
    [switch]$IntegrateLegacyLaunchers
)
$ErrorActionPreference = 'Stop'
if ($IntegrateLegacyLaunchers -and $TaskName -ne 'WAO-Public-Runtime') { throw 'Legacy launchers require the stable WAO task name' }
$RuntimeDirectory = (Resolve-Path -LiteralPath $RuntimeDirectory).Path
$watcher = Join-Path $RuntimeDirectory 'watch-windows-public-runtime.ps1'
$pausePath = Join-Path $RuntimeDirectory 'public-runtime.paused'
$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($task -and (($task.Actions.Arguments -join '') -notlike "*`"$watcher`"*")) { throw 'Existing task is not the expected WAO runtime task' }
switch ($Action) {
    'Install' {
        foreach ($file in 'WindowsPublicRuntime.psm1','watch-windows-public-runtime.ps1','manage-windows-public-runtime.ps1') {
            $source = Join-Path $PSScriptRoot $file
            $destination = Join-Path $RuntimeDirectory $file
            if ([IO.Path]::GetFullPath($source) -ne [IO.Path]::GetFullPath($destination)) { Copy-Item -LiteralPath $source -Destination $destination -Force }
        }
        $user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
        $systemPowerShell = Join-Path $env:WINDIR 'System32/WindowsPowerShell/v1.0/powershell.exe'
        $taskAction = New-ScheduledTaskAction -Execute $systemPowerShell -Argument "-NoProfile -ExecutionPolicy RemoteSigned -WindowStyle Hidden -File `"$watcher`" -RuntimeDirectory `"$RuntimeDirectory`"" -WorkingDirectory $RuntimeDirectory
        $triggers = @((New-ScheduledTaskTrigger -AtLogOn -User $user),(New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 1)))
        $settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
        Register-ScheduledTask -TaskName $TaskName -Action $taskAction -Trigger $triggers -Settings $settings -Principal $principal -Description 'WAO existing Windows public runtime; current-user supervision independent of Codex; no stored password' -Force | Out-Null
        if ($IntegrateLegacyLaunchers) {
            foreach ($item in @(@{name='start-public.ps1';action='Resume'},@{name='stop-public.ps1';action='Stop'})) {
                $path = Join-Path $RuntimeDirectory $item.name
                if (!(Test-Path -LiteralPath $path)) { continue }
                $old = [IO.File]::ReadAllText($path)
                if ($old.Contains('# System-owned runtime;')) { continue }
                $backupDirectory = Join-Path $RuntimeDirectory ('runtime-launcher-backup-' + [datetime]::UtcNow.ToString('yyyyMMddTHHmmssfffffffZ'))
                [IO.Directory]::CreateDirectory($backupDirectory) | Out-Null
                Copy-Item -LiteralPath $path -Destination (Join-Path $backupDirectory $item.name)
                $prefix = @'
# System-owned runtime; preserve existing configuration and fall back only when not installed.
$manager = Join-Path $PSScriptRoot 'manage-windows-public-runtime.ps1'
if (Test-Path -LiteralPath $manager) {
    $existingRuntimeTask = Get-ScheduledTask -TaskName 'WAO-Public-Runtime' -ErrorAction SilentlyContinue
    if ($existingRuntimeTask) {
        & $manager -Action %ACTION% -RuntimeDirectory $PSScriptRoot
        if (!$?) { exit 1 }
        exit 0
    }
}

'@
                [IO.File]::WriteAllText($path, ($prefix.Replace('%ACTION%',$item.action) + $old), [Text.UTF8Encoding]::new($true))
            }
        }
        if ($RegisterOnly) { Disable-ScheduledTask -TaskName $TaskName | Out-Null }
        elseif (!(Test-Path -LiteralPath $pausePath)) { Start-ScheduledTask -TaskName $TaskName }
    }
    'Pause' {
        [IO.File]::WriteAllText($pausePath,[datetime]::UtcNow.ToString('o'))
        # The watcher exits on its next poll without killing adopted services.
    }
    'Stop' {
        [IO.File]::WriteAllText($pausePath,[datetime]::UtcNow.ToString('o'))
        if ($task -and $task.State.ToString() -eq 'Running') { Stop-ScheduledTask -TaskName $TaskName }
        Import-Module (Join-Path $RuntimeDirectory 'WindowsPublicRuntime.psm1') -Force
        foreach ($component in 'public-relay','public-gateway','public-server','public-agent') { Stop-OwnedRuntimeComponent $RuntimeDirectory $component }
        # MySQL and its backups remain available. This is not a data cleanup command.
    }
    'Resume' {
        if (!$task) { throw 'Task is not installed' }
        if (Test-Path -LiteralPath $pausePath) { Remove-Item -LiteralPath $pausePath }
        Enable-ScheduledTask -TaskName $TaskName | Out-Null
        Start-ScheduledTask -TaskName $TaskName
    }
}
$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($task) {
    $info = Get-ScheduledTaskInfo -TaskName $TaskName
    [pscustomobject]@{task=$TaskName;state=$task.State.ToString();logon=$task.Principal.LogonType.ToString();runLevel=$task.Principal.RunLevel.ToString();lastResult=$info.LastTaskResult;paused=(Test-Path -LiteralPath $pausePath)} | ConvertTo-Json -Compress
}
