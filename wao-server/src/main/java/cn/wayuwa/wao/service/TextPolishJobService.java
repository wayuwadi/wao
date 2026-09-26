package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.dto.TextPolishRecordDto;
import cn.wayuwa.wao.mapper.TextPolishJobMapper;
import cn.wayuwa.wao.mapper.TextPolishJobMapper.Job;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 文档和工作台共用的持久润色编排，不持有 HTTP 请求等待模型。 */
@Service
public class TextPolishJobService {
    private final TextPolishJobMapper jobs;
    private final TextPolishService polish;
    private final AiAssistantService assistant;

    public TextPolishJobService(TextPolishJobMapper jobs, TextPolishService polish, AiAssistantService assistant) {
        this.jobs = jobs; this.polish = polish; this.assistant = assistant;
    }

    /** 同一请求重复提交只返回原任务；任务与 AI 队列在一个事务中落库。 */
    @Transactional
    public Result submit(String memberId, String requestId, String kind, String originalText) {
        return submit(memberId, requestId, kind, originalText, null, null, null);
    }

    /** 提交小记专用模型与标题方法；其余润色工作流仍沿用既有路由。 */
    @Transactional
    public Result submit(String memberId, String requestId, String kind, String originalText,
            String requestedModel, String titlePrompt, String notePolishPrompt) {
        if (requestId == null || !requestId.matches("[a-f0-9]{32}")) throw new BadRequestException("润色请求标识无效");
        if (!"DOCUMENT".equals(kind) && !"WORKSPACE".equals(kind)
                && !"NOTE_TITLE".equals(kind) && !"NOTE_BODY".equals(kind))
            throw new BadRequestException("任务类型无效");
        if (jobs.lockMember(memberId) == null) throw new NotFoundException("成员不存在");
        String id = "pj" + requestId;
        Job existing = jobs.findOwned(id, memberId);
        String original = originalText == null ? "" : originalText.trim();
        String storedOriginal = "NOTE_TITLE".equals(kind) ? noteTitleDigest(original) : original;
        if (existing != null) {
            if (!kind.equals(existing.kind()) || !storedOriginal.equals(existing.originalText())) {
                throw new BadRequestException("请求内容已改变，请创建新的润色任务");
            }
            return result(existing);
        }
        var prepared = "NOTE_TITLE".equals(kind) ? null : polish.prepare(memberId, original);
        if ("DOCUMENT".equals(kind) && original.length() > 4000) throw new BadRequestException("一次最多润色 4000 字");
        String method = switch (kind) {
            case "DOCUMENT" -> "改进表达的清晰度、流畅度与自然度，保留 Markdown 块结构，不增加标题或解释。";
            case "NOTE_BODY" -> (notePolishPrompt == null || notePolishPrompt.isBlank()
                    ? prepared.promptSnapshot() : notePolishPrompt.trim()) + "\n小记额外要求：删除口语化赘词和重复内容，"
                    + "保留事实、数字、原意和 Markdown 链接与图片；不要添加原文没有的信息，只返回润色后的正文。";
            case "NOTE_TITLE" -> titlePrompt == null || titlePrompt.isBlank()
                    ? "从小记正文提炼一个准确、简短的中文标题" : titlePrompt.trim();
            default -> prepared.promptSnapshot();
        };
        String runId = switch (kind) {
            case "NOTE_TITLE" -> assistant.enqueueNoteTitleJob(id, original, method, requestedModel);
            case "NOTE_BODY" -> assistant.enqueueNotePolishJob(id, original, method, requestedModel);
            default -> assistant.enqueueTextPolishJob(id, original, method);
        };
        Job job = new Job(id, memberId, kind, storedOriginal, method, runId, null);
        jobs.insert(job);
        return result(job);
    }

    /** 只读取当前成员的任务；成功结果幂等进入已有润色历史，刷新不会重复生成。 */
    @Transactional
    public Result get(String memberId, String jobId) {
        Job job = jobs.findOwned(jobId, memberId);
        if (job == null) throw new NotFoundException("润色任务不存在");
        return result(job);
    }

    private Result result(Job job) {
        var run = assistant.readOnlyJobResult(job.id(), job.aiRunId());
        String status = run.active() ? (run.status().endsWith("running") ? "RUNNING" : "QUEUED")
                : "succeeded".equals(run.status()) ? "SUCCEEDED" : "FAILED";
        String message = switch (status) {
            case "QUEUED" -> assistant.polishQueueMessage();
            case "RUNNING" -> "AI 正在润色；结果会保留，无需重复提交";
            case "SUCCEEDED" -> "NOTE_TITLE".equals(job.kind()) ? "标题已生成" : "润色已完成";
            default -> run.error() == null ? "润色失败，请重试；原文已保留" : run.error();
        };
        TextPolishRecordDto record = "SUCCEEDED".equals(status) && "WORKSPACE".equals(job.kind())
                ? polish.recordResult(job.aiRunId(), job.memberId(), job.promptSnapshot(), job.originalText(), run.response()) : null;
        String output = "SUCCEEDED".equals(status) ? run.response() : null;
        if (output != null && "NOTE_TITLE".equals(job.kind())) {
            output = output.lines().findFirst().orElse("").replaceAll("^[#\\s\\\"“”]+|[#\\s\\\"“”]+$", "").trim();
            output = output.replaceFirst("^标题[:：]\\s*", "");
            if (output.length() > 30) output = output.substring(0, 30);
            if (output.isBlank()) output = null;
        }
        return new Result(job.id(), status, message, output, record, "RUNNING".equals(status) ? run.response() : null);
    }

    public record Result(String id, String status, String message, String polished, TextPolishRecordDto record, String preview) { }

    private static String noteTitleDigest(String original) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(original.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
