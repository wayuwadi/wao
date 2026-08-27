param(
    [string]$Distribution = 'Ubuntu',
    [string]$TaskName = 'WAO WSL Keepalive'
)

$ErrorActionPreference = 'Stop'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-ScheduledTaskPrincipal `
    -UserId $identity.Name `
    -LogonType S4U `
    -RunLevel Highest
$action = New-ScheduledTaskAction `
    -Execute "$env:SystemRoot\System32\wsl.exe" `
    -Argument "-d $Distribution -u root -- /bin/sleep infinity"
$trigger = New-ScheduledTaskTrigger -AtStartup
$settings = New-ScheduledTaskSettingsSet `
    -StartWhenAvailable `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -RestartCount 999 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit ([TimeSpan]::Zero)

Register-ScheduledTask `
    -TaskName $TaskName `
    -Action $action `
    -Trigger $trigger `
    -Principal $principal `
    -Settings $settings `
    -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName
Start-Sleep -Seconds 3

$task = Get-ScheduledTask -TaskName $TaskName
if ($task.State -ne 'Running') {
    $info = Get-ScheduledTaskInfo -TaskName $TaskName
    throw "WSL keepalive task did not start (state=$($task.State), result=$($info.LastTaskResult))."
}

Write-Host "Scheduled task '$TaskName' is keeping WSL distribution '$Distribution' online."
