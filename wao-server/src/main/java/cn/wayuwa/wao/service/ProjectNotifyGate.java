package cn.wayuwa.wao.service;

import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.mapper.ProjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 项目级通知开关：一条通知该不该发到飞书群，先问它所属的项目。
 *
 * <p>为什么按项目分：群机器人原本对所有项目一视同仁地播报，多数项目的新建、状态变化其实
 * 没人需要立刻知道，通知一多整块就被无视了，真正等验收的那条也跟着被划过去。开关放在项目上，
 * 粒度正好等于「我关心哪个项目」。
 *
 * <p>不属于任何项目的通知（小记、执行电脑上下线、群聊快速收集的回执）不归这里管，
 * 它们没有项目可问，仍由全局 webhook 开关决定。
 *
 * <p>直接依赖 {@link ProjectMapper} 而不是 {@code ProjectService}：ProjectService 自己要发
 * 「新建了项目」通知，走 Service 会绕出一圈构造器循环依赖。
 */
@Component
public class ProjectNotifyGate {

    private final ProjectMapper projects;

    public ProjectNotifyGate(ProjectMapper projects) {
        this.projects = projects;
    }

    /**
     * 这个项目的动静要不要发飞书群。
     *
     * @param projectId 项目 id；为空表示通知不属于任何项目
     * @return 允许发送时返回 true；项目查不到或老数据没有开关值时按开放行，不静默吞掉通知
     */
    public boolean enabled(String projectId) {
        if (!StringUtils.hasText(projectId)) {
            return true;
        }
        ProjectEntity project = projects.selectById(projectId);
        return project == null || !Boolean.FALSE.equals(project.getNotifyEnabled());
    }
}
