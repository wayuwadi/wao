param(
    [Parameter(Mandatory=$true)][string]$CodexPath,
    [int]$Port = 18082,
    [string]$StateDirectory = (Join-Path $env:USERPROFILE '.wao/local-notes'),
    [string]$DocumentIds = '',
    [ValidateRange(1,900)][int]$TimeoutSeconds = 300,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$repoPath = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$codexExecutable = (Resolve-Path -LiteralPath $CodexPath).Path
Push-Location $repoPath
try {
    foreach ($name in @('MYSQL_HOST', 'DB_NAME', 'DB_USER', 'DB_PASS')) {
        if (-not [Environment]::GetEnvironmentVariable($name)) { throw "缺少既有 local-cloud 环境变量：$name" }
    }
    if (-not $SkipBuild) {
        & mvn -q -pl wao-server -am compile -DskipTests
        if ($LASTEXITCODE -ne 0) { throw '本机服务编译失败' }
    }
    & mvn -q -pl wao-server dependency:build-classpath '-Dmdep.outputFile=target/local-note-ai-classpath.txt' '-DincludeScope=runtime'
    if ($LASTEXITCODE -ne 0) { throw '服务运行依赖解析失败' }
    # 运行产品 main；不加入测试替身，不启动 Runner，不更换公网服务或修改登录凭据。
    $classPath = 'wao-server/target/classes;' + (Get-Content wao-server/target/local-note-ai-classpath.txt -Raw).Trim()
    & java '-Dfile.encoding=UTF-8' -cp $classPath cn.wayuwa.wao.WaoServerApplication `
        '--spring.profiles.active=local-cloud' "--server.port=$Port" `
        '--wao.ai.local-notes.enabled=true' "--wao.ai.local-notes.executable=$codexExecutable" `
        "--wao.ai.local-notes.directory=$StateDirectory" "--wao.ai.local-notes.document-ids=$DocumentIds" `
        "--wao.ai.local-notes.timeout-seconds=$TimeoutSeconds"
    if ($LASTEXITCODE -ne 0) { throw "本机服务退出：$LASTEXITCODE" }
} finally { Pop-Location }
