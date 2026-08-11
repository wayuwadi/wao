package cn.wayuwa.wao.agent.dto;

import jakarta.validation.constraints.Size;

/**
 * 账号定位。
 *
 * @param niche      账号领域，例如「户外露营装备测评」
 * @param audience   目标受众
 * @param tone       语气风格
 * @param avoid      必须回避的内容
 * @param configured 是否已填写领域，未填写时推荐质量会明显下降
 */
public record WorkspaceProfileDto(@Size(max = 200) String niche,
                                  @Size(max = 200) String audience,
                                  @Size(max = 200) String tone,
                                  @Size(max = 500) String avoid,
                                  boolean configured) {
}
