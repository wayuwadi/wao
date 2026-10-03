package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
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
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private NoteAiManagedService managed;
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
        if (managed != null) return;
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
        var owned=workflows.listOwned(memberId).stream().map(w -> managed != null && managed.managed(w.docId()) ? managed.status(w.docId(), memberId) : status(w)).filter(Objects::nonNull);
        return managed==null?owned.toList():java.util.stream.Stream.concat(owned,managed.sharedStatuses(memberId).stream()).toList();
    }

    /** 本人保存新正文后自动废弃旧阶段任务，按既有选择整理最新内容。 */
    @Transactional
    public void refreshAfterSave(String memberId, String docId) {
        if (managed != null && managed.managed(docId)) return;
        Workflow workflow = workflows.lock(docId);
        if (workflow == null || !Objects.equals(workflow.memberId(), memberId)) return;
        DocDto doc = ownedNote(workflow);
        if (doc == null || doc.content() == null || doc.rev() == null) return;
        if (!digest(doc.content().markdown()).equals(workflow.sourceMarkdownHash())
                || !Objects.equals(doc.title(), workflow.originalTitle())) {
            workflows.update(forLatestContent(workflow, doc));
        }
    }

    private Workflow forLatestContent(Workflow w, DocDto doc) {
        boolean bodyChanged = !digest(doc.content().markdown()).equals(w.sourceMarkdownHash());
        boolean titleChanged = !Objects.equals(doc.title(), w.originalTitle());
        String title = titleChanged && (active(w.titleStatus()) || FAILED.equals(w.titleStatus())) ? SKIPPED
                : bodyChanged && (active(w.titleStatus()) || FAILED.equals(w.titleStatus())) ? PENDING : w.titleStatus();
        String image = bodyChanged && (active(w.imageStatus()) || FAILED.equals(w.imageStatus())) ? PENDING : w.imageStatus();
        String polish = bodyChanged && !SKIPPED.equals(w.polishStatus()) ? PENDING : w.polishStatus();
        return new Workflow(w.docId(), w.memberId(), doc.rev(), doc.title(), digest(doc.content().markdown()),
                title, titleChanged || bodyChanged && PENDING.equals(title) ? null : w.titleJobId(),
                image, bodyChanged && PENDING.equals(image) ? null : w.imageRunId(),
                polish, bodyChanged && PENDING.equals(polish) ? null : w.polishJobId(),
                w.errorAfter(title, image, polish, null), w.createdAt(), w.updatedAt());
    }

    /** 失败后以当前已保存正文重新尝试失败阶段；完成阶段不重复消耗额度。 */
    @Transactional
    public Status retry(String memberId, String docId) {
        if (managed != null) return managed.retry(memberId, docId);
        Workflow workflow = workflows.lock(docId);
        if (workflow == null || !workflow.memberId().equals(memberId)) throw new NotFoundException("小记自动整理任务不存在");
        DocDto doc = ownedNote(workflow);
        if (doc == null || doc.content() == null || doc.rev() == null) throw new NotFoundException("小记已不可用");
        if (!digest(doc.content().markdown()).equals(workflow.sourceMarkdownHash())
                || !Objects.equals(doc.title(), workflow.originalTitle())) {
            workflow = forLatestContent(workflow, doc);
        }
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

    /** 推进符合成员并发偏好的阶段；工作流行锁防止重复入队和条件写回冲突。 */
    @Transactional
    public void advance(String docId) {
        if (managed != null) { managed.advance(docId); return; }
        Workflow workflow = workflows.lock(docId);
        if (workflow == null) return;
        Workflow original = workflow;
        DocDto doc = ownedNote(workflow);
        if (doc == null || doc.content() == null || doc.rev() == null) {
            workflows.update(failRemaining(workflow, "小记已删除或不属于原作者"));
            return;
        }
        String markdown = doc.content().markdown();
        if (!digest(markdown).equals(workflow.sourceMarkdownHash())
                || !Objects.equals(doc.title(), workflow.originalTitle())) {
            workflows.update(forLatestContent(workflow, doc));
            return;
        }
        // 标签等元信息可独立保存；正文与标题没有变化时沿用当前版本继续 CAS。
        if (doc.rev() != workflow.baseRev()) workflow = workflow.withRev(doc.rev());
        int capacity = executionCapacity(workflow.memberId());
        // 串行与并行均共享成员槽位；切换为串行后仍续查先前已入队的阶段。
        // 按标题、配图、润色的顺序填充空闲槽位。
        // 三个阶段可同时运行；润色版本会以图片写回后的最终 Markdown 保存。
        for (String stage : List.of("title", "image", "polish")) {
            String status = stageStatus(workflow, stage);
            if (!active(status)) continue;
            boolean alreadyQueued = RUNNING.equals(status) && stageJobId(workflow, stage) != null;
            if (!alreadyQueued && workflows.countRunningStages(workflow.memberId()) >= capacity) continue;
            workflow = advanceStage(workflow, stage, original);
        }
    }

    private Workflow advanceStage(Workflow workflow, String stage, Workflow original) {
        try {
            return performStage(workflow, stage);
        } catch (RuntimeException error) {
            // 阶段异常必须越过事务代理，入队/正文写回全部回滚后才记录失败。
            throw new StageFailure(original, stage, error);
        }
    }

    private Workflow performStage(Workflow workflow, String stage) {
        DocDto current = ownedNote(workflow);
        if (current == null || current.content() == null) {
            Workflow failed = failRemaining(workflow, "小记已删除或不属于原作者");
            workflows.update(failed);
            return failed;
        }
        Workflow advanced = switch (stage) {
            case "title" -> advanceTitle(workflow, current);
            case "image" -> advanceImage(workflow, current);
            case "polish" -> advancePolish(workflow, current);
            default -> workflow;
        };
        workflows.update(advanced);
        return advanced;
    }

    private int executionCapacity(String memberId) {
        String saved = preferences.find(memberId, "note-ai-defaults-v1");
        if (!StringUtils.hasText(saved)) return 3;
        try {
            JsonNode settings = json.readTree(saved);
            if ("serial".equals(settings.path("executionMode").asText())) return 1;
            int configured = settings.path("maxConcurrent").asInt(3);
            return configured >= 1 && configured <= 5 ? configured : 3;
        } catch (Exception ignored) {
            return 3;
        }
    }

    private static String stageStatus(Workflow workflow, String stage) {
        return switch (stage) {
            case "title" -> workflow.titleStatus();
            case "image" -> workflow.imageStatus();
            case "polish" -> workflow.polishStatus();
            default -> SKIPPED;
        };
    }

    private static String stageJobId(Workflow workflow, String stage) {
        return switch (stage) {
            case "title" -> workflow.titleJobId();
            case "image" -> workflow.imageRunId();
            case "polish" -> workflow.polishJobId();
            default -> null;
        };
    }

    /** 阶段事务回滚后只标记实际失败阶段；并发重试、版本冲突及数据库恢复不被迟到错误覆盖。 */
    @Transactional
    public void failAdvance(String docId, RuntimeException error) {
        if (managed != null && managed.managed(docId)) { managed.failed(docId,error); return; }
        if (!(error instanceof StageFailure failure) || !docId.equals(failure.original.docId())) return;
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.dao.DataAccessException || cause instanceof ConflictException) return;
        }
        Workflow w = workflows.lock(docId);
        if (!failure.original.equals(w)) return;
        String label = switch (failure.stage) { case "title" -> "标题"; case "image" -> "配图"; default -> "润色"; };
        String reason = failure.getCause() instanceof BadRequestException || failure.getCause() instanceof NotFoundException
                ? failure.getCause().getMessage() : "请重试；若仍失败请检查本机 Codex 与服务日志";
        String message = limit(label + "整理失败；原文已保留。" + (reason == null ? "请重试" : reason), 500);
        Workflow failed = switch (failure.stage) {
            case "title" -> w.withTitle(FAILED, w.titleJobId(), w.baseRev(), w.originalTitle(), message);
            case "image" -> w.withImage(FAILED, w.imageRunId(), w.baseRev(), w.sourceMarkdownHash(), message);
            default -> w.withPolish(FAILED, w.polishJobId(), message);
        };
        workflows.update(failed);
    }

    private static final class StageFailure extends RuntimeException {
        private final Workflow original;
        private final String stage;
        StageFailure(Workflow original, String stage, RuntimeException cause) {
            super("小记阶段推进失败：" + stage, cause);
            this.original = original;
            this.stage = stage;
        }
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
        DocDto saved = knowledge.updateDerivedNote(doc.id(), new PatchDocRequest(title, null, null, null, null, workflow.baseRev()));
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
                var started = imageJobs.startNoteWorkflow(workflow.memberId(), new ArticleImageRequest(
                        limit(markdown, 60_000), imagePrompt(workflow.memberId()), model(workflow.memberId()),
                        requestId()));
                return workflow.withImage(RUNNING, started.id(), workflow.baseRev(), workflow.sourceMarkdownHash(), null);
            }
            var result = imageJobs.get(workflow.memberId(), runId);
            if ("FAILED".equals(result.status())) return workflow.withImage(FAILED, runId, workflow.baseRev(), workflow.sourceMarkdownHash(), result.message());
            if (!"SUCCEEDED".equals(result.status())) return workflow;
            if (!StringUtils.hasText(result.imageUrl())) return workflow.withImage(FAILED, runId, workflow.baseRev(), workflow.sourceMarkdownHash(), "配图任务未返回图片");
            RichDocContentDto content = withCover(doc.content(), result.imageUrl());
            DocDto saved = knowledge.updateDerivedNote(doc.id(), new PatchDocRequest(null, null, content, null, null, workflow.baseRev()));
            return workflow.withImage(DONE, runId, saved.rev(), digest(content.markdown()), null);
        } catch (RuntimeException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("已有一张总览图正在生成")) return workflow;
            throw ex;
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
        String cover = markdown.startsWith("![文章总览图](")
                ? markdown.substring(0, markdown.indexOf('\n') + 1) + "\n" : "";
        String polished = cover + result.polished().replaceFirst("^!\\[文章总览图]\\([^\\n]+\\)\\s*", "").strip();
        polishVersions.save(workflow.memberId(), workflow.docId(),
                new SaveNotePolishVersionRequest(markdown, polished,jobId));
        DocDto saved = knowledge.updateDerivedNote(doc.id(), new PatchDocRequest(null, null,
                new RichDocContentDto(1, null, polished), null, null, workflow.baseRev()));
        return workflow.withPolish(DONE, jobId, null).withContent(saved.rev(), digest(polished));
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
    private static String requestId() { return UUID.randomUUID().toString().replace("-", ""); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 不可用", ex); }
    }
    private Status status(Workflow w) {
        return new Status(w.docId(), w.titleStatus(), w.imageStatus(), w.polishStatus(), w.lastError());
    }
    public record StageDetail(String reasonCode, String message, boolean retryable, String action, String jobId, int sourceVersion) { }
    public record Status(String docId, String title, String image, String polish, String message, java.util.Map<String,StageDetail> stages) {
        public Status(String docId,String title,String image,String polish,String message) { this(docId,title,image,polish,message,java.util.Map.of()); }
    }
}
