param(
    [Parameter(Mandatory=$true)][switch]$ConfirmLocalDatabase,
    [Parameter(Mandatory=$true)][string]$EnvFile,
    [string]$MemberId,
    [ValidateRange(0,600)][int]$HoldSeconds = 0,
    [switch]$BrowserLogin,
    [switch]$SkipCompile,
    [string]$EvidenceDirectory
)
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLocalDatabase) { throw '必须显式确认只验收本机数据库' }
$repoPath = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$envPath = (Resolve-Path -LiteralPath $EnvFile).Path
if (-not $EvidenceDirectory) { $EvidenceDirectory = Join-Path $repoPath ('wao-server/target/acceptance/workspace-cli-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$evidencePath = [IO.Path]::GetFullPath($EvidenceDirectory)
$taskEnvNames = @('MYSQL_HOST','MYSQL_PORT','DB_NAME','DB_USER','DB_PASS','REDIS_HOST','REDIS_PORT','REDIS_PASS','WAO_TEST_WORKSPACE_CLI_MYSQL','WAO_ACCEPTANCE_MEMBER_ID','WAO_ACCEPTANCE_HOLD_SECONDS','WAO_ACCEPTANCE_BROWSER_LOGIN')
$taskSavedEnv = @{}
foreach ($name in $taskEnvNames) { $taskSavedEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
Push-Location $repoPath
try {
    # 只读取数据库所需变量，不复制文件、不输出任何值；不把凭据拼到命令参数。
    foreach ($line in [IO.File]::ReadAllLines($envPath)) {
        if ($line -match '^\s*(MYSQL_HOST|MYSQL_PORT|DB_NAME|DB_USER|DB_PASS|REDIS_HOST|REDIS_PORT|REDIS_PASS)=(.*)$') {
            $name = $Matches[1]; $value = $Matches[2].Trim()
            if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) { $value = $value.Substring(1, $value.Length - 2) }
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
    foreach ($name in @('MYSQL_HOST','DB_NAME','DB_USER','DB_PASS')) {
        if (-not [Environment]::GetEnvironmentVariable($name)) { throw "缺少本机数据库环境变量：$name" }
    }
    if ($env:MYSQL_HOST -notin @('127.0.0.1','localhost')) { throw '拒绝远程数据库；只接受127.0.0.1/localhost' }
    $mysqlPort = if ($env:MYSQL_PORT) { [int]$env:MYSQL_PORT } else { 3306 }
    # 不允许把 SSH 转发误称本机数据库。Windows 必须是本机 MySQL / Docker / WSL 监听。
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $mysqlPort -ErrorAction SilentlyContinue)
    if (-not $listeners.Count) { throw '本机没有 MySQL 端口监听，未启动验收' }
    $processNames = @($listeners | ForEach-Object { (Get-Process -Id $_.OwningProcess -ErrorAction Stop).ProcessName })
    if ($processNames | Where-Object { $_ -notmatch '^(mysqld|com\.docker\.backend|wslhost|wslrelay|System)$' }) { throw '本机端口不是已确认的 MySQL/Docker/WSL 监听，拒绝隧道或代理目标' }
    if (Get-NetTCPConnection -State Listen -LocalPort 18084 -ErrorAction SilentlyContinue) { throw '18084已有服务，避免干扰现有进程' }
    $env:WAO_TEST_WORKSPACE_CLI_MYSQL = 'true'
    $env:WAO_ACCEPTANCE_MEMBER_ID = $MemberId
    $env:WAO_ACCEPTANCE_HOLD_SECONDS = [string]$HoldSeconds
    $env:WAO_ACCEPTANCE_BROWSER_LOGIN = if ($BrowserLogin) { 'true' } else { 'false' }
    if (-not $SkipCompile) {
        & mvn -pl wao-server -am test-compile -DskipTests -q
        if ($LASTEXITCODE -ne 0) { throw '真实验收入口编译失败' }
    }
    $sourceMigrationDirectory = Join-Path $repoPath 'wao-server/src/main/resources/db/migration'
    $compiledMigrationDirectory = Join-Path $repoPath 'wao-server/target/classes/db/migration'
    foreach ($compiledMigration in Get-ChildItem -LiteralPath $compiledMigrationDirectory -Filter '*.sql') {
        if (-not (Test-Path -LiteralPath (Join-Path $sourceMigrationDirectory $compiledMigration.Name))) { throw '编译目录有源码中已不存在的迁移；先协调 Maven clean，再运行验收，脚本不自动删除' }
    }
    & mvn -pl wao-server dependency:build-classpath '-Dmdep.outputFile=target/workspace-cli-acceptance-classpath.txt' -DincludeScope=test -q
    if ($LASTEXITCODE -ne 0) { throw '验收依赖解析失败' }
    # main资源在前，禁止test H2 application.yml替换真实MySQL配置。
    $classPath = 'wao-server/target/classes;wao-server/target/test-classes;' + (Get-Content wao-server/target/workspace-cli-acceptance-classpath.txt -Raw).Trim()
    $nodeScript = Join-Path $repoPath 'wao-cli/acceptance/local-mysql.mjs'
    & java '-Dfile.encoding=UTF-8' -cp $classPath cn.wayuwa.wao.acceptance.LocalWorkspaceCliMysqlAcceptance $nodeScript $evidencePath
    if ($LASTEXITCODE -ne 0) { throw "真实MySQL CLI验收失败，退出码 $LASTEXITCODE；fixture保留，详情查看验收证据" }
} finally {
    foreach ($name in $taskEnvNames) { [Environment]::SetEnvironmentVariable($name, $taskSavedEnv[$name], 'Process') }
    Pop-Location
}
