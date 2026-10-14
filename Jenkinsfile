pipeline {
    agent any
    options {
        disableConcurrentBuilds(abortPrevious: false)
        timestamps()
        skipDefaultCheckout(true)
    }
    parameters { booleanParam(name: 'STAGE_ONLY', defaultValue: true, description: '先暂存并验证制品；维护者配置生产发布。') }
    stages {
        stage('拉取受信任源码') { steps { checkout scm } }
        stage('Windows 构建与交付') {
            steps {
                powershell '''
                    if ($env:BRANCH_NAME -and $env:BRANCH_NAME -ne 'main') { throw 'Production job accepts main only' }
                    if ($env:CHANGE_ID) { throw 'PR builds cannot access production' }
                    if (!$env:WAO_RUNTIME_ROOT) { throw 'Set the private WAO_RUNTIME_ROOT on the Jenkins node' }
                    $stage = $env:STAGE_ONLY -eq 'true'
                    & "$env:WORKSPACE/deploy/jenkins/windows-build-deploy.ps1" -SourceRoot $env:WORKSPACE -RuntimeRoot $env:WAO_RUNTIME_ROOT -StageOnly:$stage
                    if (!$?) { exit 1 }
                '''
            }
        }
    }
}
