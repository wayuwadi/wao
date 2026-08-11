package cn.wayuwa.wao.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

/** 新建任务请求。due 为 ISO 日期（YYYY-MM-DD）。 */
public record CreateTaskRequest(
        @NotBlank String title,
        @NotBlank String project,
        String pr,
        String owner,
        String due,
        String desc,
        List<ImageRef> images,
        /** 落进哪个任务分组（分组视图里的「+」带上它）；不分组时为空。 */
        String groupId,
        /** 日历时间段开始（{@code yyyy-MM-ddTHH:mm}），普通截止任务为空。 */
        String startAt,
        /** 日历时间段结束（{@code yyyy-MM-ddTHH:mm}）。 */
        String endAt,
        /** 可选的首轮 PDCA；为空列表表示创建普通任务。 */
        List<PdcaCycle> pdcaCycles) {

    /** 兼容尚未引入 PDCA 的既有调用。 */
    public CreateTaskRequest(String title, String project, String pr, String owner, String due,
            String desc, List<ImageRef> images, String groupId, String startAt, String endAt) {
        this(title, project, pr, owner, due, desc, images, groupId, startAt, endAt, null);
    }

    /** 兼容尚未引入任务分组的既有调用。 */
    public CreateTaskRequest(String title, String project, String pr, String owner, String due,
            String desc, List<ImageRef> images) {
        this(title, project, pr, owner, due, desc, images, null, null, null, null);
    }
}
