package cn.wayuwa.wao.dto;

import java.util.List;
import jakarta.validation.constraints.Size;

/** 用审核版本 id 整体替换项目的 Skill 绑定。 */
public record SaveProjectSkillsRequest(
        @Size(max = 10) List<String> versionIds) { }
