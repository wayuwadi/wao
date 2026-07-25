package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 一台安装了 WAO 执行节点和至少一种 AI CLI（Codex / Claude Code）的电脑。 */
@TableName("ai_runner_node")
public class AiRunnerNodeEntity {
    @TableId
    private String id;
    private String name;
    private String platform;
    private String engines;
    private Integer maxConcurrency;
    private String tokenHash;
    private LocalDateTime lastSeenAt;
    private LocalDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getEngines() { return engines; }
    public void setEngines(String engines) { this.engines = engines; }
    public Integer getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(Integer maxConcurrency) { this.maxConcurrency = maxConcurrency; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(LocalDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
