package cn.wayuwa.wao.service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.dto.DocBlockDto;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.ImageRef;
import cn.wayuwa.wao.dto.SharedDocDto;
import cn.wayuwa.wao.dto.SharedTaskDto;
import cn.wayuwa.wao.dto.Step;
import cn.wayuwa.wao.dto.TaskDto;
import org.springframework.stereotype.Service;

/**
 * 任务 / 文档分享服务：把任务、子任务、文档、小记转成可公开访问的只读视图与 Markdown 文本。
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
    private final KnowledgeService knowledge;

    public ShareService(TaskService tasks, ProjectService projects, AiRunService aiRuns,
            KnowledgeService knowledge) {
        this.tasks = tasks;
        this.projects = projects;
        this.aiRuns = aiRuns;
        this.knowledge = knowledge;
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
                t.project(), projectName(t.project()), nz(t.desc()), nn(t.images()), subs,
                nn(t.pdcaCycles()));
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
                        t.project(), projectName(t.project()), nz(s.desc()), nn(s.images()), List.of(), List.of());
            }
        }
        throw new NotFoundException("子任务不存在：" + subId);
    }

    /**
     * 取一篇文档（或小记）的公开分享视图。
     *
     * @param docId 文档 id
     * @return 分享视图；正文已转成标准 Markdown（提示块、引用块用 blockquote 表达）
     * @throws NotFoundException 文档不存在或已在回收站时
     */
    public SharedDocDto shareDoc(String docId) {
        DocDto d = knowledge.getDoc(docId);
        boolean note = KnowledgeService.NOTES_KB_ID.equals(d.kbId());
        return new SharedDocDto(d.id(), nz(d.title()), note, d.kbId(), d.updated(), docMarkdown(nn(d.blocks())));
    }

    /**
     * 把文档分享视图渲染成 Markdown 全文（含标题与更新时间），给 {@code .md} 端点用。
     *
     * @param shared  分享视图
     * @param baseUrl 公网访问的根地址
     * @return Markdown 文本
     */
    public String toMarkdown(SharedDocDto shared, String baseUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(shared.title().isBlank()
                ? (shared.note() ? "未命名小记" : "未命名文档") : shared.title()).append("\n\n");
        if (shared.updated() != null && !shared.updated().isBlank()) {
            sb.append("- 更新于：").append(shared.updated()).append("\n\n");
        }
        sb.append(shared.md().isBlank() ? "（暂无内容）" : absolutizeMarkdown(shared.md(), baseUrl)).append('\n');
        return sb.toString();
    }

    /**
     * 文档块 → 标准 Markdown。规则与前端 {@code blocksToText} 对齐，但提示块（call）
     * 不用编辑器私有的 {@code !>} 语法，而用任何 Markdown 渲染器都认识的 blockquote。
     */
    private static String docMarkdown(List<DocBlockDto> blocks) {
        return blocks.stream().map(b -> {
            if (b.h() != null) {
                int level = b.hLevel() == null ? 2 : Math.max(1, Math.min(6, b.hLevel()));
                return "#".repeat(level) + " " + b.h();
            }
            if (b.p() != null) {
                return b.p();
            }
            if (b.call() != null) {
                return "> 💡 " + b.call();
            }
            if (b.q() != null && !b.q().isEmpty()) {
                String who = b.q().size() > 1 && b.q().get(1) != null && !b.q().get(1).isBlank()
                        ? " —— " + b.q().get(1) : "";
                return "> " + b.q().get(0) + who;
            }
            if (b.ul() != null) {
                return b.ul().stream().map(x -> "- " + x).collect(java.util.stream.Collectors.joining("\n"));
            }
            return "";
        }).filter(s -> !s.isBlank()).collect(java.util.stream.Collectors.joining("\n\n"));
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

    /**
     * 把一次合并 AI Run 的全部任务渲染为一份稳定上下文。
     *
     * <p>任务关系只保存 id 和顺序，正文每次从真实任务读取；因此排队期间补充的描述和图片也会进入
     * Runner 最终领取的上下文。每个任务单独成节，避免直接拼接多个一级标题后让 AI 看不出边界。
     *
     * @param runId AI Run id
     * @param openOnly 是否过滤已完成或已单独派工的子任务
     * @param baseUrl 公网根地址，用于绝对化图片
     * @return 一次运行可直接读取的完整 Markdown
     */
    public String toRunMarkdown(String runId, boolean openOnly, String baseUrl) {
        List<String> taskIds = aiRuns.runTaskIds(runId);
        StringBuilder sb = new StringBuilder();
        sb.append("# 合并 AI Run：").append(taskIds.size()).append(" 个任务\n\n")
                .append("> 以下任务属于同一次执行。请在一个工作区和一个上下文中整体推进，逐项完成，")
                .append("并在最终摘要中逐项说明结果与验收证据。\n\n");
        for (int i = 0; i < taskIds.size(); i++) {
            SharedTaskDto task = shareTask(taskIds.get(i), openOnly);
            sb.append("---\n\n## 任务 ").append(i + 1).append('/').append(taskIds.size())
                    .append("：").append(task.title()).append("\n\n");
            String markdown = toMarkdown(task, baseUrl);
            int bodyStart = markdown.indexOf('\n');
            sb.append(bodyStart < 0 ? markdown : markdown.substring(bodyStart + 1).stripLeading());
            if (!markdown.endsWith("\n")) sb.append('\n');
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

    /**
     * 把 Markdown 正文里内联的站内图片 / 链接地址补成绝对地址（编辑器插入的图就在这里）。
     *
     * <p>除分享 Markdown 外，AI 续跑的补充输入也走这里 —— 文本会被直接交给执行电脑上的
     * AI 会话，相对路径在那边解析不出来，粘贴的截图就丢了。
     *
     * @param markdown Markdown 文本
     * @param baseUrl  公网访问的根地址，如 {@code https://wao.wayuwa.cn}；为空时原样返回
     * @return 站内相对地址已补全的 Markdown 文本
     */
    public static String absolutizeMarkdown(String markdown, String baseUrl) {
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
