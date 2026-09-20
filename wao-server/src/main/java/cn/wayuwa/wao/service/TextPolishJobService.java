package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.dto.TextPolishRecordDto;
import cn.wayuwa.wao.mapper.TextPolishJobMapper;
import cn.wayuwa.wao.mapper.TextPolishJobMapper.Job;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        if (requestId == null || !requestId.matches("[a-f0-9]{32}")) throw new BadRequestException("润色请求标识无效");
        if (!"DOCUMENT".equals(kind) && !"WORKSPACE".equals(kind)) throw new BadRequestException("润色类型无效");
        if (jobs.lockMember(memberId) == null) throw new NotFoundException("成员不存在");
        String id = "pj" + requestId;
        Job existing = jobs.findOwned(id, memberId);
        String original = originalText == null ? "" : originalText.trim();
        if (existing != null) {
            if (!kind.equals(existing.kind()) || !original.equals(existing.originalText())) {
                throw new BadRequestException("请求内容已改变，请创建新的润色任务");
            }
            return result(existing);
        }
        var prepared = polish.prepare(memberId, original);
        if ("DOCUMENT".equals(kind) && original.length() > 4000) throw new BadRequestException("一次最多润色 4000 字");
        String method = "DOCUMENT".equals(kind)
                ? "改进表达的清晰度、流畅度与自然度，保留 Markdown 块结构，不增加标题或解释。"
                : prepared.promptSnapshot();
        String runId = assistant.enqueueTextPolishJob(id, original, method);
        Job job = new Job(id, memberId, kind, original, method, runId, null);
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
            case "SUCCEEDED" -> "润色已完成";
            default -> run.error() == null ? "润色失败，请重试；原文已保留" : run.error();
        };
        TextPolishRecordDto record = "SUCCEEDED".equals(status) && "WORKSPACE".equals(job.kind())
                ? polish.recordResult(job.aiRunId(), job.memberId(), job.promptSnapshot(), job.originalText(), run.response()) : null;
        return new Result(job.id(), status, message, "SUCCEEDED".equals(status) ? run.response() : null, record);
    }

    public record Result(String id, String status, String message, String polished, TextPolishRecordDto record) { }
}
