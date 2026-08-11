package cn.wayuwa.wao.agent.ai;

import java.util.List;

/**
 * 模型产出的平台成稿。
 *
 * <p>区分两件事：{@code body} 是**提交给平台的正文**（抖音是简短文案，公众号是全文，小红书是笔记正文）；
 * {@code blocks} 是**创作时用的结构**（抖音是口播分段，公众号是小标题段落，小红书通常为空）。
 * 两者混淆会导致把整段口播脚本当作抖音文案发出去。
 *
 * @param coverText 封面文字，视频平台用，图文平台为空
 * @param blocks    结构化分段，按顺序
 */
public record GeneratedContent(String title, String body, List<String> tags,
                               String coverText, List<Block> blocks) {

    /**
     * 一个内容块。
     *
     * @param label   段落名，例如「钩子」「痛点」或文章小标题
     * @param text    段落内容
     * @param seconds 口播时长建议，秒；图文平台为 null
     */
    public record Block(String label, String text, Integer seconds) {
    }

    /** 口播脚本总时长，用于在页面上提示是否超长。 */
    public int totalSeconds() {
        return blocks.stream().map(Block::seconds).filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue).sum();
    }
}
