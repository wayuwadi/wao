package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;

/** 新建项目请求。visAll=true 表示全部成员可见。 */
public record CreateProjectRequest(
        @NotBlank String name,
        String area,
        String goal,
        boolean visAll,
        /** 是否在创建时通知团队；想法箱静默收集时传 false，缺省保持原有通知行为。 */
        Boolean announce) {

    /** 兼容既有调用与旧客户端：普通新建项目仍立即通知。 */
    public CreateProjectRequest(String name, String area, String goal, boolean visAll) {
        this(name, area, goal, visAll, true);
    }
}
