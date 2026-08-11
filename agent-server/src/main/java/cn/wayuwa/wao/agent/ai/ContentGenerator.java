package cn.wayuwa.wao.agent.ai;

import cn.wayuwa.wao.agent.common.BadRequestException;
import cn.wayuwa.wao.agent.domain.WorkspaceProfileEntity;
import cn.wayuwa.wao.agent.platform.PlatformType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 把一个话题写成指定平台的成稿。
 *
 * <p>不同平台产出形态不同：短视频出口播脚本，公众号出文章，小红书出笔记。差异在 {@link PromptLibrary}，
 * 这里只负责调用与结构校验。未配置模型时 {@link #available()} 为 false，调用方必须据此提示，
 * 不能返回占位文案冒充生成结果。
 */
@Component
public class ContentGenerator {
    private static final int MAX_TAGS = 6;

    private final ChatClient chat;

    public ContentGenerator(ChatClient chat) {
        this.chat = chat;
    }

    public boolean available() {
        return chat.available();
    }

    public String provider() {
        return chat.provider();
    }

    public String model() {
        return chat.model();
    }

    /** 生成成稿。profile 可为 null，此时不带账号定位约束。 */
    public GeneratedContent generate(String topic, PlatformType platform, WorkspaceProfileEntity profile) {
        String raw = chat.complete(PromptLibrary.content(platform, profile), "话题：" + topic, 0.8);
        JsonNode node = chat.readJson(raw, false);

        String title = node.path("title").asText("").trim();
        String body = node.path("body").asText("").trim();
        List<GeneratedContent.Block> blocks = readBlocks(node);
        if (title.isBlank() && body.isBlank() && blocks.isEmpty()) {
            throw new BadRequestException("模型返回的 JSON 里没有可用的标题、正文或分段");
        }
        // 公众号这类长文常常只给 blocks 不给 body，这里兜底拼一份，保证发布时有正文可用。
        if (body.isBlank() && !blocks.isEmpty()) {
            body = blocks.stream().map(GeneratedContent.Block::text)
                    .filter(StringUtils::hasText).reduce((a, b) -> a + "\n\n" + b).orElse("");
        }
        return new GeneratedContent(title.isBlank() ? topic : title, body, readTags(node),
                blankToNull(node.path("coverText").asText(null)), blocks);
    }

    private List<GeneratedContent.Block> readBlocks(JsonNode node) {
        List<GeneratedContent.Block> blocks = new ArrayList<>();
        for (JsonNode item : node.path("blocks")) {
            String text = item.path("text").asText("").trim();
            if (text.isBlank()) continue;
            Integer seconds = item.hasNonNull("seconds") && item.path("seconds").isNumber()
                    ? item.path("seconds").asInt() : null;
            blocks.add(new GeneratedContent.Block(blankToNull(item.path("label").asText(null)), text, seconds));
        }
        return blocks;
    }

    private List<String> readTags(JsonNode node) {
        List<String> tags = new ArrayList<>();
        for (JsonNode tag : node.path("tags")) {
            String value = tag.asText("").trim().replace("#", "");
            if (!value.isBlank() && tags.size() < MAX_TAGS) tags.add(value);
        }
        return tags;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() || "null".equals(value) ? null : value.trim();
    }
}
