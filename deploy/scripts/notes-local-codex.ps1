param([Parameter(Mandatory=$true)][string]$ConfigPath)
$ErrorActionPreference = 'Stop'
$repoPath = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$acceptanceConfigPath = (Resolve-Path -LiteralPath $ConfigPath).Path
Push-Location $repoPath
try {
    # 环境变量由调用者沿用既有 local-cloud 配置；不复制或输出凭据。
    foreach ($name in @('MYSQL_HOST', 'DB_NAME', 'DB_USER', 'DB_PASS')) {
        if (-not [Environment]::GetEnvironmentVariable($name)) { throw "缺少 local-cloud 环境变量：$name" }
    }
    & mvn -pl wao-server -am test-compile -DskipTests -q
    if ($LASTEXITCODE -ne 0) { throw '验收入口编译失败' }
    & mvn -pl wao-server dependency:build-classpath '-Dmdep.outputFile=target/local-codex-classpath.txt' -DincludeScope=test -q
    if ($LASTEXITCODE -ne 0) { throw '验收依赖解析失败' }
    # main resources 在前，避免 test 的 H2 application.yml 覆盖真实 local-cloud 配置。
    $classPath = 'wao-server/target/classes;wao-server/target/test-classes;' +
        (Get-Content wao-server/target/local-codex-classpath.txt -Raw).Trim()
    & java '-Dfile.encoding=UTF-8' -cp $classPath cn.wayuwa.wao.acceptance.LocalCodexNotesAcceptance $acceptanceConfigPath
    if ($LASTEXITCODE -ne 0) { throw "本机验收入口退出：$LASTEXITCODE" }
} finally { Pop-Location }
