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
                .contains("WAO_AGENT_SERVER_URI: http://wao-agent-server:8081");
        assertThat(slaveCompose)
                .contains("wao-agent-server:")
                .contains("target: agent-runtime")
                .contains("WAO_AGENT_SERVER_URI: http://wao-agent-server:8081");
    }

    @Test
    void aiApiRoutesOnlyToLinuxMasterGateway() throws IOException {
        String nginx = readFromRepository("deploy/nginx/wao.wayuwa.cn.conf.template");
        String aiUpstream = section(nginx, "upstream wao_ai_gateway {", "\n}");
        String aiLocation = section(nginx,
                "location ~ ^/api/(settings/ai-accounts", " {");
        String aiRoutePattern = aiLocation.substring("location ~ ".length(), aiLocation.length() - " {".length());

        assertThat(aiUpstream)
                .contains("${MASTER_GATEWAY_IPV4}:9000")
                .doesNotContain("${SLAVE_GATEWAY_IPV4}", "backup");
        assertThat(Pattern.compile(aiRoutePattern)
                .matcher("/api/tianren/readings/today/interpret/stream").find()).isTrue();
        assertThat(Pattern.compile(aiRoutePattern)
                .matcher("/api/tianren/readings/today").find()).isFalse();
        assertThat(nginx)
                .contains("proxy_pass https://wao_ai_gateway;")
                .contains("proxy_next_upstream off;")
                .contains("X-WAO-API-Route linux-vpn-master always;");
    }

    @Test
    void readOnlyAiConsoleQueriesUseBothGatewaysButWritesStayOnLinux() throws IOException {
        String nginx = readFromRepository("deploy/nginx/wao.wayuwa.cn.conf.template");
        String readLocation = section(nginx,
                "location ~ ^/api/ai/(?:workflows", "\n    }");
        String writeLocation = section(nginx, "location @wao_ai_write {", "\n    }");

        assertThat(readLocation)
                .contains("if ($request_method !~ ^(GET|HEAD)$) { return 418; }")
                .contains("error_page 418 = @wao_ai_write;")
                .contains("proxy_pass https://wao_gateway;")
                .contains("proxy_next_upstream error timeout http_500 http_502 http_503 http_504;")
                .contains("X-WAO-API-Route dual-gateway-read always;");
        assertThat(writeLocation)
                .contains("proxy_pass https://wao_ai_gateway;")
                .contains("proxy_next_upstream off;")
                .contains("X-WAO-API-Route linux-vpn-master always;");
    }

    @Test
    void deploymentPublishesNginxConfigAndProbesOpenAiInsideMasterContainer() throws IOException {
        String workflow = readFromRepository(".github/workflows/ci-cd.yml");
        String probe = readFromRepository("deploy/scripts/probe-codex-egress.sh");

        assertThat(workflow)
                .contains("envsubst '${MASTER_GATEWAY_IPV4} ${SLAVE_GATEWAY_IPV4}'")
                .contains("scp -o ConnectTimeout=15 wao-nginx.conf")
                .contains("nginx -t")
                .contains("/api/tianren/readings/today/interpret/stream")
                .contains("probe-codex-egress.sh");
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
                .contains("test \"$ai_read_route\" = 'dual-gateway-read'")
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
    void mainSiteDeploymentPreservesIndependentApplicationRoutes() throws IOException {
        String nginx = readFromRepository("deploy/nginx/wao.wayuwa.cn.conf.template");
        String activation = readFromRepository("deploy/scripts/activate-nginx-config.sh");
        String workflow = readFromRepository(".github/workflows/ci-cd.yml");

        assertThat(nginx).contains("include /etc/nginx/wao-external-apps.d/*.conf;");
        assertThat(activation)
                .contains("external_apps_dir='/etc/nginx/wao-external-apps.d'")
                .contains("install -d -m 0755 \"${external_apps_dir}\"");
        assertThat(workflow)
                .contains("install -d -m 0755 /etc/nginx/wao-external-apps.d")
                .doesNotContain("rm -rf /etc/nginx/wao-external-apps.d");
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
