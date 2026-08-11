package cn.wayuwa.wao.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 本节点标识。素材落在处理节点本地磁盘，发布任务必须回到同一节点执行，因此需要稳定的节点名。
 *
 * @param id 节点标识，部署时由 Compose 注入 master/slave
 */
@ConfigurationProperties(prefix = "wao-agent.node")
public record NodeProperties(String id) {

    public String resolved() {
        return StringUtils.hasText(id) ? id.trim() : "local";
    }
}
