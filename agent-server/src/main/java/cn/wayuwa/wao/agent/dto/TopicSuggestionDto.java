package cn.wayuwa.wao.agent.dto;

import java.time.LocalDateTime;

/**
 * 一条选题建议。
 *
 * @param angle    具体切入角度
 * @param reason   为什么适合这个账号
 * @param fitScore 契合度 0-100，模型给出，仅用于排序参考
 * @param status   NEW / USED / DISMISSED
 */
public record TopicSuggestionDto(String id, String platform, String platformLabel, String topic,
                                 String angle, String reason, int fitScore, String status,
                                 String hotspotId, String hotspotTitle, String hotspotUrl,
                                 LocalDateTime createdAt) {
}
