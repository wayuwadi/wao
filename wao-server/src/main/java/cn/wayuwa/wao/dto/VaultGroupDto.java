package cn.wayuwa.wao.dto;

import java.util.List;

/** 密钥库分组对外表示，字段与前端 {@code VaultGroup} 一致（额外带 id 以支持增删改）。 */
public record VaultGroupDto(String id, String group, List<VaultItemDto> items) {
}
