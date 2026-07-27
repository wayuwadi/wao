package cn.wayuwa.wao.service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.dto.ImageRef;
import cn.wayuwa.wao.dto.SharedTaskDto;
import cn.wayuwa.wao.dto.Step;
import cn.wayuwa.wao.dto.TaskDto;
import org.springframework.stereotype.Service;

/**
 * 任务分享服务：把任务 / 子任务转成可公开访问的只读视图与 Markdown 文本。
 *
 * <p>分享内容只保留标题、状态、描述、图片与子任务，负责人、参与人、评论、活动时间线
 * 一律不外泄。主任务链接带上全部子任务，子任务链接只有它自己 —— 对齐「复制给 AI 让它
 * 直接读」的用法。
 */
@Service
public class ShareService {

    private final TaskService tasks;
    private final ProjectService projects;
    private final AiRunService aiRuns;

    public ShareService(TaskService tasks, ProjectService projects, AiRunService aiRuns) {
        this.tasks = tasks;
        this.projects = projects;
        this.aiRuns = aiRuns;
    }

    /**
     * 取一个任务的公开分享视图。
     *
     * @param taskId   任务 id
     * @param openOnly 为 true 时只带上「还没人做」的子任务 —— 已经做完的条目属于噪音，
     *                 已经单独派给 AI 的条目则已经有人在做，两种都不该再丢给下一个 AI 重做一遍
     * @return 分享视图
     * @throws NotFoundException 任务不存在时
     */
    public SharedTaskDto shareTask(String taskId, boolean openOnly) {
        TaskDto t = tasks.get(taskId);
        List<Step> subs = nn(t.subs());
        if (openOnly) {
            java.util.Set<String> dispatched = aiRuns.dispatchedSubIds(taskId);
            List<Step> all = subs;
            subs = java.util.stream.IntStream.range(0, all.size())
                    .filter(i -> !all.get(i).done() && !dispatched.contains(subKey(taskId, all.get(i), i)))
                    .mapToObj(all::get).toList();
        }
        return new SharedTaskDto("task", t.id(), null, t.title(), t.status(), t.pr(), t.due(),
                t.project(), projectName(t.project()), nz(t.desc()), nn(t.images()), subs);
    }

    /**
     * 取单个子任务的公开分享视图（只有它自己的标题、描述与图片）。
     *
     * @param taskId 所属任务 id
     * @param subId  子任务标识；历史数据没有 id 时可传 {@code <taskId>#<下标>}
     * @return 分享视图
     * @throws NotFoundException 任务或子任务不存在时
     */
    public SharedTaskDto shareSub(String taskId, String subId) {
        TaskDto t = tasks.get(taskId);
        List<Step> subs = nn(t.subs());
        for (int i = 0; i < subs.size(); i++) {
            Step s = subs.get(i);
            if (subKey(taskId, s, i).equals(subId)) {
                return new SharedTaskDto("sub", t.id(), subId, s.text(),
                        s.done() ? "已完成" : "未完成", null, null,
                        t.project(), projectName(t.project()), nz(s.desc()), nn(s.images()), List.of());
            }
        }
        throw new NotFoundException("子任务不存在：" + subId);
    }

    /**
     * 把分享视图渲染成 Markdown 全文 —— AI 直接 GET 这个地址就能读到完整上下文。
     *
     * <p>图片地址一律补成绝对地址：图床里存的是 {@code /api/images/{id}/raw} 这种
     * 站内相对路径，Markdown 文本被复制到别处（AI、聊天窗口、其他文档）后相对路径
     * 解析不出来，图就丢了。
     *
     * @param shared  分享视图
     * @param baseUrl 公网访问的根地址，如 {@code https://wao.wayuwa.cn}
     * @return Markdown 文本
     */
    public String toMarkdown(SharedTaskDto shared, String baseUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(shared.title()).append("\n\n");

        sb.append("- 状态：").append(nz(shared.status())).append('\n');
        if (shared.pr() != null) {
            sb.append("- 优先级：").append(shared.pr()).append('\n');
        }
        if (shared.due() != null && !shared.due().isBlank()) {
            sb.append("- 截止：").append(shared.due()).append('\n');
        }
        if (!shared.project().isBlank()) {
            sb.append("- 所属项目：").append(shared.project()).append('\n');
        }
        sb.append('\n');

        sb.append("## 描述\n\n");
        sb.append(shared.desc().isBlank() ? "（暂无描述）" : absolutizeMarkdown(shared.desc(), baseUrl))
                .append("\n\n");

        appendImages(sb, shared.images(), baseUrl);

        if (!shared.subs().isEmpty()) {
            sb.append("## 子任务\n\n");
            for (Step s : shared.subs()) {
                sb.append("- [").append(s.done() ? "x" : " ").append("] ").append(s.text()).append('\n');
                if (s.desc() != null && !s.desc().isBlank()) {
                    // 子任务描述整体缩进两格，作为该条目的下级内容
                    sb.append(absolutizeMarkdown(s.desc(), baseUrl).indent(2));
                }
                if (s.images() != null) {
                    for (ImageRef im : s.images()) {
                        sb.append("  ![").append(nz(im.name())).append("](")
                                .append(absolute(nz(im.url()), baseUrl)).append(")\n");
                    }
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void appendImages(StringBuilder sb, List<ImageRef> images, String baseUrl) {
        if (images.isEmpty()) {
            return;
        }
        sb.append("## 图片\n\n");
        for (ImageRef im : images) {
            sb.append("![").append(nz(im.name())).append("](")
                    .append(absolute(nz(im.url()), baseUrl)).append(")\n");
        }
        sb.append('\n');
    }

    /** 匹配 Markdown 里指向站内绝对路径的图片 / 链接目标，如 {@code ](/api/images/x/raw)}。 */
    private static final Pattern SITE_RELATIVE = Pattern.compile("(!?\\[[^\\]]*\\]\\()(/[^)\\s]*)");

    /** 把描述正文里内联的站内图片地址补成绝对地址（编辑器插入的图就在这里）。 */
    private static String absolutizeMarkdown(String markdown, String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return markdown;
        }
        return SITE_RELATIVE.matcher(markdown)
                .replaceAll(m -> Matcher.quoteReplacement(m.group(1) + trimEnd(baseUrl) + m.group(2)));
    }

    /** 单个地址的绝对化；已经是绝对地址或 data URL 的原样返回。 */
    private static String absolute(String url, String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank() || !url.startsWith("/")) {
            return url;
        }
        return trimEnd(baseUrl) + url;
    }

    private static String trimEnd(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** 与前端 {@code data.subKey} 保持一致：优先用子任务自身 id，历史数据回落到下标合成键。 */
    private static String subKey(String taskId, Step s, int idx) {
        return s.id() == null || s.id().isBlank() ? taskId + "#" + idx : s.id();
    }

    private String projectName(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return "";
        }
        try {
            var project = projects.get(projectId);
            return project == null ? "" : nz(project.name());
        } catch (NotFoundException ex) {
            // 项目已删除但任务还在时，分享页不该整个 500，去掉项目名照常渲染
            return "";
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static <T> List<T> nn(List<T> list) {
        return list == null ? List.of() : list;
    }
}
