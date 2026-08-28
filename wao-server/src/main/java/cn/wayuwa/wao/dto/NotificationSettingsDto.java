package cn.wayuwa.wao.dto;

/** 系统共享的飞书推送策略与当前成员独享的站内通知偏好。 */
public record NotificationSettingsDto(boolean canManageSystem, SystemPolicy system, PersonalPolicy personal) {

    /** 管理员维护、全组织共享的通知策略。 */
    public record SystemPolicy(boolean enabled, boolean taskActivity, boolean taskSuccess,
            boolean aiRunSuccess, boolean aiRunFailure, boolean manualTask, boolean automation) { }

    /** 当前登录成员自己的通知中心过滤偏好。 */
    public record PersonalPolicy(boolean enabled, boolean taskCreated, boolean taskCompleted,
            boolean aiRunSuccess, boolean aiRunFailure, boolean loginActivity) { }
}
