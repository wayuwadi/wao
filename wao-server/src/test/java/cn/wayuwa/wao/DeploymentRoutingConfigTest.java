package cn.wayuwa.wao;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** 防止 Codex/OpenAI 出站流量重新被云端 Nginx 随机分配到无 VPN 的从节点。 */
class DeploymentRoutingConfigTest {

    @Test
    void agentUsesIndependentStaticDirectoryAndDedicatedGatewayRoute() throws IOException {
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
        assertThat(masterCompose).contains("WAO_AGENT_SERVER_URI: http://wao-agent-server:8081");
        assertThat(slaveCompose).contains("WAO_AGENT_SERVER_URI: http://wao-agent-server:8081");
    }

    @Test
    void aiApiRoutesOnlyToLinuxMasterGateway() throws IOException {
        String nginx = readFromRepository("deploy/nginx/wao.wayuwa.cn.conf.template");
        String aiUpstream = section(nginx, "upstream wao_ai_gateway {", "\n}");

        assertThat(aiUpstream)
                .contains("${MASTER_PUBLIC_HOST}:9000")
                .doesNotContain("${SLAVE_PUBLIC_HOST}", "backup");
        assertThat(nginx)
                .contains("location ~ ^/api/(settings/ai-accounts(?:/|$)|ai(?:/|$))")
                .contains("proxy_pass https://wao_ai_gateway;")
                .contains("proxy_next_upstream off;")
                .contains("X-WAO-API-Route linux-vpn-master always;");
    }

    @Test
    void deploymentPublishesNginxConfigAndProbesOpenAiInsideMasterContainer() throws IOException {
        String workflow = readFromRepository(".github/workflows/ci-cd.yml");
        String probe = readFromRepository("deploy/scripts/probe-codex-egress.sh");

        assertThat(workflow)
                .contains("envsubst '${MASTER_PUBLIC_HOST} ${SLAVE_PUBLIC_HOST}'")
                .contains("scp -o ConnectTimeout=15 wao-nginx.conf")
                .contains("nginx -t")
                .contains("probe-codex-egress.sh");
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
                .contains("compose build codex-egress-proxy wao-server wao-gateway")
                .contains("compose up -d --force-recreate --wait codex-egress-proxy")
                .contains("diagnostic_services=\"codex-egress-proxy ${diagnostic_services}\"");
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
