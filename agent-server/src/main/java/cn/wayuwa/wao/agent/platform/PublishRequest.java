package cn.wayuwa.wao.agent.platform;

import java.nio.file.Path;
import java.util.List;

/**
 * 一次发布请求。
 *
 * @param title     标题
 * @param content   正文文案
 * @param tags      话题标签，不带 # 前缀
 * @param mediaKind 素材形态，见 {@link MediaKind}
 * @param mediaPath 素材在本节点上的绝对路径
 * @param mediaName 素材原始文件名
 */
public record PublishRequest(String title, String content, List<String> tags,
                             String mediaKind, Path mediaPath, String mediaName) {

    /** 抖音等短视频平台的正文：标题 + 话题标签拼成一段 text。 */
    public String captionWithTags() {
        StringBuilder caption = new StringBuilder(content == null || content.isBlank() ? title : content);
        for (String tag : tags) {
            if (tag != null && !tag.isBlank()) caption.append(" #").append(tag.trim());
        }
        return caption.toString();
    }
}
