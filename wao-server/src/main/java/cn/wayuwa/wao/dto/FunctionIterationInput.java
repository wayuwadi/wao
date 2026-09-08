package cn.wayuwa.wao.dto;

/** Runner 从本次 Git 提交中提取的产品更新与功能迭代候选。 */
public record FunctionIterationInput(String commitSha, String scope, String subject, String committedAt) {
    public FunctionIterationInput(String commitSha, String scope, String subject) {
        this(commitSha, scope, subject, null);
    }
}
