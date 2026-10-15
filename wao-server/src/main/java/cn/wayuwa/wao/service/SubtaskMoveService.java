package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.dto.*;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.mapper.TaskMapper;
import cn.wayuwa.wao.mapper.WorkItemNumberMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在一个事务内迁移内嵌子任务，源与目标要么同时成功，要么同时回滚。 */
@Service
public class SubtaskMoveService {
    private final TaskMapper mapper;
    private final TaskService tasks;
    private final WorkItemNumberMapper numbers;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired
    private TodoHistoryService todoHistory;
    public SubtaskMoveService(TaskMapper mapper, TaskService tasks, WorkItemNumberMapper numbers, ObjectMapper json) {
        this.mapper = mapper; this.tasks = tasks; this.numbers = numbers; this.json = json;
    }

    /** 校验双方可见性、快照与树关系后移动整棵子树，并保留子任务编号。 */
    @Transactional
    public List<TaskDto> move(String sourceId, String subId, MoveSubtaskRequest request, String memberId) {
        String targetId = request.targetTaskId();
        // 先做权限检查，不对不可见对象执行写入。
        tasks.get(sourceId, memberId);
        tasks.get(targetId, memberId);
        Map<String, TaskEntity> locked = new HashMap<>();
        for (String id : new TreeSet<>(List.of(sourceId, targetId))) locked.put(id, mapper.lockById(id));
        if (locked.values().contains(null)) throw new BadRequestException("任务已变更，请刷新");
        tasks.get(sourceId, memberId);
        tasks.get(targetId, memberId);
        if (mapper.countUnfinishedRuns(sourceId, targetId) > 0) throw new BadRequestException("任务仍有未结束的 AI 执行，请结束后再移动子任务");
        TaskEntity source = locked.get(sourceId), target = locked.get(targetId);
        TaskEntity sourceBefore = todoHistory == null ? null : todoHistory.copy(source);
        TaskEntity targetBefore = todoHistory == null || sourceId.equals(targetId) ? null : todoHistory.copy(target);
        List<Step> from = readSubs(source), to = sourceId.equals(targetId) ? from : readSubs(target);
        if (!from.equals(normalize(sourceId, request.expectedSourceSubs())) || !to.equals(normalize(targetId, request.expectedTargetSubs())))
            throw new BadRequestException("子任务已有更新，请刷新后重试；未移动任何内容");
        SubtaskTree.validate(from);
        SubtaskTree.validate(to);
        if (from.stream().noneMatch(sub -> subId.equals(sub.id()))) throw new BadRequestException("子任务不存在");
        Set<String> moving = SubtaskTree.descendants(from, subId);
        String parent = blank(request.parentId());
        if (sourceId.equals(targetId) && moving.contains(parent)) throw new BadRequestException("不能移到自己或后代下面");
        if (parent != null && to.stream().noneMatch(sub -> parent.equals(sub.id()))) throw new BadRequestException("目标父子任务不存在");
        if (!sourceId.equals(targetId) && to.stream().anyMatch(sub -> moving.contains(sub.id()))) throw new BadRequestException("目标已存在同标识子任务，请刷新");
        List<Step> moved = from.stream().filter(sub -> moving.contains(sub.id())).map(sub ->
                sub.id().equals(subId) ? sub.with(sub.id(), null, parent) : sub).toList();
        from.removeIf(sub -> moving.contains(sub.id()));
        to.addAll(moved);
        SubtaskTree.validate(from);
        SubtaskTree.validate(to);
        writeSubs(source, from);
        if (!sourceId.equals(targetId)) {
            writeSubs(target, to);
            for (String id : moving) numbers.move(sourceId + "/" + id, targetId + "/" + id);
        }
        if (todoHistory != null) {
            todoHistory.record(sourceBefore, source, memberId, "STEP_MOVED", null);
            if (targetBefore != null) todoHistory.record(targetBefore, target, memberId, "STEP_MOVED", null);
        }
        return sourceId.equals(targetId) ? List.of(tasks.get(sourceId, memberId))
                : List.of(tasks.get(sourceId, memberId), tasks.get(targetId, memberId));
    }

    /** 提升整棵子树，保留编号和已有属性；事务与稳定目标标识保护失败重试。 */
    @Transactional
    public List<TaskDto> promote(String sourceId, String subId, PromoteSubtaskRequest request, String memberId) {
        tasks.get(sourceId, memberId);
        TaskEntity source = mapper.lockById(sourceId);
        if (source == null) throw new BadRequestException("任务已变更，请刷新");
        tasks.get(sourceId, memberId);
        String targetId = "t" + UUID.nameUUIDFromBytes((sourceId + "/" + subId).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", "").substring(0, 31);
        List<Step> from = readSubs(source);
        Step root = from.stream().filter(sub -> subId.equals(sub.id())).findFirst().orElse(null);
        // 同一来源的重复请求返回已经提交的结果，避免网络响应丢失后重复创建。
        if (root == null && mapper.selectById(targetId) != null)
            return List.of(tasks.get(sourceId, memberId), tasks.get(targetId, memberId));
        if (!from.equals(normalize(sourceId, request.expectedSourceSubs())))
            throw new cn.wayuwa.wao.common.ConflictException("子任务已有更新，请刷新后重试；未提升任何内容");
        if (root == null) throw new BadRequestException("子任务不存在");
        if (mapper.selectById(targetId) != null) throw new BadRequestException("该子事项曾被提升，请刷新后核对");
        if (mapper.countUnfinishedRuns(sourceId, sourceId) > 0)
            throw new BadRequestException("任务仍有未结束的 AI 执行，请结束后再提升子任务");
        SubtaskTree.validate(from);
        Set<String> moving = SubtaskTree.descendants(from, subId);
        List<Step> children = from.stream().filter(sub -> moving.contains(sub.id()) && !subId.equals(sub.id()))
                .map(sub -> subId.equals(sub.parentId()) ? sub.with(sub.id(), null, null) : sub).toList();
        TaskEntity before = todoHistory == null ? null : todoHistory.copy(source);
        TaskEntity target = new TaskEntity();
        target.setId(targetId);
        target.setProjectId(source.getProjectId());
        target.setTitle(root.text());
        target.setDescription(root.desc());
        target.setStatus(root.done() ? "已完成" : Set.of("进行中", "待验证").contains(Objects.toString(root.status(), "")) ? root.status() : "待办");
        target.setPr(blank(root.pr()) == null ? "P2" : root.pr());
        target.setOwnerId(blank(root.owner()) == null ? source.getOwnerId() : root.owner());
        target.setDueDate(root.due());
        target.setCreatedBy(blank(root.createdBy()) == null ? memberId : root.createdBy());
        if (root.createdAt() != null) {
            try { target.setCreatedAt(java.time.LocalDateTime.parse(root.createdAt())); }
            catch (java.time.format.DateTimeParseException ignored) {
                try { target.setCreatedAt(java.time.OffsetDateTime.parse(root.createdAt()).atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime()); }
                catch (java.time.format.DateTimeParseException unknown) { /* 历史未知时间由数据库记录本次创建时间。 */ }
            }
        }
        if (root.done()) target.setCompletedAt(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));
        try {
            ObjectNode detail = json.createObjectNode();
            detail.set("subs", json.valueToTree(children));
            detail.set("images", json.valueToTree(root.images()));
            detail.set("docs", json.valueToTree(root.docs() == null ? List.of() : root.docs()));
            detail.set("comments", json.valueToTree(root.comments() == null ? List.of() : root.comments()));
            detail.put("groupId", json.readTree(source.getDetailJson()).path("groupId").asText(""));
            detail.put("workItemType", "task");
            target.setDetailJson(json.writeValueAsString(detail));
        } catch (Exception ex) { throw new BadRequestException("子任务详情无法转换，未执行提升"); }
        mapper.insert(target);
        numbers.move(sourceId + "/" + subId, targetId + "/");
        for (Step child : children) numbers.move(sourceId + "/" + child.id(), targetId + "/" + child.id());
        from.removeIf(sub -> moving.contains(sub.id()));
        writeSubs(source, from);
        if (todoHistory != null) {
            todoHistory.record(before, source, memberId, "STEP_MOVED", null);
            todoHistory.record(null, target, memberId, "TASK_CREATED", null);
        }
        return List.of(tasks.get(sourceId, memberId), tasks.get(targetId, memberId));
    }

    private List<Step> readSubs(TaskEntity task) {
        try { return normalize(task.getId(), json.readValue(task.getDetailJson(), TaskDetail.class).subs()); }
        catch (Exception e) { throw new BadRequestException("任务详情无法读取，未执行移动"); }
    }
    private void writeSubs(TaskEntity task, List<Step> subs) {
        try {
            ObjectNode detail = (ObjectNode) json.readTree(task.getDetailJson());
            detail.set("subs", json.valueToTree(subs));
            mapper.updateDetail(task.getId(), json.writeValueAsString(detail));
            task.setDetailJson(json.writeValueAsString(detail));
        } catch (Exception e) { throw new BadRequestException("保存移动失败，已回滚"); }
    }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value; }
    private static List<Step> normalize(String taskId, List<Step> subs) {
        List<Step> result = new ArrayList<>();
        if (subs == null) return result;
        for (int i = 0; i < subs.size(); i++) {
            Step sub = subs.get(i);
            result.add(new Step(sub.text(), sub.done(), blank(sub.id()) == null ? taskId + "#" + i : sub.id(),
                    sub.desc() == null ? "" : sub.desc(), sub.images() == null ? List.of() : sub.images(), null,
                    blank(sub.parentId()), blank(sub.owner()), blank(sub.due()), blank(sub.status()),
                    blank(sub.createdBy()), blank(sub.createdAt()), sub.docs(), sub.comments(), sub.pr()));
        }
        return result;
    }
}
