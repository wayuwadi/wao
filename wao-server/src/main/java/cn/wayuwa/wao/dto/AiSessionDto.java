package cn.wayuwa.wao.dto;

/** 会话列表只返回摘要；进度、报告和记忆在选中后按需读取。 */
public record AiSessionDto(String id, String taskId, String title, String projectName,
        String engine, String model, String status, String sessionId, String runnerName,
        String preview, String updatedAt, boolean pinned, boolean archived) {}
