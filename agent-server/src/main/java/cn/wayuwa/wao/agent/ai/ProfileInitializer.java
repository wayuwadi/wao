package cn.wayuwa.wao.agent.ai;

import cn.wayuwa.wao.agent.dto.WorkspaceProfileDto;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/** 使用已配置的真实模型，把成员需求整理成可继续编辑的账号定位。 */
@Component
public class ProfileInitializer {
    private final ChatClient chat;

    public ProfileInitializer(ChatClient chat) {
        this.chat = chat;
    }

    /** 根据成员原文生成九项定位；模型不可用或结果不合法时明确失败。 */
    public WorkspaceProfileDto initialize(String needs) {
        String raw = chat.complete("""
                你是自媒体账号定位顾问。根据用户的真实需求整理账号定位，只返回 JSON 对象，不要解释。
                字段固定为 niche、audience、tone、avoid、contentTopics、lengthStructure、updateFrequency、primaryPlatforms、imageStyle。
                不编造用户未提供的经历、成绩、账号数据或平台连接状态；信息不足时给出保守、可编辑的建议。
                """, needs, 0.4);
        JsonNode node = chat.readJson(raw, false);
        return new WorkspaceProfileDto(text(node, "niche"), text(node, "audience"), text(node, "tone"),
                text(node, "avoid"), text(node, "contentTopics"), text(node, "lengthStructure"),
                text(node, "updateFrequency"), text(node, "primaryPlatforms"), text(node, "imageStyle"), false);
    }

    private String text(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? null : value;
    }
}
