pipeline {
    agent { label 'built-in' }

    options {
        disableConcurrentBuilds(abortPrevious: false)
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '20', daysToKeepStr: '14'))
        skipDefaultCheckout(true)
    }

    parameters {
        booleanParam(
            name: 'BOOTSTRAP_WINDOWS',
            defaultValue: false,
            description: '从 Linux 主库生成 GTID 快照并重建 Windows 从库。正常发布必须保持关闭。'
        )
        booleanParam(
            name: 'DEPLOY_PRODUCTION',
            defaultValue: true,
            description: '关闭时只执行测试、前端构建、Docker 镜像构建和发布包校验。'
        )
        booleanParam(
            name: 'ALLOW_WINDOWS_OFFLINE',
            defaultValue: false,
            description: '仅在 Windows 预检不可达时保持从节点摘流并继续发布 Linux 与云端；Windows 可达后的部署失败仍会阻断。'
        )
    }

    environment {
        WAO_RUN_TEMP = "${WORKSPACE}@tmp/wao-${BUILD_NUMBER}"
        MASTER_HOST = 'linux2.wayuwa.cn'
        SLAVE_HOST = 'windows1.wayuwa.cn'
        CLOUD_HOST = '8.136.152.131'
    }

    stages {
        stage('Checkout trusted main') {
            steps {
                checkout scm
                script {
                    env.WAO_GIT_SHA = sh(
                        script: 'git rev-parse HEAD',
                        returnStdout: true
                    ).trim()
                    env.WAO_CHANGE_BASE = sh(
                        script: 'deploy/jenkins/resolve-batch-base.sh "$WAO_GIT_SHA"',
                        returnStdout: true
                    ).trim()
                    env.WAO_STATUS_SHA_FILE = "${env.WAO_RUN_TEMP}/status-shas"
                    sh '''
                        mkdir -p "$WAO_RUN_TEMP"
                        git rev-list --reverse --first-parent "$WAO_CHANGE_BASE..$WAO_GIT_SHA" >"$WAO_STATUS_SHA_FILE"
                        if [ ! -s "$WAO_STATUS_SHA_FILE" ]; then printf '%s\n' "$WAO_GIT_SHA" >"$WAO_STATUS_SHA_FILE"; fi
                    '''
                    env.WAO_BATCH_COMMIT_COUNT = sh(
                        script: 'wc -l <"$WAO_STATUS_SHA_FILE" | tr -d " "',
                        returnStdout: true
                    ).trim()
                    env.WAO_COMMIT_MESSAGE = sh(
                        script: '''
                            if [ "$WAO_BATCH_COMMIT_COUNT" -le 1 ]; then
                              git log -1 --pretty=%B "$WAO_GIT_SHA"
                            else
                              printf '批量交付 %s 个 main 提交：\n' "$WAO_BATCH_COMMIT_COUNT"
                              git log --reverse --first-parent --pretty='- %h %s' "$WAO_CHANGE_BASE..$WAO_GIT_SHA"
                            fi
                        ''',
                        returnStdout: true
                    ).trim()
                    env.WAO_GIT_BRANCH = (env.GIT_BRANCH ?: 'main').replaceFirst('^origin/', '')
                }
                withCredentials([usernamePassword(
                    credentialsId: 'wao-github-pat',
                    usernameVariable: 'GITHUB_USER',
                    passwordVariable: 'GITHUB_PAT'
                )]) {
                    sh 'deploy/jenkins/github-status.sh pending "Jenkins 已开始构建"'
                }
            }
            post {
                failure {
                    script { env.WAO_FAILURE_STAGE = 'Checkout trusted main' }
                }
            }
        }

        stage('Validate release inputs') {
            steps {
                script {
                    def planOutput = sh(
                        script: 'deploy/jenkins/ci-change-plan.sh "$WAO_CHANGE_BASE" "$WAO_GIT_SHA"',
                        returnStdout: true
                    ).trim()
                    def planLines = planOutput.readLines()
                    if (planLines.size() != 6) {
                        error("变更范围字段数量错误：${planLines.size()}")
                    }
                    planLines.each { line ->
                        if (line ==~ /WAO_DEPLOY_REQUIRED=(true|false)/) {
                            env.WAO_DEPLOY_REQUIRED = line.substring('WAO_DEPLOY_REQUIRED='.length())
                        } else if (line ==~ /WAO_BACKEND_PROJECTS=[a-z,-]+/) {
                            env.WAO_BACKEND_PROJECTS = line.substring('WAO_BACKEND_PROJECTS='.length())
                        } else if (line ==~ /WAO_TEST_WAO_WEB=(true|false)/) {
                            env.WAO_TEST_WAO_WEB = line.substring('WAO_TEST_WAO_WEB='.length())
                        } else if (line ==~ /WAO_TEST_AGENT_WEB=(true|false)/) {
                            env.WAO_TEST_AGENT_WEB = line.substring('WAO_TEST_AGENT_WEB='.length())
                        } else if (line ==~ /WAO_TEST_AI_RUNNER=(true|false)/) {
                            env.WAO_TEST_AI_RUNNER = line.substring('WAO_TEST_AI_RUNNER='.length())
                        } else if (line ==~ /WAO_CHANGED_FILE_COUNT=[0-9]+/) {
                            env.WAO_CHANGED_FILE_COUNT = line.substring('WAO_CHANGED_FILE_COUNT='.length())
                        } else {
                            error("无法解析变更范围：${line}")
                        }
                    }
                    echo "变更范围：${env.WAO_CHANGED_FILE_COUNT} 个文件；后端=${env.WAO_BACKEND_PROJECTS}；" +
                        "WAO Web 测试=${env.WAO_TEST_WAO_WEB}；Agent Web 测试=${env.WAO_TEST_AGENT_WEB}；" +
                        "生产发布=${env.WAO_DEPLOY_REQUIRED}；批次提交=${env.WAO_BATCH_COMMIT_COUNT}"
                }
                sh 'deploy/jenkins/ci-validate.sh'
            }
            post {
                failure {
                    script { env.WAO_FAILURE_STAGE = 'Validate release inputs' }
                }
            }
        }

        stage('Parallel tests and frontend builds') {
            parallel {
                stage('Backend tests') {
                    when {
                        expression { env.WAO_BACKEND_PROJECTS != 'none' }
                    }
                    steps {
                        sh 'deploy/jenkins/ci-backend-test.sh'
                    }
                    post {
                        failure {
                            script { env.WAO_FAILURE_STAGE = 'Backend tests' }
                        }
                    }
                }
                stage('WAO Web tests and build') {
                    when {
                        expression {
                            env.WAO_TEST_WAO_WEB == 'true' || env.WAO_DEPLOY_REQUIRED == 'true'
                        }
                    }
                    steps {
                        sh 'deploy/jenkins/ci-frontend-build.sh wao-web "$WAO_TEST_WAO_WEB" "$WAO_DEPLOY_REQUIRED"'
                    }
                    post {
                        failure {
                            script { env.WAO_FAILURE_STAGE = 'WAO Web tests and build' }
                        }
                    }
                }
                stage('Agent Web tests and build') {
                    when {
                        expression {
                            env.WAO_TEST_AGENT_WEB == 'true' || env.WAO_DEPLOY_REQUIRED == 'true'
                        }
                    }
                    steps {
                        sh 'deploy/jenkins/ci-frontend-build.sh agent-web "$WAO_TEST_AGENT_WEB" "$WAO_DEPLOY_REQUIRED"'
                    }
                    post {
                        failure {
                            script { env.WAO_FAILURE_STAGE = 'Agent Web tests and build' }
                        }
                    }
                }
                stage('AI Runner tests') {
                    when {
                        expression { env.WAO_TEST_AI_RUNNER == 'true' }
                    }
                    steps {
                        sh 'python3 deploy/ai-runner/test_wao_ai_runner.py'
                    }
                    post {
                        failure {
                            script { env.WAO_FAILURE_STAGE = 'AI Runner tests' }
                        }
                    }
                }
            }
        }

        stage('Build immutable release') {
            when {
                expression { env.WAO_DEPLOY_REQUIRED == 'true' }
            }
            steps {
                sh 'deploy/jenkins/ci-package.sh'
            }
            post {
                failure {
                    script { env.WAO_FAILURE_STAGE = 'Build immutable release' }
                }
            }
        }

        stage('Deploy Windows, Linux and cloud') {
            when {
                expression { params.DEPLOY_PRODUCTION && env.WAO_DEPLOY_REQUIRED == 'true' }
            }
            steps {
                withCredentials([
                    sshUserPrivateKey(
                        credentialsId: 'wao-windows-ssh',
                        keyFileVariable: 'WINDOWS_SSH_KEY',
                        usernameVariable: 'SLAVE_USER'
                    ),
                    file(
                        credentialsId: 'wao-windows-known-hosts',
                        variable: 'WINDOWS_KNOWN_HOSTS'
                    ),
                    sshUserPrivateKey(
                        credentialsId: 'wao-cloud-ssh',
                        keyFileVariable: 'CLOUD_SSH_KEY',
                        usernameVariable: 'CLOUD_USER'
                    ),
                    file(
                        credentialsId: 'wao-cloud-known-hosts',
                        variable: 'CLOUD_KNOWN_HOSTS'
                    )
                ]) {
                    sh 'BOOTSTRAP_WINDOWS="$BOOTSTRAP_WINDOWS" ALLOW_WINDOWS_OFFLINE="$ALLOW_WINDOWS_OFFLINE" deploy/jenkins/ci-deploy.sh'
                    sh 'deploy/jenkins/ci-production-verify.sh'
                }
            }
            post {
                failure {
                    script { env.WAO_FAILURE_STAGE = 'Deploy Windows, Linux and cloud' }
                }
            }
        }

        stage('Apply Jenkins controller configuration') {
            when {
                expression { params.DEPLOY_PRODUCTION && env.WAO_DEPLOY_REQUIRED == 'true' }
            }
            steps {
                sh 'deploy/jenkins/apply-controller-config.sh'
            }
            post {
                failure {
                    script { env.WAO_FAILURE_STAGE = 'Apply Jenkins controller configuration' }
                }
            }
        }
    }

    post {
        success {
            script {
                if (env.WAO_GIT_SHA) {
                    def successDescription = env.WAO_DEPLOY_REQUIRED != 'true'
                        ? 'Jenkins 验证成功（无需生产发布）'
                        : (params.DEPLOY_PRODUCTION
                            ? 'Jenkins 构建与发布成功'
                            : 'Jenkins 影子构建成功（未部署）')
                    withCredentials([usernamePassword(
                        credentialsId: 'wao-github-pat',
                        usernameVariable: 'GITHUB_USER',
                        passwordVariable: 'GITHUB_PAT'
                    )]) {
                        withEnv(["WAO_STATUS_DESCRIPTION=${successDescription}"]) {
                            sh 'deploy/jenkins/github-status.sh success "$WAO_STATUS_DESCRIPTION"'
                        }
                    }
                }
            }
            sh 'WAO_PIPELINE_RESULT=success deploy/jenkins/notify-feishu.sh || true'
            sh 'if [ "$DEPLOY_PRODUCTION" = true ] && [ "$WAO_DEPLOY_REQUIRED" = true ]; then deploy/jenkins/cleanup-images.sh; fi'
        }
        failure {
            script {
                if (env.WAO_GIT_SHA) {
                    def failureDescription = env.WAO_FAILURE_STAGE?.trim()
                        ? "Jenkins 失败：${env.WAO_FAILURE_STAGE.trim()}"
                        : 'Jenkins 构建或发布失败'
                    withCredentials([usernamePassword(
                        credentialsId: 'wao-github-pat',
                        usernameVariable: 'GITHUB_USER',
                        passwordVariable: 'GITHUB_PAT'
                    )]) {
                        withEnv(["WAO_STATUS_DESCRIPTION=${failureDescription}"]) {
                            sh 'deploy/jenkins/github-status.sh failure "$WAO_STATUS_DESCRIPTION"'
                        }
                    }
                }
            }
            sh 'WAO_PIPELINE_RESULT=failure deploy/jenkins/notify-feishu.sh || true'
        }
        // Declarative Pipeline 的 always 早于 success / failure；临时批次清单必须保留到状态回写和飞书通知结束。
        cleanup {
            sh 'deploy/jenkins/cleanup-workspace.sh || true'
        }
    }
}
