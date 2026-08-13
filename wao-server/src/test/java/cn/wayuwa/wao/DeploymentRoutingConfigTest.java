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
                .contains("WAO_DAILY_GUIDANCE_WARMUP_ENABLED: \"true\"");
        assertThat(slaveCompose)
                .contains("wao-agent-server:")
                .contains("target: agent-runtime")
                .contains("WAO_AGENT_SERVER_URI: http://wao-agent-server:8081")
                .doesNotContain("WAO_DAILY_GUIDANCE_WARMUP_ENABLED");
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
        assertThat(route.matcher("/api/ai/runner/assistant/claim").find()).isTrue();
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
    void deploymentPublishesNginxConfigAndProbesOpenAiInsideMasterContainer() throws IOException {
        String workflow = readFromRepository(".github/workflows/ci-cd.yml");
        String probe = readFromRepository("deploy/scripts/probe-codex-egress.sh");

        assertThat(workflow)
                .contains("WAO_NGINX_RENDER_OUTPUT=wao-nginx.conf sh deploy/scripts/render-nginx-config.sh")
                .contains("scp -o ConnectTimeout=15 wao-nginx.conf")
                .contains("nginx -t")
                .contains("/api/tianren/readings/today/interpret/stream")
                .contains("probe-codex-egress.sh");
        assertThat(workflow)
                .contains("set-nginx-upstream-state.sh")
                .contains("set_upstream_state slave down")
                .contains("set_upstream_state master down")
                .contains("test \"$goal_route\" = 'dual-gateway-ai'")
                .contains("/api/ai/runner/accounts");
        assertThat(workflow.indexOf("prepare_cloud_routing >routing.log"))
                .isLessThan(workflow.indexOf("deploy_slave >slave.log"));
        assertThat(workflow.indexOf("deploy_slave >slave.log"))
                .isLessThan(workflow.indexOf("deploy_cloud >cloud.log"));
        assertThat(workflow)
                .contains("scp -o ConnectTimeout=15 wao-release.tgz wao-secrets.env")
                .contains("deploy/scripts/deploy-linux.sh\" master; rm -f /tmp/wao-secrets.env")
                .doesNotContain("Prepare Linux Codex package")
                .doesNotContain("codex-package-x86_64-unknown-linux-musl.tar.gz")
                .doesNotContain("upload_master_resumable")
                .doesNotContain("WAO_CODEX_PACKAGE")
                .doesNotContain("WAO_CODEX_CHECKSUMS");
        assertThat(workflow.indexOf("if ! deploy_slave >slave.log 2>&1; then"))
                .isLessThan(workflow.indexOf("if ! deploy_cloud >cloud.log 2>&1; then"));
        assertThat(workflow.indexOf("if ! deploy_cloud >cloud.log 2>&1; then"))
                .isLessThan(workflow.indexOf("if ! deploy_master >master.log 2>&1; then"));
        assertThat(workflow)
                .doesNotContain("deploy_master >master.log 2>&1 &")
                .contains("test \"$ai_read_route\" = 'dual-gateway-ai'")
                .contains("test \"$ai_write_route\" = 'linux-vpn-master'");
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
    void mainSiteDeploymentRetiresSplitAppsAndPreservesThirdPartyRoutes() throws IOException {
        String nginx = readFromRepository("deploy/nginx/wao.wayuwa.cn.conf.template");
        String activation = readFromRepository("deploy/scripts/activate-nginx-config.sh");
        String workflow = readFromRepository(".github/workflows/ci-cd.yml");
        String routingPreparation = section(workflow,
                "prepare_cloud_routing() {", "\n          }\n\n          deploy_cloud() {");

        assertThat(nginx)
                .contains("location = /game {")
                .contains("return 308 /games;")
                .contains("location = /insight {")
                .contains("return 410;")
                .contains("include /etc/nginx/wao-external-apps.d/*.conf;");
        assertThat(activation)
                .contains("external_apps_dir='/etc/nginx/wao-external-apps.d'")
                .contains("install -d -m 0755 \"${external_apps_dir}\"");
        assertThat(workflow)
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
