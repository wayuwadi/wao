package cn.wayuwa.wao.agent.dto;

import cn.wayuwa.wao.agent.ai.GeneratedContent;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 一条发布任务。
 *
 * @param status  DRAFT / GENERATING / WAITING_REVIEW / PUBLISHING / PUBLISHED / FAILED
 * @param content 提交给平台的正文（抖音是简短文案，公众号是全文）
 * @param blocks  创作用的结构化分段：短视频是口播脚本，公众号是小标题段落，小红书为空
 */
public record PublishTaskDto(String id, String platform, String platformLabel, String platformAccountId,
                             String platformAccountName, String mediaAssetId, String mediaName,
                             String topic, String title, String content, List<String> tags,
                             String coverText, List<GeneratedContent.Block> blocks,
                             String sourceHotspotId, String sourceHotspotTitle,
                             String status, int progress, String platformItemId, String errorMessage,
                             int version, LocalDateTime createdAt, LocalDateTime updatedAt,
                             LocalDateTime publishedAt) {
}
