package cn.wayuwa.wao.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 素材存储配置。
 *
 * @param dir      素材根目录，生产挂载到容器数据卷
 * @param maxBytes 单个素材大小上限
 */
@ConfigurationProperties(prefix = "wao-agent.media")
public record MediaProperties(String dir, Long maxBytes) {

    public String directory() {
        return dir == null || dir.isBlank() ? "./.local-data/media" : dir.trim();
    }

    public long limit() {
        return maxBytes == null || maxBytes <= 0 ? 300L * 1024 * 1024 : maxBytes;
    }
}
