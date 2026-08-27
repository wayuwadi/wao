package cn.wayuwa.wao.dto;

import java.util.List;
import jakarta.validation.constraints.Size;

/** 用审核版本 id 整体替换 WAO 应用默认 Skill。 */
public record SaveWaoApplicationSkillsRequest(
        @Size(max = 10) List<String> versionIds) { }
