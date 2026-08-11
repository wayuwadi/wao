package cn.wayuwa.wao.agent.ai;

import cn.wayuwa.wao.agent.domain.WorkspaceProfileEntity;
import cn.wayuwa.wao.agent.platform.PlatformType;
import org.springframework.util.StringUtils;

/** 各平台的成稿提示词。平台调性差异集中在这里，改口径不用动调用逻辑。 */
public final class PromptLibrary {

    private PromptLibrary() {
    }

    /** 把工作区画像拼成一段约束，没填画像时返回空串。 */
    public static String profileClause(WorkspaceProfileEntity profile) {
        if (profile == null) return "";
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(profile.getNiche())) sb.append("账号领域：").append(profile.getNiche()).append("。");
        if (StringUtils.hasText(profile.getAudience())) sb.append("目标受众：").append(profile.getAudience()).append("。");
        if (StringUtils.hasText(profile.getTone())) sb.append("语气风格：").append(profile.getTone()).append("。");
        if (StringUtils.hasText(profile.getAvoid())) sb.append("必须避免：").append(profile.getAvoid()).append("。");
        return sb.isEmpty() ? "" : "\n" + sb;
    }

    /** 成稿提示词。要求模型只返回 JSON，字段随平台形态变化。 */
    public static String content(PlatformType platform, WorkspaceProfileEntity profile) {
        String base = "你是资深自媒体内容编辑。根据用户给的话题产出可直接使用的成稿。"
                + "只返回一个 JSON 对象，不要代码块，不要任何解释文字。" + profileClause(profile);
        return base + "\n" + switch (platform) {
            case DOUYIN -> """
                    平台：抖音短视频。产出口播脚本，总时长控制在 45 秒以内。
                    格式：
                    {"title":"视频标题，20字内","coverText":"封面大字，12字内",
                     "body":"发布时填在抖音的文案，50字内，不要把脚本全文放这里",
                     "tags":["标签1","标签2"],
                     "blocks":[{"label":"钩子","text":"前3秒说的话","seconds":3},
                               {"label":"痛点","text":"...","seconds":8},
                               {"label":"干货","text":"...","seconds":20},
                               {"label":"结尾引导","text":"...","seconds":5}]}
                    钩子必须在前 3 秒抛出冲突或结果，句子短、口语化、适合念出来，不要书面语。""";
            case WECHAT_CHANNELS -> """
                    平台：微信视频号。产出口播脚本，总时长 60 到 90 秒，语气比抖音稳重。
                    格式：
                    {"title":"标题,25字内","coverText":"封面文字,15字内",
                     "body":"发布时填的简短说明,80字内",
                     "tags":["标签1"],
                     "blocks":[{"label":"开场","text":"...","seconds":8},
                               {"label":"主体","text":"...","seconds":50},
                               {"label":"收尾","text":"...","seconds":12}]}""";
            case WECHAT_MP -> """
                    平台：微信公众号。产出结构完整的文章。
                    格式：
                    {"title":"标题,30字内","coverText":null,
                     "body":"文章全文，包含导语正文结语，用换行分段",
                     "tags":["标签1"],
                     "blocks":[{"label":"导语","text":"...","seconds":null},
                               {"label":"小标题一","text":"...","seconds":null},
                               {"label":"结语","text":"...","seconds":null}]}
                    body 必须是 blocks 拼起来的完整文章，正文 800 到 1500 字，语气沉稳有信息量。""";
            case XIAOHONGSHU -> """
                    平台：小红书。产出笔记。
                    格式：
                    {"title":"标题,20字内,可带1个emoji","coverText":null,
                     "body":"笔记正文，第一人称分享口吻，多用换行，适度emoji，300字内",
                     "tags":["标签1","标签2","标签3"],
                     "blocks":[]}
                    blocks 留空数组。标签用小红书常见话题词，不要带 # 号。""";
        };
    }

    /** 选题推荐提示词。 */
    public static String recommend(PlatformType platform, WorkspaceProfileEntity profile, int wanted) {
        return """
                你是自媒体选题策划。下面是当前各平台热榜。请挑出最适合这个账号做成「%s」内容的 %d 条，
                并给出具体切入角度。%s
                只返回一个 JSON 数组，不要代码块，不要解释文字。格式：
                [{"index":热点编号,"topic":"改写后的选题标题,25字内",
                  "angle":"具体怎么切入,60字内","reason":"为什么适合这个账号,40字内","fitScore":0到100的契合度}]
                要求：
                - index 必须是下面列表里真实存在的编号，不要编造。
                - 与账号领域明显无关的热点直接不选，宁可少选也不要凑数。
                - topic 不是照抄热点标题，要改写成这个账号能讲的角度。
                - 如果没有任何热点适合这个账号，返回空数组 []。
                """.formatted(platform.label(), wanted, profileClause(profile));
    }
}
