package cn.wayuwa.wao;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** 防止 Codex/OpenAI 出站流量重新被云端 Nginx 随机分配到无 VPN 的从节点。 */
class DeploymentRoutingConfigTest {

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
                .contains("scp wao-nginx.conf")
                .contains("nginx -t")
                .contains("probe-codex-egress.sh");
        assertThat(probe)
                .contains("elif sudo -n docker info")
                .contains("sudo -n docker \"$@\"")
                .contains("label=com.docker.compose.service=wao-server")
                .contains("https://auth.openai.com/api/accounts/deviceauth/usercode")
                .contains("if [ \"${status}\" != '200' ]");
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
