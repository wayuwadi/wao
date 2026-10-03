package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.mapper.TaskMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 完整任务之间的归属调整；不转换为内嵌 Step，不复制或删除原任务。 */
@Service
public class TaskHierarchyService {
    private final TaskMapper mapper;
    private final TaskService tasks;
    @org.springframework.beans.factory.annotation.Autowired
    private TodoHistoryService todoHistory;
    public TaskHierarchyService(TaskMapper mapper, TaskService tasks) { this.mapper = mapper; this.tasks = tasks; }

    /** 同项目内原子调整父任务；校验权限、旧关系和环，null 表示移回顶层。 */
    @Transactional
    public TaskDto move(String id, String parentId, String expectedParentId, String memberId) {
        TaskDto source = tasks.get(id, memberId);
        String parent = blank(parentId);
        if (parent != null) tasks.get(parent, memberId);
        Map<String, TaskEntity> rows = new HashMap<>();
        for (TaskEntity row : mapper.lockProjectTasks(source.project())) rows.put(row.getId(), row);
        TaskEntity current = rows.get(id);
        if (current == null) throw new ConflictException("任务已移动，请刷新后重试");
        tasks.get(id, memberId);
        if (!Objects.equals(current.getParentTaskId(), blank(expectedParentId)))
            throw new ConflictException("父任务已被其他窗口修改，请刷新后重试");
        if (parent != null) {
            tasks.get(parent, memberId);
            if (!rows.containsKey(parent)) throw new BadRequestException("只能拖到同一项目的任务下面");
            Set<String> seen = new HashSet<>(Set.of(id));
            for (String cursor = parent; cursor != null;) {
                if (!seen.add(cursor)) throw new BadRequestException("不能移到自己或后代任务下面");
                TaskEntity ancestor = rows.get(cursor);
                if (ancestor == null) throw new BadRequestException("父任务关系已变化，请刷新后重试");
                cursor = ancestor.getParentTaskId();
            }
        }
        mapper.updateParent(id, parent);
        if (todoHistory != null) {
            TaskEntity after = todoHistory.copy(current);
            after.setParentTaskId(parent);
            todoHistory.record(current, after, memberId, "HIERARCHY_CHANGED", null);
        }
        return tasks.get(id, memberId);
    }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value; }
}
