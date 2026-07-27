[CmdletBinding()]
param(
    [string]$Server = 'https://wao.wayuwa.cn',
    [string]$Name = $env:COMPUTERNAME,
    [Parameter(Mandatory = $true)][string]$Root,
    [ValidateRange(1, 8)][int]$MaxConcurrency = 2,
    [string]$Engines = '',
    [string]$TaskName = 'WAO AI Runner',
    [string]$PythonPath = ''
)

$ErrorActionPreference = 'Stop'
$runnerPath = Join-Path $PSScriptRoot 'wao-ai-runner.py'
if (-not (Test-Path -LiteralPath $runnerPath -PathType Leaf)) {
    throw "Runner script not found: $runnerPath"
}
if (-not $PythonPath) {
    $PythonPath = (Get-Command python -ErrorAction Stop).Source
}
$resolvedPython = (Resolve-Path -LiteralPath $PythonPath).Path
$resolvedRunner = (Resolve-Path -LiteralPath $runnerPath).Path
$resolvedRoot = [System.IO.Path]::GetFullPath($Root)
[System.IO.Directory]::CreateDirectory($resolvedRoot) | Out-Null

$arguments = @(
    ('"{0}"' -f $resolvedRunner),
    '--server', ('"{0}"' -f $Server),
    '--name', ('"{0}"' -f $Name),
    '--root', ('"{0}"' -f $resolvedRoot),
    '--max-concurrency', $MaxConcurrency
)
if ($Engines) { $arguments += @('--engines', ('"{0}"' -f $Engines)) }

$action = New-ScheduledTaskAction -Execute $resolvedPython -Argument ($arguments -join ' ') -WorkingDirectory (Split-Path $resolvedRunner)
$triggers = @(
    (New-ScheduledTaskTrigger -AtStartup),
    (New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME),
    (New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(5) -RepetitionInterval (New-TimeSpan -Minutes 5) -RepetitionDuration (New-TimeSpan -Days 3650))
)
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
    -RestartCount 6 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero)

# The runner needs the current user's Codex/Git/GitHub credentials, so it uses an interactive principal.
# AtLogOn and the five-minute watchdog compensate when AtStartup fires before that profile is available.
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
$task = New-ScheduledTask -Action $action -Trigger $triggers -Settings $settings -Principal $principal `
    -Description 'WAO AI Runner: start at boot/logon, restart after failures, and check every five minutes.'

Register-ScheduledTask -TaskName $TaskName -InputObject $task -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName
Write-Host "Scheduled task installed and started: $TaskName"
Write-Host "Principal: $($principal.UserId); workspace: $resolvedRoot; concurrency: $MaxConcurrency"
