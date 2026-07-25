package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 系统级配置表实体：全局唯一一份的键值配置（飞书 webhook、群聊同步凭据等）。 */
@TableName("system_setting")
public class SystemSettingEntity {

    @TableId("cfg_key")
    private String cfgKey;

    @TableField("cfg_value")
    private String cfgValue;

    private LocalDateTime updatedAt;

    public String getCfgKey() { return cfgKey; }
    public void setCfgKey(String cfgKey) { this.cfgKey = cfgKey; }
    public String getCfgValue() { return cfgValue; }
    public void setCfgValue(String cfgValue) { this.cfgValue = cfgValue; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
