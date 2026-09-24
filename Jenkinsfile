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
            name: 'BOOTSTRAP_SLAVE',
            defaultValue: false,
            description: '从 Linux2 主库生成 GTID 快照并重建 Linux1 从库。正常发布必须保持关闭。'
        )
        booleanParam(
            name: 'DEPLOY_PRODUCTION',
            defaultValue: true,
            description: '关闭时只打包，不部署生产。'
        )
        booleanParam(
            name: 'ALLOW_SLAVE_OFFLINE',
            defaultValue: false,
            description: '仅在 Linux1 预检不可达时保持从节点摘流并继续发布主节点与云端；从节点可达后的部署失败仍会阻断。'
        )
    }

    environment {
        WAO_RUN_TEMP = "${WORKSPACE}@tmp/wao-${BUILD_NUMBER}"
        MASTER_HOST = 'linux2.wayuwa.cn'
        SLAVE_HOST = 'linux1.wayuwa.cn'
        // Linux1 主动回连 Linux2；Jenkins 只访问控制器本机的受限 loopback 转发。
        SLAVE_SSH_HOST = '127.0.0.1'
        SLAVE_SSH_PORT = '2201'
        CLOUD_HOST = '8.136.152.131'
    }

    stages {
        stage('拉取代码') {
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
                            git log --reverse --first-parent --pretty='- %s' "$WAO_CHANGE_BASE..$WAO_GIT_SHA"
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
                    script { env.WAO_FAILURE_STAGE = '拉取代码' }
                }
            }
        }

        stage('确定发布范围') {
            steps {
                script {
                    def planOutput = sh(
                        script: 'deploy/jenkins/ci-change-plan.sh "$WAO_CHANGE_BASE" "$WAO_GIT_SHA"',
                        returnStdout: true
                    ).trim()
                    def planLines = planOutput.readLines()
                    if (planLines.size() != 7) {
                        error("变更范围字段数量错误：${planLines.size()}")
                    }
                    planLines.each { line ->
                        if (line ==~ /WAO_DEPLOY_REQUIRED=(true|false)/) {
                            env.WAO_DEPLOY_REQUIRED = line.substring('WAO_DEPLOY_REQUIRED='.length())
                        } else if (line ==~ /WAO_WEB_ONLY=(true|false)/) {
                            env.WAO_WEB_ONLY = line.substring('WAO_WEB_ONLY='.length())
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
                    if (params.BOOTSTRAP_SLAVE || params.ALLOW_SLAVE_OFFLINE) { env.WAO_WEB_ONLY = 'false' }
                    env.WAO_OPS_CONFIG_CHANGED = sh(
                        script: '''
                            if git diff --name-only "$WAO_CHANGE_BASE" "$WAO_GIT_SHA" |
                                grep -Eq '^deploy/(jenkins|backup)/'; then
                                printf true
                            else
                                printf false
                            fi
                        ''',
                        returnStdout: true
                    ).trim()
                    echo "发布范围：${env.WAO_CHANGED_FILE_COUNT} 个文件；" +
                        "仅网页=${env.WAO_WEB_ONLY}；需要发布=${env.WAO_DEPLOY_REQUIRED}；" +
                        "需同步运维配置=${env.WAO_OPS_CONFIG_CHANGED}；批次提交=${env.WAO_BATCH_COMMIT_COUNT}"
                }
                sh 'deploy/jenkins/ci-preflight.sh'
            }
            post {
                failure {
                    script { env.WAO_FAILURE_STAGE = '确定发布范围' }
                }
            }
        }

        stage('打包构建') {
            when {
                expression { env.WAO_DEPLOY_REQUIRED == 'true' }
            }
            steps {
                sh 'deploy/jenkins/ci-build-frontends.sh'
                sh 'deploy/jenkins/ci-package.sh'
            }
            post {
                failure {
                    script { env.WAO_FAILURE_STAGE = '打包构建' }
                }
            }
        }

        stage('部署并验收') {
            when {
                expression { params.DEPLOY_PRODUCTION && env.WAO_DEPLOY_REQUIRED == 'true' }
            }
            steps {
                // 新 JCasC 凭据只能在当前构建结束后的 safeRestart 生效。Linux1 的控制器本地文件
                // 已由安装脚本以 root:jenkins 0640 准备，直接绑定路径可让拓扑迁移的首个构建完成。
                withEnv([
                    'SLAVE_SSH_KEY=/etc/jenkins/secrets/slave-ed25519',
                    'SLAVE_KNOWN_HOSTS=/etc/jenkins/secrets/slave-known-hosts',
                    'SLAVE_USER=wage'
                ]) {
                    withCredentials([
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
                        sh 'BOOTSTRAP_SLAVE="$BOOTSTRAP_SLAVE" ALLOW_SLAVE_OFFLINE="$ALLOW_SLAVE_OFFLINE" deploy/jenkins/ci-deploy.sh'
                        sh 'deploy/jenkins/ci-production-verify.sh'
                    }
                }
            }
            post {
                failure {
                    script { env.WAO_FAILURE_STAGE = '部署并验收' }
                }
            }
        }

        stage('同步运维配置') {
            when {
                expression { params.DEPLOY_PRODUCTION && env.WAO_DEPLOY_REQUIRED == 'true' && env.WAO_OPS_CONFIG_CHANGED == 'true' }
            }
            steps {
                sh 'deploy/jenkins/apply-controller-config.sh'
            }
            post {
                failure {
                    script { env.WAO_FAILURE_STAGE = '同步运维配置' }
                }
            }
        }
    }

    post {
        success {
            script {
                if (env.WAO_GIT_SHA) {
                    def successDescription = env.WAO_DEPLOY_REQUIRED != 'true'
                        ? 'Jenkins 已检查变更（无需生产发布）'
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
            sh 'if [ "$DEPLOY_PRODUCTION" = true ] && [ "$WAO_DEPLOY_REQUIRED" = true ] && [ "$WAO_WEB_ONLY" != true ]; then deploy/jenkins/cleanup-images.sh; fi'
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
