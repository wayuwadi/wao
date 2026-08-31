package cn.wayuwa.wao.agent.dto;

import jakarta.validation.constraints.Size;

/**
 * 账号定位。
 *
 * @param niche      账号领域，例如「户外露营装备测评」
 * @param audience   目标受众
 * @param tone       语气风格
 * @param avoid      必须回避的内容
 * @param contentTopics 常用内容主题
 * @param lengthStructure 篇幅与结构偏好
 * @param updateFrequency 更新频率
 * @param primaryPlatforms 主要发布平台
 * @param imageStyle 封面与配图风格
 * @param configured 是否已填写领域，未填写时推荐质量会明显下降
 */
public record WorkspaceProfileDto(@Size(max = 200) String niche,
                                  @Size(max = 200) String audience,
                                  @Size(max = 200) String tone,
                                  @Size(max = 500) String avoid,
                                  @Size(max = 500) String contentTopics,
                                  @Size(max = 500) String lengthStructure,
                                  @Size(max = 200) String updateFrequency,
                                  @Size(max = 200) String primaryPlatforms,
                                  @Size(max = 500) String imageStyle,
                                  boolean configured) {
}
