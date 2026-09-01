package cn.wayuwa.wao.service;

import java.util.Locale;
import java.util.Optional;
import org.springframework.util.StringUtils;

/** 把可批量恢复的执行基础设施故障归一成稳定指纹，避免把任务自身失败混在一起。 */
public final class AiRunFailureClassifier {
    public static final String INFRASTRUCTURE = "INFRASTRUCTURE";
    public static final String RUNNER_GITHUB_CREDENTIALS = "RUNNER_GITHUB_CREDENTIALS";
    public static final String RUNNER_GITHUB_CREDENTIALS_LABEL = "执行节点 GitHub 凭据不可用";

    private AiRunFailureClassifier() { }

    /** 目前只识别有真实故障样本支撑的 GitHub 节点凭据异常；其它失败保持原分类。 */
    public static Optional<Failure> classify(String status, String resultSummary) {
        if (!"failed".equalsIgnoreCase(status) || !StringUtils.hasText(resultSummary)) {
            return Optional.empty();
        }
        String normalized = resultSummary.toLowerCase(Locale.ROOT);
        boolean githubContext = normalized.contains("github")
                || normalized.contains("gh_token")
                || normalized.contains("requires authentication");
        boolean credentialSymptom = normalized.contains("requires authentication")
                || normalized.contains("401")
                || normalized.contains("gh_token")
                || normalized.contains("github 凭据")
                || normalized.contains("github 认证")
                || normalized.contains("github 授权");
        if (!githubContext || !credentialSymptom) return Optional.empty();
        return Optional.of(new Failure(INFRASTRUCTURE, RUNNER_GITHUB_CREDENTIALS,
                RUNNER_GITHUB_CREDENTIALS_LABEL));
    }

    /** 前端展示和批量重跑共同使用的稳定故障契约。 */
    public record Failure(String kind, String code, String label) { }
}
