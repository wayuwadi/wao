[CmdletBinding()]
param(
    [string]$Server = 'https://wao.wayuwa.cn',
    [string]$Name = $env:COMPUTERNAME,
    [Parameter(Mandatory = $true)][string]$Root,
    [ValidateRange(1, 8)][int]$MaxConcurrency = 2,
    [string]$Engines = '',
    [string]$TaskName = 'WAO AI Runner',
    [string]$PythonPath = '',
    [string]$GitCommand = '',
    [string]$CodexCommand = '',
    [string]$ClaudeCommand = '',
    [string]$QoderCommand = ''
)

$ErrorActionPreference = 'Stop'
$runnerPath = Join-Path $PSScriptRoot 'wao-ai-runner.py'
if (-not (Test-Path -LiteralPath $runnerPath -PathType Leaf)) {
    throw "Runner script not found: $runnerPath"
}
if (-not $PythonPath) {
    $PythonPath = (Get-Command python.exe -All -ErrorAction SilentlyContinue |
        Where-Object { $_.Source -notlike '*\WindowsApps\python.exe' -and (Test-Path -LiteralPath $_.Source) } |
        Select-Object -First 1 -ExpandProperty Source)
}
if (-not $PythonPath) {
    $runtimeRoot = Join-Path $env:USERPROFILE '.cache\codex-runtimes'
    if (Test-Path -LiteralPath $runtimeRoot) {
        $PythonPath = Get-ChildItem -LiteralPath $runtimeRoot -Filter python.exe -Recurse -File -ErrorAction SilentlyContinue |
            Select-Object -First 1 -ExpandProperty FullName
    }
}
if (-not $PythonPath) {
    throw 'No usable Python interpreter found. Install Python or pass -PythonPath once.'
}

if (-not $GitCommand) {
    $GitCommand = (Get-Command git.exe -All -ErrorAction SilentlyContinue |
        Where-Object { Test-Path -LiteralPath $_.Source } |
        Select-Object -First 1 -ExpandProperty Source)
    if (-not $GitCommand) {
        $programFilesGit = Join-Path $env:ProgramFiles 'Git\cmd\git.exe'
        if (Test-Path -LiteralPath $programFilesGit -PathType Leaf) {
            $GitCommand = $programFilesGit
        }
    }
}
if (-not $GitCommand) {
    throw 'No usable Git executable found. Install Git for Windows or pass -GitCommand.'
}

if (-not $CodexCommand) {
    # The ChatGPT/Codex desktop app also registers a WindowsApps codex.exe alias, but that
    # protected app binary cannot be launched by a background runner. Prefer the official
    # OpenAI.Codex winget package before consulting PATH.
    $wingetPackages = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages'
    if (Test-Path -LiteralPath $wingetPackages) {
        $CodexCommand = Get-ChildItem `
            -LiteralPath $wingetPackages `
            -Filter 'codex-x86_64-pc-windows-msvc.exe' `
            -Recurse `
            -File `
            -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -like '*OpenAI.Codex*' } |
            Select-Object -First 1 -ExpandProperty FullName
    }
}
if (-not $CodexCommand) {
    $CodexCommand = (Get-Command codex.exe -All -ErrorAction SilentlyContinue |
        Where-Object { $_.Source -notlike '*\WindowsApps\OpenAI.Codex_*' -and (Test-Path -LiteralPath $_.Source) } |
        Select-Object -First 1 -ExpandProperty Source)
}

$resolvedPython = (Resolve-Path -LiteralPath $PythonPath).Path
$pythonwPath = Join-Path (Split-Path $resolvedPython) 'pythonw.exe'
$taskPython = if (Test-Path -LiteralPath $pythonwPath -PathType Leaf) {
    (Resolve-Path -LiteralPath $pythonwPath).Path
} else {
    $resolvedPython
}
$resolvedRunner = (Resolve-Path -LiteralPath $runnerPath).Path
$resolvedRoot = [System.IO.Path]::GetFullPath($Root)
[System.IO.Directory]::CreateDirectory($resolvedRoot) | Out-Null

$arguments = @(
    ('"{0}"' -f $resolvedRunner),
    '--server', ('"{0}"' -f $Server),
    '--name', ('"{0}"' -f $Name),
    '--root', ('"{0}"' -f $resolvedRoot),
    '--max-concurrency', $MaxConcurrency,
    '--windows-task-name', ('"{0}"' -f $TaskName)
)
if ($Engines) { $arguments += @('--engines', ('"{0}"' -f $Engines)) }
foreach ($command in @(
    @{ Argument = '--git-command'; Path = $GitCommand },
    @{ Argument = '--codex-command'; Path = $CodexCommand },
    @{ Argument = '--claude-command'; Path = $ClaudeCommand },
    @{ Argument = '--qoder-command'; Path = $QoderCommand }
)) {
    if ($command.Path) {
        $resolvedCommand = (Resolve-Path -LiteralPath $command.Path -ErrorAction Stop).Path
        $arguments += @($command.Argument, ('"{0}"' -f $resolvedCommand))
    }
}

$action = New-ScheduledTaskAction -Execute $taskPython -Argument ($arguments -join ' ') -WorkingDirectory (Split-Path $resolvedRunner)
$triggers = @(
    (New-ScheduledTaskTrigger -AtStartup),
    (New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME),
    (New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 1) -RepetitionDuration (New-TimeSpan -Days 3650))
)
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
    -RestartCount 6 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero)

# The runner needs the current user's Codex/Git/GitHub credentials, so it uses an interactive principal.
# AtLogOn and the one-minute watchdog compensate when AtStartup fires before that profile is available.
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
$task = New-ScheduledTask -Action $action -Trigger $triggers -Settings $settings -Principal $principal `
    -Description 'WAO AI Runner: start at boot/logon, restart after failures, and check every minute.'

Register-ScheduledTask -TaskName $TaskName -InputObject $task -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName
Write-Host "Scheduled task installed and started: $TaskName"
Write-Host "Runtime: $taskPython"
Write-Host "Principal: $($principal.UserId); workspace: $resolvedRoot; concurrency: $MaxConcurrency"
Write-Host "Git: $GitCommand"
if ($CodexCommand) { Write-Host "Codex: $CodexCommand" }
