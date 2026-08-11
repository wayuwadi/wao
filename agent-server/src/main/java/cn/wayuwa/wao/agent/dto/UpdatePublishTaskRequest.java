package cn.wayuwa.wao.agent.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 人工审核阶段修改发布内容。version 用于乐观锁，避免定时刷新覆盖正在编辑的文案。
 */
public record UpdatePublishTaskRequest(@NotNull(message = "缺少版本号") Integer version,
                                       @Size(max = 200) String title,
                                       String content,
                                       List<String> tags,
                                       String platformAccountId,
                                       String mediaAssetId) {
}
