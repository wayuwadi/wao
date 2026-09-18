package cn.wayuwa.wao.dto;

import static org.assertj.core.api.Assertions.assertThat;

import cn.wayuwa.wao.service.SubtaskTree;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class StepTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void historicalSubtasksKeepTheirJsonAndVersionDigest() throws Exception {
        // 升级前落库的子任务快照：没有状态、创建人、关联文档、评论等新字段
        String stored = "[{\"text\":\"旧子任务\",\"done\":false,\"id\":\"s1\",\"desc\":\"\",\"images\":[],\"number\":3,"
                + "\"parentId\":null,\"owner\":\"wg\",\"due\":null}]";
        List<Step> subs = List.of(json.readValue(stored, Step[].class));

        assertThat(json.writeValueAsString(subs)).isEqualTo(stored);
        assertThat(SubtaskTree.version(subs)).isEqualTo(SubtaskTree.version(List.of(
                new Step("旧子任务", false, "s1", "", List.of(), 3L, null, "wg", null))));
    }

    @Test
    void newSubtaskFieldsRoundTripAndDescribeTheirState() throws Exception {
        Step step = new Step("子任务", false, "s1", "", List.of(), null, null, "wd", null, "进行中", "wd",
                "2026-09-18T21:00:00", List.of("doc1"), List.of(new CommentDto("c1", "wg", "2026-09-18T21:05:00", "看一下")));

        Step read = json.readValue(json.writeValueAsString(step), Step.class);

        assertThat(read).isEqualTo(step);
        assertThat(read.statusLabel()).isEqualTo("进行中");
        assertThat(json.writeValueAsString(step)).doesNotContain("statusLabel");
        assertThat(new Step("完成", true, "s2", "", List.of()).statusLabel()).isEqualTo("已完成");
        assertThat(new Step("没开始", false, "s3", "", List.of()).statusLabel()).isEqualTo("未开始");
    }

    @Test
    void renumberingOrMovingKeepsTheNewFields() {
        Step step = new Step("子任务", false, "s1", "", List.of(), null, null, "wd", null, "进行中", "wd",
                "2026-09-18T21:00:00", List.of("doc1"), List.of());

        Step moved = step.with("s1", 9L, "parent");

        assertThat(moved.number()).isEqualTo(9L);
        assertThat(moved.parentId()).isEqualTo("parent");
        assertThat(moved.status()).isEqualTo("进行中");
        assertThat(moved.createdBy()).isEqualTo("wd");
        assertThat(moved.docs()).containsExactly("doc1");
    }
}
