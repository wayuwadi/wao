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
                    env.WAO_COMMIT_MESSAGE = sh(
                        script: 'git log -1 --pretty=%B',
                        returnStdout: true
                    ).trim()
                }
                withCredentials([usernamePassword(
                    credentialsId: 'wao-github-pat',
                    usernameVariable: 'GITHUB_USER',
                    passwordVariable: 'GITHUB_PAT'
                )]) {
                    sh 'deploy/jenkins/github-status.sh pending "Jenkins 已开始构建"'
                }
            }
        }

        stage('Validate release inputs') {
            steps {
                sh 'deploy/jenkins/ci-validate.sh'
            }
        }

        stage('Backend tests') {
            steps {
                sh 'deploy/jenkins/ci-backend-test.sh'
            }
        }

        stage('Frontend tests and build') {
            steps {
                sh 'deploy/jenkins/ci-frontend-build.sh'
            }
        }

        stage('Build immutable release') {
            steps {
                sh 'deploy/jenkins/ci-package.sh'
            }
        }

        stage('Deploy Windows, Linux and cloud') {
            when {
                expression { params.DEPLOY_PRODUCTION }
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
                    sh 'BOOTSTRAP_WINDOWS="$BOOTSTRAP_WINDOWS" deploy/jenkins/ci-deploy.sh'
                    sh 'deploy/jenkins/ci-production-verify.sh'
                }
            }
        }
    }

    post {
        success {
            script {
                if (env.WAO_GIT_SHA) {
                    withCredentials([usernamePassword(
                        credentialsId: 'wao-github-pat',
                        usernameVariable: 'GITHUB_USER',
                        passwordVariable: 'GITHUB_PAT'
                    )]) {
                        sh 'deploy/jenkins/github-status.sh success "Jenkins 构建与发布成功"'
                    }
                }
            }
            sh 'WAO_PIPELINE_RESULT=success deploy/jenkins/notify-feishu.sh || true'
            sh 'if [ "$DEPLOY_PRODUCTION" = true ]; then deploy/jenkins/cleanup-images.sh; fi'
        }
        failure {
            script {
                if (env.WAO_GIT_SHA) {
                    withCredentials([usernamePassword(
                        credentialsId: 'wao-github-pat',
                        usernameVariable: 'GITHUB_USER',
                        passwordVariable: 'GITHUB_PAT'
                    )]) {
                        sh 'deploy/jenkins/github-status.sh failure "Jenkins 构建或发布失败"'
                    }
                }
            }
            sh 'WAO_PIPELINE_RESULT=failure deploy/jenkins/notify-feishu.sh || true'
        }
        always {
            sh 'deploy/jenkins/cleanup-workspace.sh || true'
        }
    }
}
