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
        String notification = readFromRepository("deploy/jenkins/notify-feishu.sh");

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
                .contains("done <\"$WAO_STATUS_SHA_FILE\"");
        assertThat(notification).contains("$batch+\" 个 main 提交\"");
    }

    @Test
    void jenkinsFailureStatusIdentifiesTheFailedStage() throws IOException {
        String pipeline = readFromRepository("Jenkinsfile");

        assertThat(pipeline)
                .contains("env.WAO_FAILURE_STAGE = 'Validate release inputs'")
                .contains("env.WAO_FAILURE_STAGE = 'Backend tests'")
                .contains("env.WAO_FAILURE_STAGE = 'Build immutable release'")
                .contains("env.WAO_FAILURE_STAGE = 'Deploy Windows, Linux and cloud'")
                .contains("env.WAO_FAILURE_STAGE = 'Apply Jenkins controller configuration'")
                .contains("Jenkins 失败：${env.WAO_FAILURE_STAGE.trim()}")
                .contains("github-status.sh failure \"$WAO_STATUS_DESCRIPTION\"");
    }

    @Test
    void agentUsesBundledStaticDirectoryServiceAndDedicatedGatewayRoute() throws IOException {
        String nginx = readFromRepository("deploy/nginx/wao.wayuwa.cn.conf.template");
        String gateway = readFromRepository("wao-gateway/src/main/resources/application.yml");
        String localGateway = readFromRepository("wao-gateway/src/main/resources/application-local.yml");
        String masterCompose = readFromRepository("deploy/docker-compose-master.yml");
        String slaveCompose = readFromRepository("deploy/docker-compose-slave.yml");

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
                .contains("WAO_DAILY_GUIDANCE_WARMUP_ENABLED: \"false\"");
        assertThat(masterCompose).contains("WAO_LIFE_REVIEW_SCHEDULER_ENABLED: \"true\"");
        assertThat(slaveCompose)
                .contains("wao-agent-server:")
                .contains("target: agent-runtime")
                .contains("WAO_AGENT_SERVER_URI: http://wao-agent-server:8081")
                .doesNotContain("WAO_DAILY_GUIDANCE_WARMUP_ENABLED");
        assertThat(slaveCompose).doesNotContain("WAO_LIFE_REVIEW_SCHEDULER_ENABLED");
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
    void deploymentPublishesNginxConfigAndProbesOpenAiInsideMasterContainer() throws IOException {
        String pipeline = readFromRepository("Jenkinsfile");
        String rollout = readFromRepository("deploy/jenkins/ci-deploy.sh");
        String verification = readFromRepository("deploy/jenkins/ci-production-verify.sh");
        String localDeploy = readFromRepository("deploy/jenkins/deploy-master-local.sh");
        String probe = readFromRepository("deploy/scripts/probe-codex-egress.sh");

        assertThat(rollout)
                .contains("WAO_NGINX_RENDER_OUTPUT=wao-nginx.conf sh deploy/scripts/render-nginx-config.sh")
                .contains("scp -o ConnectTimeout=15 wao-nginx.conf")
                .contains("nginx -t");
        assertThat(verification)
                .contains("/api/tianren/readings/today/interpret/stream")
                .contains("probe-codex-egress.sh")
                .contains("dual-gateway-ai")
                .contains("/api/ai/runner/accounts")
                .contains("linux-vpn-master");
        assertThat(rollout)
                .contains("set-nginx-upstream-state.sh")
                .contains("set_upstream_state slave down")
                .contains("set_upstream_state master down");
        assertThat(rollout.indexOf("prepare_cloud_routing >routing.log"))
                .isLessThan(rollout.indexOf("retry_node_deploy Windows deploy_slave slave.log"));
        assertThat(rollout.indexOf("retry_node_deploy Windows deploy_slave slave.log"))
                .isLessThan(rollout.indexOf("retry_node_deploy Linux deploy_master master.log"));
        assertThat(rollout.indexOf("retry_node_deploy Linux deploy_master master.log"))
                .isLessThan(rollout.indexOf("deploy_cloud >cloud.log"));
        assertThat(rollout)
                .contains("wao-release.zip wao-secrets.env wao-shared.env wao-gateway.p12 wao-images.tar.gz")
                .contains("sudo -n -u wadi /usr/local/lib/wao-jenkins/deploy-master-local.sh")
                .doesNotContain("Prepare Linux Codex package")
                .doesNotContain("codex-package-x86_64-unknown-linux-musl.tar.gz")
                .doesNotContain("upload_master_resumable")
                .doesNotContain("WAO_CODEX_PACKAGE")
                .doesNotContain("WAO_CODEX_CHECKSUMS");
        assertThat(localDeploy)
                .contains("setfacl -m u:jenkins:r \"$project_root/deploy/.env.secrets\"")
                .contains("WAO_IMAGE_TAG=\"$image_tag\" SKIP_IMAGE_BUILD=true");
        assertThat(rollout)
                .doesNotContain("deploy_master >master.log 2>&1 &")
                .contains("slave_deployed=false")
                .contains("slave_deployed=true")
                .contains("ALLOW_WINDOWS_OFFLINE")
                .contains("windows_available=false")
                .contains("linux_only_schema_rollout=true")
                .contains("set_status windows_status skipped")
                .contains("Windows 从节点预检不可达；保持摘流并按显式授权继续发布 Linux 与云端")
                .contains("V77 完成标记和飞书同步保持暂停")
                .contains("Windows 从节点严格预检失败")
                .contains("Windows 从节点三次部署失败")
                .contains("Linux 主节点三次部署失败")
                .contains("validate-deployment-node.sh")
                .contains("start_availability_monitor");
        assertThat(pipeline)
                .contains("credentialsId: 'wao-windows-ssh'")
                .contains("credentialsId: 'wao-cloud-ssh'")
                .contains("name: 'ALLOW_WINDOWS_OFFLINE'")
                .contains("disableConcurrentBuilds(abortPrevious: false)");
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
                .contains("https://auth.openai.com/api/accounts/deviceauth/usercode")
                .contains("if [ \"${status}\" != '200' ]");
    }

    @Test
    void jenkinsUiUsesWaoSessionIdentityWithRoleBasedJenkinsAuthorization() throws IOException {
        String nginx = readFromRepository("deploy/nginx/wao.wayuwa.cn.conf.template");
        String service = readFromRepository("deploy/jenkins/jenkins.service.override.conf");
        String casc = readFromRepository("deploy/jenkins/jenkins.yaml");
        String updater = readFromRepository("deploy/jenkins/apply-controller-config.sh");
        String backup = readFromRepository("deploy/jenkins/backup-controller.sh");
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
                .contains("X-Forwarded-User: wao-controller-backup")
                .contains("X-Forwarded-Groups: wao-members")
                .doesNotContain("admin-password");
        assertThat(pipeline).contains("deploy/jenkins/apply-controller-config.sh");
        assertThat(verification)
                .contains("https://wao.wayuwa.cn/jenkins)\" = 308")
                .contains("https://wao.wayuwa.cn/jenkins/)\" = 401");
    }

    @Test
    void mainSiteDeploymentRetiresSplitAppsAndPreservesThirdPartyRoutes() throws IOException {
        String nginx = readFromRepository("deploy/nginx/wao.wayuwa.cn.conf.template");
        String activation = readFromRepository("deploy/scripts/activate-nginx-config.sh");
        String rollout = readFromRepository("deploy/jenkins/ci-deploy.sh");
        String routingPreparation = section(rollout,
                "prepare_cloud_routing() {", "\n}\n\ndeploy_cloud() {");

        assertThat(nginx)
                .contains("location = /game {")
                .contains("return 308 /games;")
                .contains("location = /insight {")
                .contains("return 410;")
                .contains("include /etc/nginx/wao-external-apps.d/*.conf;");
        assertThat(activation)
                .contains("external_apps_dir='/etc/nginx/wao-external-apps.d'")
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
    void automaticDdnsReloadDefersWhileGatewayIsDrained() throws IOException {
        String activation = readFromRepository("deploy/scripts/activate-nginx-config.sh");
        String stateScript = readFromRepository("deploy/scripts/set-nginx-upstream-state.sh");
        String nginx = readFromRepository("deploy/nginx/wao.wayuwa.cn.conf.template");

        assertThat(activation)
                .contains("deployment_drain_lock='/run/wao-nginx-upstreams/deployment.lock'")
                .contains("automatic DDNS activation is deferred");
        assertThat(stateScript)
                .contains("MASTER_GATEWAY_STATE=' down'")
                .contains("SLAVE_GATEWAY_STATE=' down'")
                .contains("systemctl reload nginx")
                .contains("rm -f \"${lock_file}\"");
        assertThat(nginx)
                .contains("${MASTER_GATEWAY_IPV4}:9000${MASTER_GATEWAY_STATE}")
                .contains("${SLAVE_GATEWAY_IPV4}:9000${SLAVE_GATEWAY_STATE}");
    }

    @Test
    void masterUsesHostNetworkProxyRestrictedToDockerSources() throws IOException {
        String compose = readFromRepository("deploy/docker-compose-master.yml");
        String deployScript = readFromRepository("deploy/scripts/deploy-linux.sh");
        String proxyService = section(compose, "  codex-egress-proxy:", "\n  wao-server:");
        String serverService = section(compose, "  wao-server:", "\n  wao-gateway:");
        String dockerfile = readFromRepository("deploy/Dockerfile");
        String tinyproxy = readFromRepository("deploy/tinyproxy/codex-egress.conf");

        assertThat(proxyService)
                .contains("target: codex-egress-proxy")
                .contains("network_mode: host")
                .contains("127.0.0.1\", \"18888\"")
                .doesNotContain("ports:");
        assertThat(serverService)
                .contains("WAO_AI_CODEX_PROXY_URL: http://${MASTER_BIND_IP:-host.docker.internal}:18888")
                .contains("codex-egress-proxy:")
                .contains("condition: service_healthy")
                .contains("host.docker.internal:host-gateway");
        assertThat(dockerfile)
                .contains("AS codex-egress-proxy")
                .contains("apk add --no-cache curl tinyproxy")
                .contains("USER tinyproxy");
        assertThat(deployScript)
                .contains("requested_image_tag=\"${WAO_IMAGE_TAG:-}\"")
                .contains("WAO_IMAGE_TAG=\"${requested_image_tag}\"")
                .contains("requested_skip_image_build=\"${SKIP_IMAGE_BUILD:-}\"")
                .contains("SKIP_IMAGE_BUILD=\"${requested_skip_image_build}\"")
                .contains("compose build codex-egress-proxy wao-server wao-agent-server wao-gateway")
                .contains("compose up -d --force-recreate --wait codex-egress-proxy")
                .contains("diagnostic_services=\"codex-egress-proxy ${diagnostic_services}\"")
                .doesNotContain("install-linux-codex.sh");
        assertThat(tinyproxy)
                .contains("Allow 127.0.0.1")
                .contains("Allow 172.16.0.0/12")
                .contains("ConnectPort 443")
                .doesNotContain("Allow 0.0.0.0/0");
    }

    @Test
    void windowsDeploymentRecoversADeadWslDockerDaemonBeforeFailingTheRelease() throws IOException {
        String windowsDeploy = readFromRepository("deploy/scripts/deploy-windows.ps1");

        assertThat(windowsDeploy)
                .contains("while ($deployAttempt -le 2)")
                .contains("Get-Service -Name 'WslService', 'LxssManager'")
                .contains("Restart-Service -InputObject $wslService -Force")
                .contains("wsl.exe --shutdown")
                .contains("Start-Sleep -Seconds 10")
                .contains("failed after restarting the distribution");
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
