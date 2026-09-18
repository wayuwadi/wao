package cn.wayuwa.wao.service;

import java.util.*;
import cn.wayuwa.wao.dto.Step;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.mapper.WorkItemNumberMapper;
import org.springframework.stereotype.Service;

/** Resolves server-owned stable numbers in bounded batches, including legacy subtasks. */
@Service
public class WorkItemNumberService {
    private final WorkItemNumberMapper mapper;
    public WorkItemNumberService(WorkItemNumberMapper mapper) { this.mapper = mapper; }

    private String subId(TaskDto task, Step sub, int index) {
        return sub.id() == null || sub.id().isBlank() ? task.id() + "#" + index : sub.id();
    }

    /** Attach canonical numbers; client-supplied numbers never influence allocation. */
    public List<TaskDto> attach(List<TaskDto> tasks) {
        Set<String> keys = new LinkedHashSet<>();
        for (TaskDto task : tasks) {
            keys.add(task.id() + "/");
            for (int i = 0; i < task.subs().size(); i++)
                keys.add(task.id() + "/" + subId(task, task.subs().get(i), i));
        }
        List<String> all = new ArrayList<>(keys);
        Map<String, Long> numbers = new HashMap<>();
        for (int offset = 0; offset < all.size(); offset += 500) {
            List<String> batch = all.subList(offset, Math.min(offset + 500, all.size()));
            mapper.find(batch).forEach(e -> numbers.put(e.itemKey(), e.number()));
            List<String> missing = batch.stream().filter(k -> !numbers.containsKey(k)).toList();
            if (!missing.isEmpty()) {
                mapper.allocate(missing);
                mapper.find(missing).forEach(e -> numbers.put(e.itemKey(), e.number()));
            }
        }
        return tasks.stream().map(task -> {
            List<Step> subs = new ArrayList<>();
            for (int i = 0; i < task.subs().size(); i++) {
                Step sub = task.subs().get(i);
                String id = subId(task, sub, i);
                subs.add(sub.with(id, numbers.get(task.id() + "/" + id), sub.parentId()));
            }
            return task.withNumbers(numbers.get(task.id() + "/"), subs);
        }).toList();
    }
}
