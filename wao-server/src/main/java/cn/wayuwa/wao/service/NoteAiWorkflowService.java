package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.dto.ArticleImageRequest;
import cn.wayuwa.wao.dto.CreateNoteRequest;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.PatchDocRequest;
import cn.wayuwa.wao.dto.RichDocContentDto;
import cn.wayuwa.wao.dto.SaveNotePolishVersionRequest;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import cn.wayuwa.wao.mapper.NoteAiWorkflowMapper;
import cn.wayuwa.wao.mapper.NoteAiWorkflowMapper.Workflow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 让新小记的标题、总览图和润色在浏览器关闭后仍能安全续跑。 */
@Service
public class NoteAiWorkflowService {
    private static final String PENDING = "PENDING", RUNNING = "RUNNING", DONE = "DONE";
    private static final String FAILED = "FAILED", SKIPPED = "SKIPPED";
    private static final String DEFAULT_IMAGE_PROMPT = "为这篇小记制作简洁的16:9象征性总览图。只表达原文已有主题；不编造人物、数字或事实，不绘制大段文字。";
    private final NoteAiWorkflowMapper workflows;
    private final DocMapper docs;
    private final KnowledgeService knowledge;
    private final TextPolishJobService textJobs;
    private final ArticleImageService imageJobs;
    private final NotePolishVersionService polishVersions;
    private final MemberPreferenceMapper preferences;
    private final ObjectMapper json;

    public NoteAiWorkflowService(NoteAiWorkflowMapper workflows, DocMapper docs, KnowledgeService knowledge,
            TextPolishJobService textJobs, ArticleImageService imageJobs,
            NotePolishVersionService polishVersions, MemberPreferenceMapper preferences, ObjectMapper json) {
        this.workflows = workflows; this.docs = docs; this.knowledge = knowledge; this.textJobs = textJobs;
        this.imageJobs = imageJobs; this.polishVersions = polishVersions; this.preferences = preferences; this.json = json;
    }

    /** 在小记保存响应返回前持久化本篇明确的自动选择；旧客户端不被静默开启。 */
    @Transactional
    public void beginIfRequested(String memberId, DocDto doc, CreateNoteRequest request) {
        if (!Boolean.TRUE.equals(request.autoTitle()) && !Boolean.TRUE.equals(request.autoImage())
                && !Boolean.TRUE.equals(request.autoPolish())) return;
        if (memberId == null || !Objects.equals(memberId, doc.author())
                || !KnowledgeService.NOTES_KB_ID.equals(doc.kbId())) return;
        String markdown = doc.content() == null ? "" : doc.content().markdown();
        if (!StringUtils.hasText(markdown)) return;
        workflows.insertIfAbsent(new Workflow(doc.id(), memberId, Math.max(1, doc.rev() == null ? 1 : doc.rev()),
                doc.title(), digest(markdown), Boolean.TRUE.equals(request.autoTitle()) ? PENDING : SKIPPED, null,
                Boolean.TRUE.equals(request.autoImage()) ? PENDING : SKIPPED, null,
                Boolean.TRUE.equals(request.autoPolish()) ? PENDING : SKIPPED, null, null, null, null));
    }

    /** 返回当前成员的真实阶段状态，不携带正文或其他成员数据。 */
    public List<Status> list(String memberId) {
        return workflows.listOwned(memberId).stream().map(this::status).toList();
    }

    /** 失败后以当前已保存正文重新尝试失败阶段；完成阶段不重复消耗额度。 */
    @Transactional
    public Status retry(String memberId, String docId) {
        Workflow workflow = workflows.lock(docId);
        if (workflow == null || !workflow.memberId().equals(memberId)) throw new NotFoundException("小记自动整理任务不存在");
        DocDto doc = ownedNote(workflow);
        if (doc == null || doc.content() == null) throw new NotFoundException("小记已不可用");
        String title = FAILED.equals(workflow.titleStatus()) ? PENDING : workflow.titleStatus();
        String image = FAILED.equals(workflow.imageStatus()) ? PENDING : workflow.imageStatus();
        String polish = FAILED.equals(workflow.polishStatus()) ? PENDING : workflow.polishStatus();
        Workflow reset = new Workflow(docId, memberId, Math.max(1, doc.rev() == null ? 1 : doc.rev()), doc.title(),
                digest(doc.content().markdown()), title, FAILED.equals(workflow.titleStatus()) ? null : workflow.titleJobId(),
                image, FAILED.equals(workflow.imageStatus()) ? null : workflow.imageRunId(),
                polish, FAILED.equals(workflow.polishStatus()) ? null : workflow.polishJobId(),
                null, workflow.createdAt(), workflow.updatedAt());
        workflows.update(reset);
        return status(reset);
    }

    /** 每次只推进一个阶段；行锁使两个应用节点不会重复入队。 */
    @Transactional
    public void advance(String docId) {
        Workflow workflow = workflows.lock(docId);
        if (workflow == null) return;
        DocDto doc = ownedNote(workflow);
        if (doc == null || doc.content() == null) {
            workflows.update(failRemaining(workflow, "小记已删除或不属于原作者"));
            return;
        }
        String markdown = doc.content().markdown();
        if (doc.rev() == null || !digest(markdown).equals(workflow.sourceMarkdownHash())
                || !Objects.equals(doc.title(), workflow.originalTitle())) {
            workflows.update(failRemaining(workflow, "小记已由成员修改；旧 AI 结果不会覆盖新版本，可手动重试"));
            return;
        }
        // 标签等元信息可独立保存；正文与标题没有变化时沿用当前版本继续 CAS。
        if (doc.rev() != workflow.baseRev()) workflow = workflow.withRev(doc.rev());
        if (active(workflow.titleStatus())) { workflows.update(advanceTitle(workflow, doc)); return; }
        // 先把独立的文字润色任务入队，图片占用成员槽位时也能并行完成文本生成。
        // 润色结果待图片阶段结束后再保存，以最终 Markdown 作为阅读版本来源。
        if (active(workflow.polishStatus()) && workflow.polishJobId() == null) {
            workflows.update(advancePolish(workflow, doc));
            return;
        }
        if (active(workflow.imageStatus())) { workflows.update(advanceImage(workflow, doc)); return; }
        if (active(workflow.polishStatus())) workflows.update(advancePolish(workflow, doc));
    }

    /** 阶段调用回滚后单独保存失败状态，下一阶段仍可继续。 */
    @Transactional
    public void failCurrent(String docId, String message) {
        Workflow w = workflows.lock(docId);
        if (w == null) return;
        String error = limit(message == null ? "自动整理失败" : message, 500);
        if (active(w.titleStatus())) workflows.update(w.withTitle(FAILED, w.titleJobId(), w.baseRev(), w.originalTitle(), error));
        else if (active(w.polishStatus()) && w.polishJobId() == null)
            workflows.update(w.withPolish(FAILED, null, error));
        else if (active(w.imageStatus())) workflows.update(w.withImage(FAILED, w.imageRunId(), w.baseRev(), w.sourceMarkdownHash(), error));
        else if (active(w.polishStatus())) workflows.update(w.withPolish(FAILED, w.polishJobId(), error));
    }

    private Workflow advanceTitle(Workflow workflow, DocDto doc) {
        if (!Objects.equals(doc.title(), workflow.originalTitle())) {
            return workflow.withTitle(SKIPPED, workflow.titleJobId(), workflow.baseRev(), doc.title(), null);
        }
        String markdown = doc.content().markdown();
        if (markdown.strip().length() < 12) return workflow.withTitle(SKIPPED, null, workflow.baseRev(), doc.title(), null);
        String jobId = workflow.titleJobId();
        if (jobId == null) {
            var submitted = textJobs.submit(workflow.memberId(), requestId(),
                    "NOTE_TITLE", limit(markdown, 12_000), model(workflow.memberId()),
                    preferences.find(workflow.memberId(), "note-title-prompt-v1"), null);
            return workflow.withTitle(RUNNING, submitted.id(), workflow.baseRev(), doc.title(), null);
        }
        var result = textJobs.get(workflow.memberId(), jobId);
        if ("FAILED".equals(result.status())) return workflow.withTitle(FAILED, jobId, workflow.baseRev(), doc.title(), result.message());
        if (!"SUCCEEDED".equals(result.status())) return workflow;
        String title = result.polished();
        if (!StringUtils.hasText(title)) return workflow.withTitle(FAILED, jobId, workflow.baseRev(), doc.title(), "AI 未返回可用标题");
        DocDto saved = knowledge.updateDoc(doc.id(), new PatchDocRequest(title, null, null, null, null, workflow.baseRev()));
        return workflow.withTitle(DONE, jobId, saved.rev(), title, null);
    }

    private Workflow advanceImage(Workflow workflow, DocDto doc) {
        String markdown = doc.content().markdown();
        if (markdown.startsWith("![文章总览图](") || markdown.strip().length() < 80) {
            return workflow.withImage(SKIPPED, workflow.imageRunId(), workflow.baseRev(), workflow.sourceMarkdownHash(), null);
        }
        try {
            String runId = workflow.imageRunId();
            if (runId == null) {
                var started = imageJobs.start(workflow.memberId(), new ArticleImageRequest(
                        limit(markdown, 60_000), imagePrompt(workflow.memberId()), model(workflow.memberId()),
                        requestId()));
                return workflow.withImage(RUNNING, started.id(), workflow.baseRev(), workflow.sourceMarkdownHash(), null);
            }
            var result = imageJobs.get(workflow.memberId(), runId);
            if ("FAILED".equals(result.status())) return workflow.withImage(FAILED, runId, workflow.baseRev(), workflow.sourceMarkdownHash(), result.message());
            if (!"SUCCEEDED".equals(result.status())) return workflow;
            if (!StringUtils.hasText(result.imageUrl())) return workflow.withImage(FAILED, runId, workflow.baseRev(), workflow.sourceMarkdownHash(), "配图任务未返回图片");
            RichDocContentDto content = withCover(doc.content(), result.imageUrl());
            DocDto saved = knowledge.updateDoc(doc.id(), new PatchDocRequest(null, null, content, null, null, workflow.baseRev()));
            return workflow.withImage(DONE, runId, saved.rev(), digest(content.markdown()), null);
        } catch (RuntimeException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("已有一张总览图正在生成")) return workflow;
            return workflow.withImage(FAILED, workflow.imageRunId(), workflow.baseRev(), workflow.sourceMarkdownHash(), shortError(ex));
        }
    }

    private Workflow advancePolish(Workflow workflow, DocDto doc) {
        String markdown = doc.content().markdown();
        String text = markdown.replaceFirst("^!\\[文章总览图]\\([^\\n]+\\)\\s*", "").strip();
        if (text.length() < 40 || text.length() > 12_000) return workflow.withPolish(SKIPPED, workflow.polishJobId(), null);
        String jobId = workflow.polishJobId();
        if (jobId == null) {
            var submitted = textJobs.submit(workflow.memberId(), requestId(),
                    "NOTE_BODY", text, model(workflow.memberId()), null,
                    preferences.find(workflow.memberId(), "note-polish-prompt-v1"));
            return workflow.withPolish(RUNNING, submitted.id(), null);
        }
        var result = textJobs.get(workflow.memberId(), jobId);
        if ("FAILED".equals(result.status())) return workflow.withPolish(FAILED, jobId, result.message());
        if (!"SUCCEEDED".equals(result.status())) return workflow;
        if (!StringUtils.hasText(result.polished())) return workflow.withPolish(FAILED, jobId, "AI 未返回可用润色版本");
        polishVersions.save(workflow.memberId(), workflow.docId(),
                new SaveNotePolishVersionRequest(markdown, result.polished()));
        return workflow.withPolish(DONE, jobId, null);
    }

    private RichDocContentDto withCover(RichDocContentDto original, String url) {
        JsonNode document = original.document() == null ? null : original.document().deepCopy();
        if (document instanceof ObjectNode root && root.path("type").asText().equals("doc")) {
            ArrayNode children = root.withArray("content");
            ObjectNode image = json.createObjectNode(); image.put("type", "image");
            ObjectNode attrs = image.putObject("attrs"); attrs.put("src", url); attrs.put("alt", "文章总览图");
            attrs.put("width", "50%"); attrs.putNull("height"); attrs.put("align", "center");
            children.insert(0, image);
        }
        return new RichDocContentDto(1, document,
                "![文章总览图](" + url + " \"wao-image-size:50%xauto\")\n\n" + original.markdown());
    }

    private DocDto ownedNote(Workflow workflow) {
        DocEntity entity = docs.selectById(workflow.docId());
        if (entity == null || entity.getDeletedAt() != null || entity.getArchivedAt() != null
                || !KnowledgeService.NOTES_KB_ID.equals(entity.getKbId())
                || !workflow.memberId().equals(entity.getAuthorId())) return null;
        return knowledge.getDoc(workflow.docId());
    }

    private Workflow failRemaining(Workflow w, String error) {
        return new Workflow(w.docId(), w.memberId(), w.baseRev(), w.originalTitle(), w.sourceMarkdownHash(),
                active(w.titleStatus()) ? FAILED : w.titleStatus(), w.titleJobId(),
                active(w.imageStatus()) ? FAILED : w.imageStatus(), w.imageRunId(),
                active(w.polishStatus()) ? FAILED : w.polishStatus(), w.polishJobId(),
                error, w.createdAt(), w.updatedAt());
    }

    private String model(String memberId) { return preferences.find(memberId, "note-ai-model-v1"); }
    private String imagePrompt(String memberId) {
        String value = preferences.find(memberId, "article-overview-image-prompt-v1");
        return StringUtils.hasText(value) ? value : DEFAULT_IMAGE_PROMPT;
    }
    private static boolean active(String status) { return PENDING.equals(status) || RUNNING.equals(status); }
    private static String limit(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
    private static String shortError(RuntimeException ex) { return limit(ex.getMessage() == null ? "自动整理失败" : ex.getMessage(), 500); }
    private static String requestId() { return UUID.randomUUID().toString().replace("-", ""); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 不可用", ex); }
    }
    private Status status(Workflow w) {
        return new Status(w.docId(), w.titleStatus(), w.imageStatus(), w.polishStatus(), w.lastError());
    }
    public record Status(String docId, String title, String image, String polish, String message) { }
}
