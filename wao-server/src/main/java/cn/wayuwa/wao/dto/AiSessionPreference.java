package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Size;

/** 成员个人的会话整理方式，不改变任务标题、执行状态或会话文件。 */
public record AiSessionPreference(@Size(max = 160) String title, boolean pinned, boolean archived) {}
