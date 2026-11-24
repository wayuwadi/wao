package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** 用于回收站的草图元数据。 */
public record DrawingTrashRow(String id, String title, String groupName, LocalDateTime deletedAt) {}
