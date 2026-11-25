package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class LocalAgyQuotaProcessTest {

    private static final String SAMPLE_JSON = """
            {
              "conversation_id": "",
              "status": "SUCCESS",
              "response": "ignored",
              "command": {
                "name": "usage",
                "data": {
                  "groups": [
                    {
                      "name": "Gemini Models",
                      "buckets": [
                        {
                          "id": "gemini-weekly",
                          "name": "Weekly Limit Remaining",
                          "window": "weekly",
                          "remaining_fraction": 0.787266,
                          "reset_time": "2026-12-01T03:53:02Z"
                        },
                        {
                          "id": "gemini-5h",
                          "name": "Five Hour Limit Remaining",
                          "window": "5h",
                          "remaining_fraction": 0.91372,
                          "reset_time": "2026-11-25T07:28:05Z"
                        }
                      ]
                    },
                    {
                      "name": "Claude and GPT models",
                      "buckets": [
                        {
                          "id": "3p-weekly",
                          "name": "Weekly Limit Remaining",
                          "window": "weekly",
                          "remaining_fraction": 1,
                          "reset_time": "1970-01-01T00:00:00Z"
                        },
                        {
                          "id": "3p-5h",
                          "name": "Five Hour Limit Remaining",
                          "window": "5h",
                          "remaining_fraction": 1,
                          "reset_time": "1970-01-01T00:00:00Z"
                        }
                      ]
                    }
                  ]
                }
              }
            }
            """;

    @Test
    void parsesGroupsAndNormalizesEpochZeroToNull() throws Exception {
        var report = LocalAgyQuotaProcess.parseOutput(SAMPLE_JSON, " Agy 主号 ");
        assertThat(report.accountLabel()).isEqualTo("Agy 主号");
        assertThat(report.groups()).hasSize(2);
        var gemini = report.groups().get(0);
        assertThat(gemini.name()).isEqualTo("Gemini Models");
        assertThat(gemini.buckets()).hasSize(2);
        assertThat(gemini.buckets().get(0).remainingPercent()).isEqualTo(79);
        assertThat(gemini.buckets().get(0).resetsAt()).isNotNull();
        assertThat(gemini.buckets().get(1).remainingPercent()).isEqualTo(91);

        var claude = report.groups().get(1);
        assertThat(claude.buckets()).hasSize(2);
        assertThat(claude.buckets().get(0).remainingPercent()).isEqualTo(100);
        assertThat(claude.buckets().get(0).resetsAt()).isNull();
    }

    @Test
    void rejectsNonSuccessOrEmptyGroups() {
        assertThatThrownBy(() -> LocalAgyQuotaProcess.parseOutput("{\"status\":\"ERROR\"}", "Agy"))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> LocalAgyQuotaProcess.parseOutput(
                "{\"status\":\"SUCCESS\",\"command\":{\"name\":\"usage\",\"data\":{\"groups\":[]}}}", "Agy"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
