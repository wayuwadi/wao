param(
    [Parameter(Mandatory=$true)][string]$CodexPath,
    [int]$Port = 18082,
    [string]$StateDirectory = (Join-Path $env:USERPROFILE '.wao/local-notes'),
    [string]$DocumentIds = '',
    [ValidateRange(1,900)][int]$TimeoutSeconds = 300,
    # 仅验收：连接内临时工作流表隔离日常调度器；文档、任务、登录仍使用既有真实数据库。
    [switch]$IsolateWorkflowState,
    [switch]$EnableDiscoveries,
    # 仅在已验证此主机真实图片参考输入后开启，不因CLI文字视觉能力推定图片能力。
    [switch]$EnableDigitalReferences,
    [string]$DiscoveryMemberIds = '',
    [string]$DiscoveryDocumentIds = '',
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
    $isolationArgs = @()
    if ($IsolateWorkflowState) {
        if ($Port -in @(18080, 19090) -or -not $PSBoundParameters.ContainsKey('StateDirectory')) {
            throw '隔离验收必须使用专用端口和显式 StateDirectory，不能使用公网服务端口'
        }
        # 每个新连接先创建同名临时表；建表失败即连接失败，不回退到共享工作流表。
        # 单连接让浏览器请求和本机 worker 看到同一份验收状态，断开后临时表自动消失。
        $isolationArgs = @('--server.address=127.0.0.1',
            '--spring.datasource.hikari.maximum-pool-size=1',
            '--spring.datasource.hikari.minimum-idle=1',
            '--spring.datasource.hikari.max-lifetime=0',
            '--spring.datasource.hikari.idle-timeout=0',
            '--spring.datasource.hikari.data-source-properties.allowMultiQueries=true',
            '--spring.datasource.hikari.connection-init-sql=CREATE TEMPORARY TABLE wao_acceptance_workflow_init LIKE note_ai_workflow; ALTER TABLE wao_acceptance_workflow_init RENAME TO note_ai_workflow')
        Write-Output '隔离工作流验收：仅本连接保存阶段状态；停止后阶段状态消失，文档与模型任务仍须按精确 ID 清理。'
    }
    $discoveryArgs = @('--wao.ai.discoveries.cleanup-enabled=false')
    if ($EnableDiscoveries) {
        if ($Port -in @(18080, 19090) -or -not $DiscoveryMemberIds -or -not $DiscoveryDocumentIds -or -not $PSBoundParameters.ContainsKey('StateDirectory')) {
            throw '发现验收须使用专用端口、目录、明确成员和文档白名单'
        }
        $discoveryArgs += @('--wao.ai.discoveries.enabled=true', "--wao.ai.discoveries.member-ids=$DiscoveryMemberIds", "--wao.ai.discoveries.document-ids=$DiscoveryDocumentIds")
    }
    & java '-Dfile.encoding=UTF-8' -cp $classPath cn.wayuwa.wao.WaoServerApplication `
        '--spring.profiles.active=local-cloud' "--server.port=$Port" `
        '--wao.ai.local-notes.enabled=true' "--wao.ai.local-notes.executable=$codexExecutable" `
        "--wao.ai.local-notes.directory=$StateDirectory" "--wao.ai.local-notes.document-ids=$DocumentIds" `
        "--wao.ai.local-notes.timeout-seconds=$TimeoutSeconds" "--wao.ai.local-notes.reference-images-enabled=$($EnableDigitalReferences.IsPresent.ToString().ToLowerInvariant())" @isolationArgs @discoveryArgs
    if ($LASTEXITCODE -ne 0) { throw "本机服务退出：$LASTEXITCODE" }
} finally { Pop-Location }
