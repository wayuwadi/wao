package cn.wayuwa.wao.service;

import cn.wayuwa.wao.domain.ChatReviewRunEntity;
import cn.wayuwa.wao.dto.ChatReviewReportDto;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.HostedPageDto;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 把已通过服务端校验的聊天复盘原子沉淀为公开报告与知识库文档。 */
@Service
public class ChatReviewArtifactService {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private final HostedPageService pages;
    private final KnowledgeService knowledge;

    public ChatReviewArtifactService(HostedPageService pages, KnowledgeService knowledge) {
        this.pages = pages;
        this.knowledge = knowledge;
    }

    /** 两个产物共用数据库事务；worker 接管重入时按运行 id 覆盖同一份产物。 */
    @Transactional
    public Result publish(ChatReviewRunEntity run, ChatReviewReportDto report) {
        String title = title(run);
        HostedPageDto page = pages.saveGeneratedReport(run.getId(), html(title, run, report),
                title, run.getCreatedBy());
        DocDto doc = knowledge.saveChatReviewReport(run.getId(), title, report, page.url(), run.getCreatedBy());
        return new Result(page.url(), doc.id(), "/kb/" + KnowledgeService.CHAT_REVIEW_KB_ID + "?doc=" + doc.id());
    }

    private static String title(ChatReviewRunEntity run) {
        if ("SHARED_CHAT".equals(run.getSourceKind())) {
            return "聊天复盘 · " + ("MESSAGES".equals(run.getRangeKind())
                    ? "最近 " + run.getRangeValue() + " 条"
                    : "最近 " + run.getRangeValue() + " 小时");
        }
        return "聊天复盘 · " + DAY.format(run.getStartDate()) + " 至 " + DAY.format(run.getEndDate());
    }

    private static byte[] html(String title, ChatReviewRunEntity run, ChatReviewReportDto report) {
        String body = """
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <style>body{margin:0;background:#f5f7fb;color:#1f2937;font:16px/1.75 system-ui,sans-serif}main{max-width:920px;margin:0 auto;padding:48px 22px}header,section{background:#fff;border:1px solid #e5e7eb;border-radius:18px;padding:24px;margin-bottom:16px}h1{margin:0 0 8px;font-size:28px}h2{margin:0 0 12px;font-size:19px;color:#4358d6}.meta{color:#6b7280}.text{white-space:pre-wrap}footer{color:#6b7280;font-size:13px;text-align:center;padding:18px}</style></head><body><main>
                <header><h1>%s</h1><div class="meta">真实消息 %d 条 · 由 WAO 只读 AI 生成并通过引用校验</div></header>
                <section><h2>讨论过程</h2><div class="text">%s</div></section>
                <section><h2>讨论方向</h2><div class="text">%s</div></section>
                <section><h2>讨论结论</h2><div class="text">%s</div></section>
                <section><h2>后续建议</h2><div class="text">%s</div></section>
                <footer>WAO 聊天复盘 · 报告内容来自所选真实聊天消息，不包含图片识别或语音转写。</footer>
                </main></body></html>
                """.formatted(escape(title), run.getMessageCount(), escape(report.discussionProcess()),
                escape(report.discussionDirections()), escape(report.conclusions()), escape(report.recommendations()));
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    public record Result(String reportUrl, String documentId, String knowledgeUrl) { }
}
