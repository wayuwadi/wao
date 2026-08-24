package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.IdeaArtifactEntity;
import cn.wayuwa.wao.domain.IdeaArtifactKind;
import cn.wayuwa.wao.domain.IdeaEntity;
import cn.wayuwa.wao.domain.IdeaIterationEntity;
import cn.wayuwa.wao.domain.IdeaIterationStatus;
import cn.wayuwa.wao.domain.IdeaPriority;
import cn.wayuwa.wao.domain.IdeaResearchRunEntity;
import cn.wayuwa.wao.domain.IdeaStage;
import cn.wayuwa.wao.domain.IdeaTaskLinkEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.CreateTaskRequest;
import cn.wayuwa.wao.dto.CreateProjectRequest;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.IdeaDto;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.dto.ProjectDto;
import cn.wayuwa.wao.mapper.AiTaskRunMapper;
import cn.wayuwa.wao.mapper.IdeaArtifactMapper;
import cn.wayuwa.wao.mapper.IdeaIterationMapper;
import cn.wayuwa.wao.mapper.IdeaMapper;
import cn.wayuwa.wao.mapper.IdeaResearchRunMapper;
import cn.wayuwa.wao.mapper.IdeaTaskLinkMapper;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.mapper.ProjectMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 团队点子从收集、调研、MVP 到验证/放弃的完整生命周期。 */
@Service
public class IdeaService {
    public static final List<String> STAGES = java.util.Arrays.stream(IdeaStage.values()).map(Enum::name).toList();
    private static final Set<String> PRIORITIES = java.util.Arrays.stream(IdeaPriority.values()).map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> ARTIFACT_KINDS = java.util.Arrays.stream(IdeaArtifactKind.values()).map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> ITERATION_STATES = java.util.Arrays.stream(IdeaIterationStatus.values()).map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> ACTIVE_RUNS = Set.of("queued", "claimed", "running");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final IdeaMapper ideas;
    private final IdeaArtifactMapper artifacts;
    private final IdeaTaskLinkMapper links;
    private final IdeaIterationMapper iterations;
    private final IdeaResearchRunMapper researchRuns;
    private final TaskMapper taskMapper;
    private final MemberMapper memberMapper;
    private final ProjectMapper projectMapper;
    private final AiTaskRunMapper aiRuns;
    private final TaskService tasks;
    private final KnowledgeService knowledge;
    private final ProjectService projectService;
    private final ObjectMapper json;

    public IdeaService(IdeaMapper ideas, IdeaArtifactMapper artifacts, IdeaTaskLinkMapper links,
            IdeaIterationMapper iterations, IdeaResearchRunMapper researchRuns, TaskMapper taskMapper,
            MemberMapper memberMapper, ProjectMapper projectMapper, AiTaskRunMapper aiRuns,
            TaskService tasks, KnowledgeService knowledge, ProjectService projectService, ObjectMapper json) {
        this.ideas = ideas; this.artifacts = artifacts; this.links = links; this.iterations = iterations;
        this.researchRuns = researchRuns; this.taskMapper = taskMapper; this.memberMapper = memberMapper;
        this.projectMapper = projectMapper; this.aiRuns = aiRuns;
        this.tasks = tasks; this.knowledge = knowledge; this.projectService = projectService; this.json = json;
    }

    /** 列出团队共享点子；查询参数由前端本地组合筛选，避免首版引入分页分歧。 */
    public List<IdeaDto> list() {
        return ideas.selectList(new LambdaQueryWrapper<IdeaEntity>().orderByDesc(IdeaEntity::getUpdatedAt))
                .stream().map(this::toDto).toList();
    }

    /** 读取一条完整点子档案。 */
    public IdeaDto get(String id) { return toDto(require(id)); }

    /** 创建人即默认负责人；只要求标题，收集箱不设置质量门槛。 */
    @Transactional
    public IdeaDto create(IdeaDto.CreateRequest req, String memberId) {
        String actor = actor(memberId);
        IdeaEntity e = new IdeaEntity();
        e.setId(id("i")); e.setTitle(req.title().trim()); e.setRawIdea(nz(req.rawIdea()));
        e.setProblemText(""); e.setTargetOutcome(""); e.setPriority(priority(req.priority()));
        e.setStage("INBOX"); e.setCreatedBy(actor); e.setOwnerId(actor);
        LocalDateTime now = now(); e.setCreatedAt(now); e.setUpdatedAt(now);
        e.setDetailJson(write(emptyDetail(List.of(activity("创建了点子", actor)))));
        ideas.insert(e);
        return toDto(e);
    }

    /** 局部编辑点子；阶段只能走 transition，防止绕过阶段门禁。 */
    @Transactional
    public IdeaDto patch(String id, IdeaDto.PatchRequest req, String memberId) {
        IdeaEntity e = require(id); String actor = actor(memberId); IdeaDto.Detail old = detail(e);
        if (StringUtils.hasText(req.title())) e.setTitle(req.title().trim());
        if (req.rawIdea() != null) e.setRawIdea(req.rawIdea());
        if (req.problem() != null) e.setProblemText(req.problem());
        if (req.targetOutcome() != null) e.setTargetOutcome(req.targetOutcome());
        if (req.priority() != null) e.setPriority(priority(req.priority()));
        if (StringUtils.hasText(req.ownerId())) {
            String owner = req.ownerId().trim();
            if (memberMapper.selectById(owner) == null) throw new BadRequestException("负责人不存在");
            e.setOwnerId(owner);
        }
        if (req.projectId() != null) {
            String projectId = blank(req.projectId());
            if (projectId != null && projectMapper.selectById(projectId) == null) throw new BadRequestException("关联项目不存在");
            e.setProjectId(projectId);
        }
        IdeaDto.Detail merged = req.detail() == null ? old : merge(old, req.detail());
        e.setDetailJson(write(withActivity(merged, activity("更新了点子档案", actor))));
        e.setUpdatedAt(now()); ideas.updateById(e); return toDto(e);
    }

    /** 按方法论推进阶段，并在达成/放弃时自动沉淀原生知识文档。 */
    @Transactional
    public IdeaDto transition(String id, IdeaDto.TransitionRequest req, String memberId) {
        IdeaEntity e = require(id); String actor = actor(memberId); String target = stage(req.stage());
        if (target.equals(e.getStage())) return toDto(e);
        validateTransition(e, target, req);
        IdeaDto.Detail d = detail(e);
        if ("ALIGNING".equals(target)) {
            DocDto doc = knowledge.createIdeaDocument(e.getId(), e.getTitle(), "方案与 MVP",
                    "## 目标用户\n\n" + nz(d.targetUsers()) + "\n\n## 核心价值\n\n" + nz(d.coreValue())
                            + "\n\n## 非目标\n\n" + nz(d.nonGoals()) + "\n\n## 可行性判断\n\n" + nz(d.feasibility())
                            + "\n\n## MVP 假设\n\n" + nz(d.mvpHypothesis()) + "\n\n## 验收指标\n\n" + nz(d.acceptanceCriteria()), actor);
            addArtifact(e, "SOLUTION_MVP", doc.id(), doc.title(), actor);
        }
        if ("MVP_BUILDING".equals(target)) {
            String participants = d.meetingParticipants() == null ? "" : String.join("、", d.meetingParticipants());
            DocDto doc = knowledge.createIdeaDocument(e.getId(), e.getTitle(), "会议记录",
                    "## 会议信息\n\n- 时间：" + nz(d.meetingDate()) + "\n- 时长："
                            + (d.meetingDuration() == null ? "未记录" : d.meetingDuration() + " 分钟")
                            + "\n- 参与人：" + participants + "\n\n## 对齐结论\n\n" + nz(d.meetingConclusion())
                            + "\n\n## 补充意见\n\n" + nz(d.meetingNotes()), actor);
            addArtifact(e, "MEETING_NOTE", doc.id(), doc.title(), actor);
        }
        if ("ACHIEVED".equals(target)) {
            d = copy(d, req.validationConclusion(), req.validationEvidence(), null, null, null, null);
            DocDto doc = knowledge.createIdeaDocument(e.getId(), e.getTitle(), "验证记录",
                    "## 验证结论\n\n" + req.validationConclusion() + "\n\n## 验证证据\n\n" + req.validationEvidence(), actor);
            addArtifact(e, "VALIDATION_RECORD", doc.id(), doc.title(), actor);
        }
        if ("ABANDONED".equals(target)) {
            d = copy(d, null, null, null, req.abandonmentReason(), null, null);
            DocDto doc = knowledge.createIdeaDocument(e.getId(), e.getTitle(), "放弃报告",
                    "## 放弃原因\n\n" + req.abandonmentReason(), actor);
            addArtifact(e, "ABANDONMENT_REPORT", doc.id(), doc.title(), actor);
        }
        e.setStage(target); e.setDetailJson(write(withActivity(d, activity("将阶段推进到「" + target + "」", actor))));
        e.setUpdatedAt(now()); ideas.updateById(e); return toDto(e);
    }

    /** 确认已阅读最新调研，显式保留“想明白由人完成”的边界。 */
    @Transactional
    public IdeaDto acceptResearch(String id, String memberId) {
        IdeaEntity e = require(id); String actor = actor(memberId);
        if (!hasArtifact(id, "RESEARCH_REPORT")) throw new BadRequestException("请先完成并保存调研报告");
        IdeaDto.Detail d = detail(e);
        d = copy(d, null, null, actor, null, now().toString(), actor);
        e.setDetailJson(write(withActivity(d, activity("确认已读调研报告", actor))));
        e.setUpdatedAt(now()); ideas.updateById(e); return toDto(e);
    }

    /** 关联一篇已存在知识文档。 */
    public List<IdeaDto.Artifact> listArtifacts(String id) { return get(id).artifacts(); }

    /** 关联一篇已存在知识文档。 */
    @Transactional
    public IdeaDto.Artifact addArtifact(String id, IdeaDto.ArtifactRequest req, String memberId) {
        IdeaEntity e = require(id); String kind = artifactKind(req.kind());
        DocDto doc = knowledge.getDoc(req.docId());
        IdeaArtifactEntity saved = addArtifact(e, kind, doc.id(),
                StringUtils.hasText(req.title()) ? req.title().trim() : doc.title(), actor(memberId));
        touch(e); return artifact(saved);
    }

    /** 新建或关联一个 MVP 任务；关联关系失败时事务整体回滚。 */
    @Transactional
    public IdeaDto.TaskLink addTask(String id, IdeaDto.TaskRequest req, String memberId) {
        IdeaEntity idea = require(id); String actor = actor(memberId); TaskDto task;
        if (StringUtils.hasText(req.taskId())) {
            task = tasks.get(req.taskId(), actor);
            if (StringUtils.hasText(idea.getProjectId()) && !idea.getProjectId().equals(task.project()))
                throw new BadRequestException("MVP 任务必须属于点子关联项目");
        } else {
            if (!StringUtils.hasText(idea.getProjectId())) throw new BadRequestException("进入 MVP 前请先关联项目");
            if (!StringUtils.hasText(req.title())) throw new BadRequestException("请填写 MVP 任务标题");
            task = tasks.create(new CreateTaskRequest(req.title(), idea.getProjectId(), priority(req.priority()),
                    StringUtils.hasText(req.ownerId()) ? req.ownerId() : actor, "", nz(req.description()), List.of()), actor);
        }
        IdeaTaskLinkEntity existing = links.selectOne(new LambdaQueryWrapper<IdeaTaskLinkEntity>()
                .eq(IdeaTaskLinkEntity::getIdeaId, id).eq(IdeaTaskLinkEntity::getTaskId, task.id()));
        if (existing != null) return taskLink(existing);
        IdeaTaskLinkEntity link = new IdeaTaskLinkEntity(); link.setId(id("itl")); link.setIdeaId(id);
        link.setTaskId(task.id()); link.setRequiredMvp(!Boolean.FALSE.equals(req.requiredMvp()));
        Long count = links.selectCount(new LambdaQueryWrapper<IdeaTaskLinkEntity>().eq(IdeaTaskLinkEntity::getIdeaId, id));
        link.setOrd(count == null ? 1 : count.intValue() + 1); link.setCreatedBy(actor); link.setCreatedAt(now());
        links.insert(link); touch(idea); return taskLink(link);
    }

    /** 只解除关系，不删除任务。 */
    @Transactional
    public void unlinkTask(String ideaId, String taskId) {
        require(ideaId);
        links.delete(new LambdaQueryWrapper<IdeaTaskLinkEntity>().eq(IdeaTaskLinkEntity::getIdeaId, ideaId)
                .eq(IdeaTaskLinkEntity::getTaskId, taskId));
    }

    /** 为一次 MVP 交付或继续优化建立可评价的迭代档案。 */
    @Transactional
    public IdeaDto.Iteration createIteration(String ideaId, IdeaDto.IterationRequest req, String memberId) {
        require(ideaId); requireLinkedTask(ideaId, req.taskId());
        IdeaIterationEntity e = new IdeaIterationEntity(); e.setId(id("ii")); e.setIdeaId(ideaId);
        e.setTaskId(req.taskId()); e.setAiRunId(blank(req.aiRunId())); e.setObjective(req.objective().trim());
        e.setAcceptanceCriteria(nz(req.acceptanceCriteria())); e.setStatus(StringUtils.hasText(req.aiRunId()) ? "RUNNING" : "PLANNED");
        e.setCreatedBy(actor(memberId)); e.setCreatedAt(now()); iterations.insert(e); return iteration(e);
    }

    /** 保存本轮人工反馈与结果快照。 */
    @Transactional
    public IdeaDto.Iteration patchIteration(String ideaId, String iterationId,
            IdeaDto.IterationPatchRequest req) {
        IdeaIterationEntity e = iterations.selectById(iterationId);
        if (e == null || !ideaId.equals(e.getIdeaId())) throw new NotFoundException("点子迭代不存在：" + iterationId);
        if (StringUtils.hasText(req.status())) {
            String status = req.status().trim(); if (!ITERATION_STATES.contains(status)) throw new BadRequestException("不支持的迭代状态");
            e.setStatus(status); if (Set.of("ACCEPTED", "REJECTED", "FAILED").contains(status)) e.setFinishedAt(now());
        }
        if (req.feedback() != null) e.setFeedback(req.feedback()); if (req.resultSummary() != null) e.setResultSummary(req.resultSummary());
        if (req.reportUrl() != null) e.setReportUrl(req.reportUrl()); iterations.updateById(e); return iteration(e);
    }

    /**
     * 把存量独立点子无损归并到项目：不搬任务、不移动文档，只补项目关联并生成历史档案。
     */
    @Transactional
    public ProjectDto convertToProject(String ideaId, IdeaDto.ConvertRequest req, String memberId) {
        IdeaEntity entity = require(ideaId);
        String actor = actor(memberId);
        IdeaDto snapshot = toDto(entity);
        ProjectDto target = conversionTarget(entity, snapshot, req == null ? null : req.projectId(), actor);

        DocDto archive = knowledge.ensureIdeaArchive(target, snapshot, actor);
        for (IdeaDto.Artifact artifact : snapshot.artifacts()) {
            if (!"LEGACY_ARCHIVE".equals(artifact.kind())) {
                knowledge.linkDocToProject(artifact.docId(), target.id());
            }
        }
        if (snapshot.artifacts().stream().noneMatch(a -> "LEGACY_ARCHIVE".equals(a.kind()))) {
            addArtifact(entity, "LEGACY_ARCHIVE", archive.id(), archive.title(), actor);
        }
        entity.setProjectId(target.id());
        entity.setDetailJson(write(withActivity(detail(entity), activity("已归并到项目「" + target.name() + "」", actor))));
        entity.setUpdatedAt(now());
        ideas.updateById(entity);
        return target;
    }

    private ProjectDto conversionTarget(IdeaEntity idea, IdeaDto snapshot, String requestedProjectId, String actor) {
        if (StringUtils.hasText(requestedProjectId)) {
            return projectService.get(requestedProjectId.trim(), actor);
        }
        if (StringUtils.hasText(idea.getProjectId())) {
            return projectService.get(idea.getProjectId(), actor);
        }
        List<TaskEntity> linkedTasks = snapshot.tasks().stream().map(IdeaDto.TaskLink::taskId)
                .map(taskMapper::selectById).filter(java.util.Objects::nonNull).toList();
        Set<String> taskProjects = linkedTasks.stream().map(TaskEntity::getProjectId)
                .filter(StringUtils::hasText).collect(java.util.stream.Collectors.toSet());
        boolean everyTaskBelongsToAProject = linkedTasks.size() == snapshot.tasks().size()
                && linkedTasks.stream().allMatch(task -> StringUtils.hasText(task.getProjectId()));
        if (everyTaskBelongsToAProject && taskProjects.size() == 1) {
            return projectService.get(taskProjects.iterator().next(), actor);
        }
        String goal = StringUtils.hasText(snapshot.targetOutcome()) ? snapshot.targetOutcome()
                : StringUtils.hasText(snapshot.rawIdea()) ? snapshot.rawIdea() : snapshot.problem();
        return projectService.create(new CreateProjectRequest(snapshot.title(), "career", goal, true, false), actor);
    }

    private void validateTransition(IdeaEntity e, String target, IdeaDto.TransitionRequest req) {
        int current = STAGES.indexOf(e.getStage()), next = STAGES.indexOf(target);
        if (!"ABANDONED".equals(target) && next != current + 1) throw new BadRequestException("点子阶段必须逐步推进");
        IdeaDto.Detail d = detail(e);
        if ("THINKING".equals(target) && (!hasArtifact(e.getId(), "RESEARCH_REPORT") || !StringUtils.hasText(d.researchAcceptedAt())))
            throw new BadRequestException("请先保存并确认已读调研报告");
        if ("ALIGNING".equals(target) && (!StringUtils.hasText(d.mvpHypothesis()) || !StringUtils.hasText(d.acceptanceCriteria())))
            throw new BadRequestException("请先写清 MVP 假设和验收指标");
        if ("MVP_BUILDING".equals(target) && (!StringUtils.hasText(e.getProjectId()) || !StringUtils.hasText(d.meetingConclusion())))
            throw new BadRequestException("请先关联项目并记录对齐会议结论");
        if ("VALIDATING".equals(target)) validateMvpReady(e.getId());
        if ("ACHIEVED".equals(target) && (!StringUtils.hasText(req.validationConclusion()) || !StringUtils.hasText(req.validationEvidence())))
            throw new BadRequestException("达成前必须填写验证结论与证据");
        if ("ABANDONED".equals(target)) {
            if ("INBOX".equals(e.getStage())) throw new BadRequestException("未启动点子继续留在收集箱即可");
            if (!StringUtils.hasText(req.abandonmentReason())) throw new BadRequestException("放弃前必须填写原因");
        }
    }

    private void validateMvpReady(String ideaId) {
        List<IdeaTaskLinkEntity> required = links.selectList(new LambdaQueryWrapper<IdeaTaskLinkEntity>()
                .eq(IdeaTaskLinkEntity::getIdeaId, ideaId).eq(IdeaTaskLinkEntity::getRequiredMvp, true));
        if (required.isEmpty()) throw new BadRequestException("请至少关联一条必做 MVP 任务");
        for (IdeaTaskLinkEntity link : required) {
            TaskEntity task = taskMapper.selectById(link.getTaskId());
            if (task == null || !Set.of("待验证", "已完成").contains(task.getStatus()))
                throw new BadRequestException("必做 MVP 任务尚未进入待验证或已完成：" + link.getTaskId());
            Long active = aiRuns.selectCount(new LambdaQueryWrapper<cn.wayuwa.wao.domain.AiTaskRunEntity>()
                    .eq(cn.wayuwa.wao.domain.AiTaskRunEntity::getTaskId, link.getTaskId())
                    .in(cn.wayuwa.wao.domain.AiTaskRunEntity::getStatus, ACTIVE_RUNS));
            if (active != null && active > 0) throw new BadRequestException("必做 MVP 任务仍有 AI 在运行");
        }
    }

    private IdeaDto toDto(IdeaEntity e) {
        List<IdeaDto.Artifact> a = artifacts.selectList(new LambdaQueryWrapper<IdeaArtifactEntity>()
                .eq(IdeaArtifactEntity::getIdeaId, e.getId()).orderByDesc(IdeaArtifactEntity::getCreatedAt)).stream().map(this::artifact).toList();
        List<IdeaDto.TaskLink> t = links.selectList(new LambdaQueryWrapper<IdeaTaskLinkEntity>()
                .eq(IdeaTaskLinkEntity::getIdeaId, e.getId()).orderByAsc(IdeaTaskLinkEntity::getOrd)).stream().map(this::taskLink).toList();
        List<IdeaDto.Iteration> it = iterations.selectList(new LambdaQueryWrapper<IdeaIterationEntity>()
                .eq(IdeaIterationEntity::getIdeaId, e.getId()).orderByDesc(IdeaIterationEntity::getCreatedAt)).stream().map(this::iteration).toList();
        List<IdeaDto.ResearchRun> rr = researchRuns.selectList(new LambdaQueryWrapper<IdeaResearchRunEntity>()
                .eq(IdeaResearchRunEntity::getIdeaId, e.getId()).orderByDesc(IdeaResearchRunEntity::getCreatedAt)).stream().map(this::researchRun).toList();
        return new IdeaDto(e.getId(), e.getTitle(), nz(e.getRawIdea()), nz(e.getProblemText()), nz(e.getTargetOutcome()),
                e.getPriority(), e.getStage(), e.getCreatedBy(), e.getOwnerId(), e.getProjectId(), detail(e), a, t, it, rr,
                text(e.getCreatedAt()), text(e.getUpdatedAt()));
    }

    private IdeaDto.TaskLink taskLink(IdeaTaskLinkEntity e) {
        TaskEntity task = taskMapper.selectById(e.getTaskId());
        return new IdeaDto.TaskLink(e.getId(), e.getTaskId(), task == null ? "任务已不存在" : task.getTitle(),
                task == null ? "" : task.getStatus(), Boolean.TRUE.equals(e.getRequiredMvp()), e.getOrd() == null ? 0 : e.getOrd(),
                e.getCreatedBy(), text(e.getCreatedAt()));
    }
    private IdeaDto.Artifact artifact(IdeaArtifactEntity e) { return new IdeaDto.Artifact(e.getId(), e.getKind(), e.getDocId(), e.getTitle(), e.getCreatedBy(), text(e.getCreatedAt())); }
    private IdeaDto.Iteration iteration(IdeaIterationEntity e) { return new IdeaDto.Iteration(e.getId(), e.getTaskId(), e.getAiRunId(), e.getObjective(), e.getAcceptanceCriteria(), e.getStatus(), e.getFeedback(), e.getResultSummary(), e.getReportUrl(), e.getCreatedBy(), text(e.getCreatedAt()), text(e.getFinishedAt())); }
    private IdeaDto.ResearchRun researchRun(IdeaResearchRunEntity e) { return new IdeaDto.ResearchRun(e.getId(), e.getStatus(), e.getErrorText(), e.getDocId(), e.getMemberId(), text(e.getCreatedAt()), text(e.getFinishedAt())); }

    private IdeaArtifactEntity addArtifact(IdeaEntity idea, String kind, String docId, String title, String actor) {
        IdeaArtifactEntity e = new IdeaArtifactEntity(); e.setId(id("ia")); e.setIdeaId(idea.getId()); e.setKind(kind);
        e.setDocId(docId); e.setTitle(title); e.setCreatedBy(actor); e.setCreatedAt(now()); artifacts.insert(e); return e;
    }
    private void requireLinkedTask(String ideaId, String taskId) { Long n = links.selectCount(new LambdaQueryWrapper<IdeaTaskLinkEntity>().eq(IdeaTaskLinkEntity::getIdeaId, ideaId).eq(IdeaTaskLinkEntity::getTaskId, taskId)); if (n == null || n == 0) throw new BadRequestException("该任务尚未关联到点子"); }
    private boolean hasArtifact(String ideaId, String kind) { Long n = artifacts.selectCount(new LambdaQueryWrapper<IdeaArtifactEntity>().eq(IdeaArtifactEntity::getIdeaId, ideaId).eq(IdeaArtifactEntity::getKind, kind)); return n != null && n > 0; }
    private IdeaEntity require(String id) { IdeaEntity e = ideas.selectById(id); if (e == null) throw new NotFoundException("点子不存在：" + id); return e; }
    private void touch(IdeaEntity e) { e.setUpdatedAt(now()); ideas.updateById(e); }
    private IdeaDto.Detail detail(IdeaEntity e) { try { return StringUtils.hasText(e.getDetailJson()) ? json.readValue(e.getDetailJson(), IdeaDto.Detail.class) : emptyDetail(List.of()); } catch (Exception ex) { return emptyDetail(List.of()); } }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException("点子详情无法序列化", ex); } }
    private IdeaDto.Detail emptyDetail(List<IdeaDto.Activity> activity) { return new IdeaDto.Detail("", "", "", "", "", "", "", null, List.of(), "", "", "", "", "", null, null, activity); }
    private IdeaDto.Detail merge(IdeaDto.Detail a, IdeaDto.Detail b) { return new IdeaDto.Detail(or(b.targetUsers(),a.targetUsers()),or(b.coreValue(),a.coreValue()),or(b.nonGoals(),a.nonGoals()),or(b.feasibility(),a.feasibility()),or(b.mvpHypothesis(),a.mvpHypothesis()),or(b.acceptanceCriteria(),a.acceptanceCriteria()),or(b.meetingDate(),a.meetingDate()),b.meetingDuration()!=null?b.meetingDuration():a.meetingDuration(),b.meetingParticipants()!=null?b.meetingParticipants():a.meetingParticipants(),or(b.meetingConclusion(),a.meetingConclusion()),or(b.meetingNotes(),a.meetingNotes()),or(b.validationConclusion(),a.validationConclusion()),or(b.validationEvidence(),a.validationEvidence()),or(b.abandonmentReason(),a.abandonmentReason()),or(b.researchAcceptedAt(),a.researchAcceptedAt()),or(b.researchAcceptedBy(),a.researchAcceptedBy()),a.activity()); }
    private IdeaDto.Detail withActivity(IdeaDto.Detail d, IdeaDto.Activity a) { List<IdeaDto.Activity> activity = new ArrayList<>(d.activity()==null?List.of():d.activity()); activity.add(0,a); return new IdeaDto.Detail(d.targetUsers(),d.coreValue(),d.nonGoals(),d.feasibility(),d.mvpHypothesis(),d.acceptanceCriteria(),d.meetingDate(),d.meetingDuration(),d.meetingParticipants(),d.meetingConclusion(),d.meetingNotes(),d.validationConclusion(),d.validationEvidence(),d.abandonmentReason(),d.researchAcceptedAt(),d.researchAcceptedBy(),activity); }
    private IdeaDto.Detail copy(IdeaDto.Detail d,String validation,String evidence,String acceptedBy,String abandon,String acceptedAt,String researchBy){ return new IdeaDto.Detail(d.targetUsers(),d.coreValue(),d.nonGoals(),d.feasibility(),d.mvpHypothesis(),d.acceptanceCriteria(),d.meetingDate(),d.meetingDuration(),d.meetingParticipants(),d.meetingConclusion(),d.meetingNotes(),or(validation,d.validationConclusion()),or(evidence,d.validationEvidence()),or(abandon,d.abandonmentReason()),or(acceptedAt,d.researchAcceptedAt()),or(researchBy!=null?researchBy:acceptedBy,d.researchAcceptedBy()),d.activity()); }
    private IdeaDto.Activity activity(String action,String by){return new IdeaDto.Activity(action,by,text(now()));}
    private String priority(String v){String p=StringUtils.hasText(v)?v.trim():"P2";if(!PRIORITIES.contains(p))throw new BadRequestException("点子优先级只支持 P0/P1/P2");return p;}
    private String stage(String v){String s=v.trim();if(!STAGES.contains(s))throw new BadRequestException("不支持的点子阶段");return s;}
    private String artifactKind(String v){String k=v.trim();if(!ARTIFACT_KINDS.contains(k))throw new BadRequestException("不支持的点子资产类型");return k;}
    private static String actor(String v){return StringUtils.hasText(v)?v:"unknown";} private static String id(String p){return p+UUID.randomUUID().toString().replace("-","").substring(0,Math.min(32-p.length(),24));}
    private static LocalDateTime now(){return LocalDateTime.now(SHANGHAI).withNano(0);} private static String text(LocalDateTime v){return v==null?null:v.toString();}
    private static String nz(String v){return v==null?"":v;} private static String blank(String v){return StringUtils.hasText(v)?v.trim():null;} private static String or(String v,String fallback){return v==null?fallback:v;}
}
