param(
    [Parameter(Mandatory=$true)][string]$PythonPath,
    [Parameter(Mandatory=$true)][string]$NodePath,
    [Parameter(Mandatory=$true)][string]$NpmCliPath,
    [string]$RuntimeDirectory = (Join-Path $env:USERPROFILE '.wao/codex-runtime')
)
$ErrorActionPreference = 'Stop'
$pythonExecutable = (Resolve-Path -LiteralPath $PythonPath).Path
$nodeExecutable = (Resolve-Path -LiteralPath $NodePath).Path
$npmScript = (Resolve-Path -LiteralPath $NpmCliPath).Path
$runtimePath = [IO.Path]::GetFullPath($RuntimeDirectory)
New-Item -ItemType Directory -Force $runtimePath | Out-Null
# 使用仓库中的已审阅脚本副本，计划任务不依赖临时 worktree 是否保留。
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'update-local-codex.py') -Destination (Join-Path $runtimePath 'update-local-codex.py')
$config = @{ python=$pythonExecutable; nodeDirectory=(Split-Path $nodeExecutable); npm=$npmScript; root=$runtimePath }
$config | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $runtimePath 'task-config.json')
$launcher = @'
$ErrorActionPreference = 'Stop'
$config = Get-Content (Join-Path $PSScriptRoot 'task-config.json') -Raw | ConvertFrom-Json
$env:PATH = $config.nodeDirectory + ';' + $env:PATH
$env:PYTHONIOENCODING = 'utf-8'
& $config.python (Join-Path $PSScriptRoot 'update-local-codex.py') --root $config.root --npm-cli $config.npm
exit $LASTEXITCODE
'@
$launcher | Set-Content -Encoding utf8 (Join-Path $runtimePath 'update.ps1')
$pwsh = (Get-Command pwsh.exe).Source
$action = New-ScheduledTaskAction -Execute $pwsh -Argument ('-NoProfile -WindowStyle Hidden -File "' + (Join-Path $runtimePath 'update.ps1') + '"')
$trigger = New-ScheduledTaskTrigger -Daily -At '06:15'
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 20) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
$principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName 'WAO-Local-Codex-Update' -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Description '检查官方 Codex 稳定版；合成文字与图片验收通过后切换 WAO，本机登录保持不变。' -Force | Select-Object TaskName,State
