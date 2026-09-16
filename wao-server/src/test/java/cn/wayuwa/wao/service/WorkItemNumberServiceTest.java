package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import java.util.*;
import cn.wayuwa.wao.dto.*;
import cn.wayuwa.wao.mapper.WorkItemNumberMapper;
import org.junit.jupiter.api.Test;

class WorkItemNumberServiceTest {
    private final WorkItemNumberMapper mapper = mock(WorkItemNumberMapper.class);
    private final Map<String, Long> registry = new LinkedHashMap<>();
    private final WorkItemNumberService service = new WorkItemNumberService(mapper);

    WorkItemNumberServiceTest() {
        when(mapper.find(anyList())).thenAnswer(call -> ((List<String>) call.getArgument(0)).stream()
            .filter(registry::containsKey).map(k -> new WorkItemNumberMapper.Entry(k, registry.get(k))).toList());
        doAnswer(call -> {
            for (String key : (List<String>) call.getArgument(0)) registry.computeIfAbsent(key, k -> (long) registry.size());
            return null;
        }).when(mapper).allocate(anyList());
    }

    private TaskDto task(String id, List<Step> subs) {
        return new TaskDto(id, "title", "p1", "待办", "P2", "m1", List.of(), null, null, null, "",
            List.of(), subs, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test void stableAfterInsertReorderDeleteAndForgedInput() {
        TaskDto first = service.attach(List.of(task("t1", List.of(new Step("old", false, "s1", null, List.of()))))).get(0);
        assertThat(first.number()).isZero();
        assertThat(first.subs().get(0).number()).isEqualTo(1L);
        Step forged = new Step("edited", true, "s1", null, List.of(), 999L);
        TaskDto next = service.attach(List.of(task("t1", List.of(new Step("new", false, "s2", null, List.of()), forged)))).get(0);
        assertThat(next.number()).isZero();
        assertThat(next.subs()).extracting(Step::number).containsExactly(2L, 1L);
        service.attach(List.of(task("t1", List.of())));
        TaskDto restored = service.attach(List.of(task("t1", List.of(forged)))).get(0);
        assertThat(restored.subs().get(0).number()).isEqualTo(1L);
        assertThat(service.attach(List.of(task("t2", List.of()))).get(0).number()).isEqualTo(3L);
    }

    @Test void legacySubIdentityBecomesStableBeforeReorder() {
        TaskDto legacy = service.attach(List.of(task("t1", List.of(
            new Step("a", false, null, null, List.of()), new Step("b", false, null, null, List.of()))))).get(0);
        assertThat(legacy.subs()).extracting(Step::id).containsExactly("t1#0", "t1#1");
        TaskDto reordered = service.attach(List.of(task("t1", List.of(legacy.subs().get(1), legacy.subs().get(0))))).get(0);
        assertThat(reordered.subs()).extracting(Step::number).containsExactly(2L, 1L);
        verify(mapper, times(1)).allocate(anyList());
    }

    @Test void batchesManyTasksWithoutPerRowQueriesAndDoesNotReallocate() {
        List<TaskDto> tasks = java.util.stream.IntStream.range(0, 400).mapToObj(i -> task("t" + i, List.of())).toList();
        service.attach(tasks);
        verify(mapper, times(2)).find(anyList());
        service.attach(tasks);
        verify(mapper, times(3)).find(anyList());
        verify(mapper, times(1)).allocate(anyList());
    }

    @Test void sameSubIdInDifferentParentsGetsDistinctNumbers() {
        List<TaskDto> result = service.attach(List.of(task("t1", List.of(new Step("a", false, "s", null, List.of()))),
            task("t2", List.of(new Step("b", false, "s", null, List.of())))));
        assertThat(result.get(0).subs().get(0).number()).isNotEqualTo(result.get(1).subs().get(0).number());
    }
}
