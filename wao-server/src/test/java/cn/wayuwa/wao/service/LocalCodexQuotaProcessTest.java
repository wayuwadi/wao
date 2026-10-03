package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class LocalCodexQuotaProcessTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String ACCOUNT = """
        {"account":{"type":"chatgpt","email":" User@EXAMPLE.com ","planType":"pro"},"token":"never-save"}
        """;
    private static final String RATES = """
        {"rateLimits":{"primary":{"usedPercent":99}},
         "rateLimitsByLimitId":{"codex":{"primary":{"usedPercent":12,"windowDurationMins":300,"resetsAt":1900000000},
           "secondary":{"usedPercent":34,"windowDurationMins":10080,"resetsAt":1900100000}},
           "model":{"primary":{"usedPercent":56,"windowDurationMins":300}}},
         "rateLimitResetCredits":{"availableCount":7,"credits":[{"grantedAt":1800000000,"expiresAt":2000000000}]},
         "credits":{"balance":"99999"},"accessToken":"never-save"}
        """;

    @Test void prefersBucketsAndAuthoritativeResetCountWithoutCredentials() throws Exception {
        var report = LocalCodexQuotaProcess.normalize(json.readTree(ACCOUNT), json.readTree(RATES));
        assertThat(report.accountEmail()).isEqualTo("user@example.com");
        assertThat(report.limits()).hasSize(2);
        assertThat(report.limits().get(0).windows().get(1).usedPercent()).isEqualTo(34);
        assertThat(report.limits().get(0).windows().get(1).resetsAt()).isEqualTo(1900100000L);
        assertThat(report.resetCredits().availableCount()).isEqualTo(7);
        assertThat(json.writeValueAsString(report)).doesNotContain("never-save", "99999");
    }

    @Test void legacyWindowsAndMissingResetCountRemainUnknown() throws Exception {
        var report = LocalCodexQuotaProcess.normalize(json.readTree(ACCOUNT), json.readTree("""
            {"rateLimits":{"primary":{"usedPercent":20,"windowDurationMins":300}},"rateLimitResetCredits":null}
            """));
        assertThat(report.limits().get(0).id()).isEqualTo("codex");
        assertThat(report.resetCredits()).isNull();
        assertThat(report.limits().get(0).windows().get(0).resetsAt()).isNull();
    }

    @Test void zeroIsKnownAndApiKeyAccountsAreRejected() throws Exception {
        var rates = json.readTree(RATES.replace("\"availableCount\":7", "\"availableCount\":0"));
        assertThat(LocalCodexQuotaProcess.normalize(json.readTree(ACCOUNT), rates).resetCredits().availableCount()).isZero();
        assertThatThrownBy(() -> LocalCodexQuotaProcess.normalize(json.readTree("{\"account\":{\"type\":\"apiKey\"}}"), rates))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @EnabledIfSystemProperty(named = "wao.codex.live", matches = "true")
    void readsActualLoggedInAccountWithoutRunningInference() throws Exception {
        var report = LocalCodexQuotaProcess.read(System.getProperty("wao.codex.executable", "codex"), Duration.ofSeconds(8));
        assertThat(report.accountEmail()).isNotBlank();
        assertThat(report.limits()).isNotEmpty();
        System.out.println("本机真实采样：窗口组=" + report.limits().size() + "，重置卡字段=" + (report.resetCredits() != null));
    }
}
