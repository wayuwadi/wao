package cn.wayuwa.wao;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** 防止部署与 Codex/OpenAI 专用路由回归成单点中断或无 VPN 出站。 */
class DeploymentRoutingConfigTest {

    @Test
    void browserSessionPersistsForThirtyDaysWithoutWeakeningCookieProtection() throws IOException {
        String application = readFromRepository("wao-server/src/main/resources/application.yml");
        String session = section(application, "    session:\n", "\n\nspring:");

        assertThat(session)
                .contains("timeout: 30d")
                .contains("name: WAO_SESSION")
                .contains("http-only: true")
                .contains("secure: ${SESSION_COOKIE_SECURE:false}")
                .contains("same-site: lax")
                .contains("max-age: 30d");
    }

    @Test
    void jenkinsChangePlanUsesSandboxSafeStaticEnvironmentAssignments() throws IOException {
        String pipeline = readFromRepository("Jenkinsfile");
        String declarativeEnvironment = section(pipeline,
                "    environment {", "\n    }\n\n    stages {");

        assertThat(pipeline)
                .contains("env.WAO_DEPLOY_REQUIRED = line.substring")
                .contains("env.WAO_BACKEND_PROJECTS = line.substring")
                .contains("env.WAO_TEST_WAO_WEB = line.substring")
                .contains("env.WAO_TEST_AGENT_WEB = line.substring")
                .contains("env.WAO_TEST_AI_RUNNER = line.substring")
                .contains("env.WAO_CHANGED_FILE_COUNT = line.substring")
                .doesNotContain("env[pair[0]]");
        assertThat(declarativeEnvironment)
                .doesNotContain("WAO_DEPLOY_REQUIRED")
                .doesNotContain("WAO_BACKEND_PROJECTS")
                .doesNotContain("WAO_TEST_WAO_WEB")
                .doesNotContain("WAO_TEST_AGENT_WEB")
                .doesNotContain("WAO_TEST_AI_RUNNER");
    }

    @Test
    void jenkinsBatchesMainPushesAndCoversEveryUndeployedCommit() throws IOException {
        String pipeline = readFromRepository("Jenkinsfile");
        String casc = readFromRepository("deploy/jenkins/jenkins.yaml");
        String status = readFromRepository("deploy/jenkins/github-status.sh");
        String resolver = readFromRepository("deploy/jenkins/resolve-batch-base.sh");

        assertThat(casc).contains("quietPeriod(300)");
        assertThat(pipeline)
                .contains("resolve-batch-base.sh \"$WAO_GIT_SHA\"")
                .contains("git rev-list --reverse --first-parent \"$WAO_CHANGE_BASE..$WAO_GIT_SHA\"")
                .contains("ci-change-plan.sh \"$WAO_CHANGE_BASE\" \"$WAO_GIT_SHA\"")
                .contains("WAO_BATCH_COMMIT_COUNT");
        assertThat(resolver)
                .contains("pluginWebUpdateNotice/web_version_by_plugin.json")
                .contains("git rev-list --first-parent \"$head\"")
                .contains("GIT_PREVIOUS_SUCCESSFUL_COMMIT")
                .contains("GIT_PREVIOUS_COMMIT");
        assertThat(status)
                .contains("WAO_STATUS_SHA_FILE")
                .contains("done <\"$WAO_STATUS_SHA_FILE\"")
                .contains("--retry 5 --retry-delay 2 --retry-all-errors")
                .contains("GitHub commit status delivery failed")
                .contains("Jenkins result is unchanged");
    }

    @Test
    void jenkinsInvokesBothNotificationPoliciesAfterBuildCompletion() throws IOException {
        String pipeline = readFromRepository("Jenkinsfile");
        // ADR-234 恢复受策略控制的成功短卡；卡片与静默行为由 test-notify-feishu.sh 验证。
        String completion = pipeline.substring(pipeline.lastIndexOf("\n    post {"));
        String success = completion.substring(completion.indexOf("success {"), completion.indexOf("failure {"));
        String failure = completion.substring(completion.indexOf("failure {"), completion.indexOf("cleanup {"));
        assertThat(success).contains("WAO_PIPELINE_RESULT=success deploy/jenkins/notify-feishu.sh || true");
        assertThat(failure).contains("WAO_PIPELINE_RESULT=failure deploy/jenkins/notify-feishu.sh || true");
    }

    @Test
    void jenkinsScmUsesPinnedGithubSshCredentialsInsteadOfProxyTls() throws IOException {
        String casc = readFromRepository("deploy/jenkins/jenkins.yaml");
        String installer = readFromRepository("deploy/jenkins/install-controller.sh");
        String updater = readFromRepository("deploy/jenkins/apply-controller-config.sh");

        assertThat(casc)
                .contains("id: wao-github-scm-ssh")
                .contains("url('git@github.com:wayuwacn/wao.git')")
                .contains("credentials('wao-github-scm-ssh')")
                .doesNotContain("url('https://github.com/wayuwacn/wao.git')");
        assertThat(installer)
                .contains("generate_key \"$secret_dir/github-scm-ed25519\"")
                .contains("github-known-hosts")
                .contains("\"$jenkins_home/.ssh/known_hosts\"")
                .doesNotContain("credential.https://github.com.helper");
        assertThat(updater)
                .contains("/etc/jenkins/secrets/github-known-hosts")
                .contains("/var/lib/jenkins/.ssh/known_hosts");
    }

    @Test
    void jenkinsFailureStatusIdentifiesTheFailedStage() throws IOException {
        String pipeline = readFromRepository("Jenkinsfile");

        assertThat(pipeline)
                .contains("env.WAO_FAILURE_STAGE = '确定发布范围'")
                .contains("env.WAO_FAILURE_STAGE = '打包构建'")
                .contains("env.WAO_FAILURE_STAGE = '发布部署'")
                .contains("env.WAO_FAILURE_STAGE = '同步运维配置'")
                .contains("Jenkins 失败：${env.WAO_FAILURE_STAGE.trim()}")
                .contains("github-status.sh failure \"$WAO_STATUS_DESCRIPTION\"");
    }

    @Test
    void immutableReleaseRetriesTransientRegistryPullFailures() throws IOException {
        String packager = readFromRepository("deploy/jenkins/ci-package.sh");

        assertThat(packager)
                .contains("pull_image()")
                .contains("max_attempts=3")
                .contains("while ! docker pull \"$image\"")
                .contains("retrying in 5 seconds")
                .contains("pull_image \"$image\"");
    }

    @Test
    void agentUsesBundledStaticDirectoryServiceAndDedicatedGatewayRoute() throws IOException {
        String nginx = readFromRepository("deploy/nginx/wao.wayuwa.cn.conf.template");
        String gateway = readFromRepository("wao-gateway/src/main/resources/application.yml");
        String localGateway = readFromRepository("wao-gateway/src/main/resources/application-local.yml");
        String masterCompose = readFromRepository("deploy/docker-compose-master.yml");

        assertThat(nginx)
                .contains("location /agent/")
                .contains("alias /var/www/wao-agent/dist/")
                .contains("location /api/");
        assertThat(gateway.indexOf("id: wao-agent-api")).isLessThan(gateway.indexOf("id: wao-server-api"));
        assertThat(gateway)
                .contains("Path=/api/agent/**")
                .contains("WAO_AGENT_SERVER_URI:http://wao-agent-server:8081");
        assertThat(localGateway).contains("WAO_AGENT_SERVER_URI:http://127.0.0.1:8081");
        assertThat(masterCompose)
                .contains("wao-agent-server:")
                .contains("target: agent-runtime")
                .contains("WAO_AGENT_SERVER_URI: http://wao-agent-server:8081")
                .contains("WAO_AI_EVOLUTION_SCHEDULER_ENABLED: \"false\"")
                .contains("WAO_DAILY_GUIDANCE_WARMUP_ENABLED: \"true\"");
        assertThat(masterCompose).contains("WAO_LIFE_REVIEW_SCHEDULER_ENABLED: \"true\"");
    }

    @Test
    void onlyCredentialBearingAiApiRoutesToLinuxMasterGateway() throws IOException {
        String nginx = readFromRepository("deploy/nginx/wao.wayuwa.cn.conf.template");
        String aiUpstream = section(nginx, "upstream wao_ai_gateway {", "\n}");
        String aiLocation = section(nginx,
                "location ~ ^/api/(settings/ai-accounts", " {");
        String aiRoutePattern = aiLocation.substring("location ~ ".length(), aiLocation.length() - " {".length());

        assertThat(aiUpstream)
                .contains("${MASTER_GATEWAY_IPV4}:9000")
                .doesNotContain("${SLAVE_GATEWAY_IPV4}", "backup");
        Pattern route = Pattern.compile(aiRoutePattern);
        assertThat(route.matcher("/api/settings/ai-accounts/login/start").find()).isTrue();
        assertThat(route.matcher("/api/ai/runner/accounts").find()).isTrue();
        assertThat(route.matcher("/api/ai/runner/claim").find()).isTrue();
        assertThat(route.matcher("/api/ai/runner/assistant-v2/claim").find()).isTrue();
        assertThat(route.matcher("/api/ai/runner/assistant-v2/ac1/complete").find()).isFalse();
        assertThat(route.matcher("/api/ai/runner/assistant/claim").find()).isFalse();
        assertThat(route.matcher("/api/tianren/readings/today/interpret/stream").find()).isTrue();
        assertThat(route.matcher("/api/ai/goals").find()).isFalse();
        assertThat(route.matcher("/api/ai/runs").find()).isFalse();
        assertThat(route.matcher("/api/ai/runner/heartbeat").find()).isFalse();
        assertThat(route.matcher("/api/assistant/chat/stream").find()).isFalse();
        assertThat(route.matcher("/api/tianren/readings/today").find()).isFalse();
        assertThat(nginx)
                .contains("proxy_pass https://wao_ai_gateway;")
                .contains("proxy_next_upstream off;")
                .contains("X-WAO-API-Route linux-vpn-master always;");
    }

    @Test
    void aiOrchestrationReadsAndWritesUseBothGateways() throws IOException {
        String nginx = readFromRepository("deploy/nginx/wao.wayuwa.cn.conf.template");
        String orchestrationLocation = section(nginx,
                "location ~ ^/api/ai/(?:workflows", "\n    }");

        assertThat(orchestrationLocation)
                .contains("|goals|")
                .contains("proxy_pass https://wao_gateway;")
                .contains("proxy_next_upstream error timeout http_500 http_502 http_503 http_504;")
                .contains("X-WAO-API-Route dual-gateway-ai always;")
                .doesNotContain("request_method", "@wao_ai_write");
    }

    @Test
    void evolutionSuggestionRefreshKeepsFiveMinuteReadOnlyJobAliveWithoutWriteReplay() throws IOException {
        String nginx = readFromRepository("deploy/nginx/wao.wayuwa.cn.conf.template");
        String refreshLocation = section(nginx,
                "location = /api/ai/evolution/suggestions/refresh {", "\n    }");

        assertThat(refreshLocation)
                .contains("proxy_pass https://wao_gateway;")
                .contains("proxy_read_timeout 330s;")
                .contains("proxy_next_upstream off;")
                .contains("X-WAO-API-Route dual-gateway-ai-long always;");
    }

    @Test
    void dailyGuidanceKeepsFiveMinuteReadOnlyJobAliveWithoutWriteReplay() throws IOException {
        String nginx = readFromRepository("deploy/nginx/wao.wayuwa.cn.conf.template");
        String guidanceLocation = section(nginx,
                "location = /api/daily-guidance {", "\n    }");

        assertThat(guidanceLocation)
                .contains("proxy_pass https://wao_gateway;")
                .contains("proxy_read_timeout 330s;")
                .contains("proxy_next_upstream off;")
                .contains("X-WAO-API-Route dual-gateway-ai-long always;");
    }

    @Test
    void lifeReviewGenerationKeepsFiveMinuteReadOnlyJobAliveWithoutWriteReplay() throws IOException {
        String nginx = readFromRepository("deploy/nginx/wao.wayuwa.cn.conf.template");
        String location = section(nginx,
                "location = /api/life-reviews/generate {", "\n    }");

        assertThat(location)
                .contains("proxy_pass https://wao_gateway;")
                .contains("proxy_read_timeout 330s;")
                .contains("proxy_next_upstream off;")
                .contains("X-WAO-API-Route dual-gateway-ai-long always;");
    }

    @Test
    void deploymentPublishesSingleLinux2GatewayAndCloudAssets() throws IOException {
        String pipeline = readFromRepository("Jenkinsfile");
        String rollout = readFromRepository("deploy/jenkins/ci-deploy.sh");
        String verification = readFromRepository("deploy/jenkins/ci-production-verify.sh");
        String localDeploy = readFromRepository("deploy/jenkins/deploy-master-local.sh");
        String probe = readFromRepository("deploy/scripts/probe-codex-egress.sh");

        assertThat(rollout)
                .contains("linux2_status=not_started")
                .contains("stage_cloud")
                .contains("set_status verification_status success")
                .contains("Linux2、云端静态制品与公共入口检查通过")
                .doesNotContain("linux1.wayuwa.cn", "SLAVE_HOST", "slave_status", "BOOTSTRAP_SLAVE");
        assertThat(pipeline)
                .contains("LINUX2_HOST = 'linux2.wayuwa.cn'")
                .contains("credentialsId: 'wao-cloud-ssh'")
                .doesNotContain("linux1.wayuwa.cn", "SLAVE_SSH", "ALLOW_SLAVE_OFFLINE", "BOOTSTRAP_SLAVE");
        assertThat(localDeploy)
                .contains("setfacl -m u:jenkins:r \"$project_root/deploy/.env.secrets\"")
                .contains("WAO_IMAGE_TAG=\"$image_tag\" SKIP_IMAGE_BUILD=true")
                .doesNotContain("linux1.wayuwa.cn", "JUMPSERVER_SLAVE");
        assertThat(verification)
                .contains("/api/tianren/readings/today/interpret/stream")
                .contains("probe-codex-egress.sh")
                .contains("dual-gateway-ai")
                .contains("/api/ai/runner/accounts")
                .contains("linux-vpn-master");
        assertThat(probe)
                .contains("elif sudo -n docker info")
                .contains("sudo -n docker \"$@\"")
                .contains("label=com.docker.compose.service=wao-server")
                .contains("WAO_AI_CODEX_PROXY_URL")
                .contains("--proxy \"${proxy_url}\"")
                .contains("label=com.docker.compose.service=codex-egress-proxy")
                .contains("direct_status")
                .contains("run_docker logs --tail 50")
                .contains("--connect-timeout 10 --max-time 30")
                .contains("https://chatgpt.com/backend-api/wham/usage")
                .contains("case \"${status}\" in")
                .contains("200|401");
    }
    @Test
    void jenkinsUiUsesWaoSessionIdentityWithRoleBasedJenkinsAuthorization() throws IOException {
        String nginx = readFromRepository("deploy/nginx/wao.wayuwa.cn.conf.template");
        String service = readFromRepository("deploy/jenkins/jenkins.service.override.conf");
        String casc = readFromRepository("deploy/jenkins/jenkins.yaml");
        String updater = readFromRepository("deploy/jenkins/apply-controller-config.sh");
        String backup = readFromRepository("deploy/jenkins/backup-controller.sh");
        String webhookTunnel = readFromRepository(
                "deploy/jenkins/wao-jenkins-webhook-tunnel.service");
        String plugins = readFromRepository("deploy/jenkins/plugins.txt");
        String pipeline = readFromRepository("Jenkinsfile");
        String verification = readFromRepository("deploy/jenkins/ci-production-verify.sh");
        String dockerfile = readFromRepository("deploy/Dockerfile");

        String authLocation = section(nginx,
                "location = /_wao_jenkins_auth {", "\n    }");
        String uiLocation = section(nginx, "location ^~ /jenkins/ {", "\n    }");
        String healthLocation = section(nginx,
                "location = /jenkins-health {", "\n    }");
        String webhookLocation = section(nginx,
                "location = /github-webhook/ {", "\n    }");

        assertThat(authLocation)
                .contains("internal;")
                .contains("proxy_pass https://wao_gateway/api/auth/jenkins;")
                .contains("proxy_pass_request_body off;")
                .contains("proxy_set_header Cookie $http_cookie;");
        assertThat(uiLocation)
                .contains("auth_request /_wao_jenkins_auth;")
                .contains("auth_request_set $wao_jenkins_user $upstream_http_x_wao_jenkins_user;")
                .contains("auth_request_set $wao_jenkins_groups $upstream_http_x_wao_jenkins_groups;")
                .contains("proxy_pass http://127.0.0.1:18081;")
                .contains("proxy_request_buffering off;")
                .contains("proxy_buffering off;")
                .contains("proxy_set_header X-Forwarded-Proto $scheme;")
                .contains("proxy_set_header Authorization \"\";")
                .contains("proxy_set_header X-Forwarded-User $wao_jenkins_user;")
                .contains("proxy_set_header X-Forwarded-Groups $wao_jenkins_groups;");
        assertThat(healthLocation)
                .contains("if ($request_method != HEAD) { return 405; }")
                .contains("proxy_pass http://127.0.0.1:18081/jenkins/login;")
                .contains("proxy_method HEAD;")
                .contains("proxy_pass_request_body off;");
        assertThat(webhookLocation)
                .contains("limit_except POST { deny all; }")
                .contains("/jenkins/github-webhook/")
                .contains("error_page 403 404 = @legacy_jenkins_webhook;");
        assertThat(service).contains("--httpListenAddress=127.0.0.1 --prefix=/jenkins");
        assertThat(dockerfile).contains("apk add --no-cache curl")
                .doesNotContain("util-linux-misc");
        assertThat(casc)
                .contains("reverseProxy:")
                .contains("headerGroups: X-Forwarded-Groups")
                .contains("globalMatrix:")
                .contains("name: wao-admins")
                .contains("- Overall/Administer")
                .contains("name: wao-members")
                .contains("- Job/Read")
                .contains("systemLocale: zh_CN")
                .contains("ignoreAcceptLanguage: true")
                .contains("url: https://wao.wayuwa.cn/jenkins/");
        assertThat(updater)
                .contains("chroot /host /usr/bin/nsenter --target 1 --mount --pid --")
                .contains("/usr/bin/systemctl daemon-reload")
                .contains("/safeRestart")
                .contains("--cookie \"$controller_cookie\" --cookie-jar \"$controller_cookie\"")
                .contains("wao-controller-runtime-check")
                .contains("running security realm still needs activation")
                .contains("X-Forwarded-User: wao-controller-maintenance")
                .contains("X-Forwarded-Groups: wao-admins")
                .contains("jenkins_auth='legacy'")
                .contains("jenkins-plugin-manager-2.15.0.jar")
                .contains("--plugin-file deploy/jenkins/plugins.txt")
                .contains("/host/etc/jenkins/plugins.txt")
                .contains("/host/usr/local/lib/wao-jenkins/backup-controller.sh")
                .contains("docker run --rm --privileged --pid=host")
                .contains("WAO_MASTER_PROJECT_ROOT|WAO_JENKINS_SCM_BRANCH|HTTP_PROXY|HTTPS_PROXY|NO_PROXY")
                .doesNotContain("/etc/jenkins/secrets:/host");
        assertThat(plugins)
                .contains("reverse-proxy-auth-plugin:257.v7dc0e405b_624")
                .contains("matrix-auth:3.3")
                .contains("localization-zh-cn:371.v23851f835d6b_")
                .contains("locale:641.v84f22d75fd8b_");
        assertThat(backup)
                .contains("jenkins_url='http://127.0.0.1:8081/jenkins'")
                .contains("--require-created")
                .contains("X-Forwarded-User: wao-controller-backup")
                .contains("X-Forwarded-Groups: wao-members")
                .doesNotContain("admin-password");
        assertThat(webhookTunnel)
                .contains("After=network-online.target jenkins.service")
                .contains("Restart=always")
                .doesNotContain("Requires=jenkins.service", "PartOf=jenkins.service");
        assertThat(pipeline).contains("deploy/jenkins/apply-controller-config.sh");
        assertThat(verification)
                .contains("https://wao.wayuwa.cn/jenkins)\" = 308")
                .contains("https://wao.wayuwa.cn/jenkins/)\" = 401");
    }

    @Test
    void readableExportUsesOnlyTheLinux2WriterAndRemainsScheduled() throws IOException {
        String wrapper = readFromRepository("deploy/backup/run-readable-content.sh");
        String exporter = readFromRepository("deploy/backup/export-readable-content.py");
        String service = readFromRepository("deploy/backup/wao-offsite-backup.service");
        String timer = readFromRepository("deploy/backup/wao-offsite-backup.timer");
        String updater = readFromRepository("deploy/jenkins/apply-controller-config.sh");
        String installer = readFromRepository("deploy/jenkins/install-controller.sh");

        assertThat(wrapper)
                .contains("Readable export requires the Linux2 WAO writer.")
                .contains("export WAO_EXPORT_MYSQL_HOST=127.0.0.1")
                .doesNotContain("linux1", "10.66.0.3");
        assertThat(exporter)
                .contains("role != \"linux2\"")
                .contains("if fields[0] != local_uuid")
                .doesNotContain("linux1", "10.66.0.3");
        assertThat(service)
                .contains("ConditionPathExists=/etc/wao-backup/webdav.env")
                .contains("ExecStart=/usr/local/lib/wao-backup/run-readable-content.sh")
                .contains("TimeoutStartSec=12h")
                .contains("Restart=on-failure")
                .contains("RestartSec=30m");
        assertThat(timer)
                .contains("OnCalendar=*-*-* 05:00:00 Asia/Shanghai")
                .contains("Persistent=true");
        assertThat(updater)
                .contains("run-readable-content.sh")
                .contains("export-readable-content.py")
                .contains("systemctl enable --now wao-offsite-backup.timer");
        assertThat(installer)
                .contains("python3")
                .contains("run-readable-content.sh")
                .contains("export-readable-content.py")
                .contains("systemctl enable --now wao-offsite-backup.timer");
    }

    @Test
    void mainSiteDeploymentRetiresSplitAppsAndPreservesThirdPartyRoutes() throws IOException {
        String nginx = readFromRepository("deploy/nginx/wao.wayuwa.cn.conf.template");
        String activation = readFromRepository("deploy/scripts/activate-nginx-config.sh");
        String rollout = readFromRepository("deploy/jenkins/ci-deploy.sh");
        String routingPreparation = section(rollout,
                "prepare_cloud_routing() {", "\n}\n\nstage_cloud_static() {");

        assertThat(nginx)
                .contains("location = /insight {")
                .contains("return 410;")
                .contains("include /etc/nginx/wao-external-apps.d/*.conf;");
        assertThat(activation)
                .contains("${WAO_NGINX_EXTERNAL_APPS_DIR:-/etc/nginx/wao-external-apps.d}")
                .contains("install -d -m 0755 \"${external_apps_dir}\"");
        assertThat(rollout)
                .contains("install -d -m 0755 /etc/nginx/wao-external-apps.d")
                .contains("rm -f /etc/nginx/wao-external-apps.d/wao-game.conf")
                .contains("/etc/nginx/wao-external-apps.d/wao-insight.conf")
                .doesNotContain("rm -rf /etc/nginx/wao-external-apps.d");
        assertThat(routingPreparation)
                .as("首次摘流前必须先移除会与新主配置重复的旧内部应用路由")
                .contains("rm -f /etc/nginx/wao-external-apps.d/wao-game.conf")
                .contains("/etc/nginx/wao-external-apps.d/wao-insight.conf");
    }

    @Test
    void nginxUsesOnlyTheLinux2GatewayUpstream() throws IOException {
        String activation = readFromRepository("deploy/scripts/activate-nginx-config.sh");
        String nginx = readFromRepository("deploy/nginx/wao.wayuwa.cn.conf.template");
        String renderer = readFromRepository("deploy/scripts/render-nginx-config.sh");

        assertThat(activation)
                .contains("flock -n 9")
                .contains("MASTER_PUBLIC_HOST")
                .doesNotContain("SLAVE_GATEWAY");
        assertThat(renderer)
                .contains("server ${MASTER_GATEWAY_IPV4}:9000")
                .doesNotContain("SLAVE_GATEWAY", "MASTER_GATEWAY_STATE");
        assertThat(nginx)
                .contains("${WAO_GATEWAY_SERVERS}")
                .doesNotContain("SLAVE_GATEWAY");
    }

    @Test
    void masterUsesHeadlessVpnAndBridgeProxyRestrictedToDockerSources() throws IOException {
        String compose = readFromRepository("deploy/docker-compose-master.yml");
        String deployScript = readFromRepository("deploy/scripts/deploy-linux.sh");
        String vpnService = section(compose, "  codex-vpn:", "\n  codex-egress-proxy:");
        String proxyService = section(compose, "  codex-egress-proxy:", "\n  wao-server:");
        String serverService = section(compose, "  wao-server:", "\n  wao-gateway:");
        String dockerfile = readFromRepository("deploy/Dockerfile");
        String tinyproxy = readFromRepository("deploy/tinyproxy/codex-egress.conf");

        assertThat(vpnService)
                .contains("/usr/local/bin/start-codex-vpn")
                .contains("${CODEX_MIHOMO_BIN:-/usr/bin/verge-mihomo}:/usr/local/bin/verge-mihomo:ro")
                .contains("${CODEX_MIHOMO_CONFIG_DIR:-/home/wadi/.local/share/io.github.clash-verge-rev.clash-verge-rev}:/config")
                .contains("networks: [wao]")
                .contains("127.0.0.1:7897:7897")
                .contains("WAO_CODEX_HEALTH_PROXY_URL: http://127.0.0.1:7897")
                .contains("/usr/local/bin/healthcheck-codex-egress")
                .doesNotContain("network_mode: host");
        assertThat(proxyService)
                .contains("target: codex-egress-proxy")
                .contains("networks: [wao]")
                .contains("codex-vpn:")
                .contains("condition: service_healthy")
                .contains("/usr/local/bin/healthcheck-codex-egress")
                .doesNotContain("ports:", "network_mode: host");
        assertThat(serverService)
                .contains("WAO_AI_CODEX_PROXY_URL: http://codex-egress-proxy:18888")
                .contains("codex-egress-proxy:")
                .contains("condition: service_healthy")
                .contains("host.docker.internal:host-gateway");
        assertThat(dockerfile)
                .contains("AS codex-egress-proxy")
                .contains("apk add --no-cache curl tinyproxy")
                .contains("healthcheck-codex-egress.sh")
                .contains("start-codex-vpn.sh")
                .contains("USER tinyproxy");
        assertThat(deployScript)
                .contains("requested_image_tag=\"${WAO_IMAGE_TAG:-}\"")
                .contains("WAO_IMAGE_TAG=\"${requested_image_tag}\"")
                .contains("requested_skip_image_build=\"${SKIP_IMAGE_BUILD:-}\"")
                .contains("SKIP_IMAGE_BUILD=\"${requested_skip_image_build}\"")
                .contains("compose build codex-egress-proxy wao-server wao-agent-server wao-gateway")
                .contains("compose up -d --no-deps codex-vpn codex-egress-proxy")
                .contains("diagnostic_services=\"codex-vpn codex-egress-proxy ${diagnostic_services}\"")
                .contains("ensure-codex-egress.sh")
                .doesNotContain("install-linux-codex.sh");
        assertThat(tinyproxy)
                .contains("Allow 127.0.0.1")
                .contains("Allow 172.16.0.0/12")
                .contains("ConnectPort 443")
                .contains("Upstream http codex-vpn:7897")
                .doesNotContain("Allow 0.0.0.0/0");
    }

    private static String readFromRepository(String relative) throws IOException {
        Path path = Path.of(relative);
        if (!Files.exists(path)) path = Path.of("..").resolve(relative);
        return Files.readString(path);
    }

    private static String section(String text, String start, String end) {
        int from = text.indexOf(start);
        assertThat(from).isGreaterThanOrEqualTo(0);
        int to = text.indexOf(end, from + start.length());
        assertThat(to).isGreaterThan(from);
        return text.substring(from, to + end.length());
    }
}
