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
        String groupId) {

    /** 兼容尚未引入任务分组的既有调用。 */
    public CreateTaskRequest(String title, String project, String pr, String owner, String due,
            String desc, List<ImageRef> images) {
        this(title, project, pr, owner, due, desc, images, null);
    }
}
