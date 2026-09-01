package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiRunFailureClassifierTest {

    @Test
    void recognizesRunnerGithubCredentialFailureFromRealSummarySignals() {
        var failure = AiRunFailureClassifier.classify("failed",
                "PR 创建失败：Runner 缺少 GH_TOKEN，gh 返回 HTTP 401 Requires authentication (GitHub)");

        assertThat(failure).hasValueSatisfying(value -> {
            assertThat(value.kind()).isEqualTo("INFRASTRUCTURE");
            assertThat(value.code()).isEqualTo("RUNNER_GITHUB_CREDENTIALS");
            assertThat(value.label()).isEqualTo("执行节点 GitHub 凭据不可用");
        });
        assertThat(AiRunFailureClassifier.classify("failed",
                "PR 创建失败：gh 命令返回 401，GitHub 无法完成认证"))
                .hasValueSatisfying(value -> assertThat(value.code())
                        .isEqualTo("RUNNER_GITHUB_CREDENTIALS"));
    }

    @Test
    void keepsTaskSpecificCredentialBlockAsOrdinaryFailure() {
        assertThat(AiRunFailureClassifier.classify("failed",
                "123 网盘缺少 WebDAV 用户名和密码，需要管理员配置后继续")).isEmpty();
        assertThat(AiRunFailureClassifier.classify("succeeded",
                "已修复 GitHub GH_TOKEN 的 HTTP 401 问题")).isEmpty();
    }
}
