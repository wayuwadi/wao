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
        return sourceId.equals(targetId) ? List.of(tasks.get(sourceId, memberId))
                : List.of(tasks.get(sourceId, memberId), tasks.get(targetId, memberId));
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
                    blank(sub.createdBy()), blank(sub.createdAt()), sub.docs(), sub.comments()));
        }
        return result;
    }
}
