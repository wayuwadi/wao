package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import java.util.regex.Pattern;

/** 确定性保护导入书摘，模型只处理个人感想；正文前的真实图片原样保留。 */
public final class BookExcerptProtection {
    private static final Pattern PARTS = Pattern.compile("\\A((?:!\\[[^\\n]*]\\([^\\n]*\\)\\n\\n)*## 书摘\\n\\n> [\\s\\S]*?\\n\\n## 我的感想\\n\\n)([\\s\\S]*)\\z");
    private BookExcerptProtection() {}

    /** 返回可整理的个人感想；普通小记返回全文。 */
    public static String thoughts(String markdown) {
        if (markdown == null) return "";
        var match = PARTS.matcher(markdown);
        return match.matches() ? match.group(2) : markdown;
    }

    /** 用服务端原始前缀拼回润色结果，书摘不会进入正文模型。 */
    public static String reassemble(String source, String polishedThoughts) {
        if (source == null) return polishedThoughts;
        var match = PARTS.matcher(source);
        return match.matches() ? match.group(1) + polishedThoughts : polishedThoughts;
    }

    /** 拒绝改动了书摘的派生结果；不会拒绝用户显式修改正文。 */
    public static void assertPreserved(String source, String result) {
        if (source == null) return;
        var original = PARTS.matcher(source);
        if (!original.matches()) return;
        var candidate = PARTS.matcher(result == null ? "" : result);
        if (!candidate.matches() || !original.group(1).substring(original.group(1).indexOf("## 书摘"))
                .equals(candidate.group(1).substring(candidate.group(1).indexOf("## 书摘"))))
            throw new BadRequestException("AI 结果改变了书摘或来源，未采用；请仅整理我的感想");
    }
}
