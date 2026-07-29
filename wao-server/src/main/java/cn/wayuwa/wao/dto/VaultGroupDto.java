package cn.wayuwa.wao.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 密钥库分组对外表示，字段与前端 {@code VaultGroup} 一致（额外带 id 以支持增删改）。
 *
 * <p>{@code isDefault} 标出该成员的默认分组（不可删除、新建条目的默认落点）。前端据此判断，
 * 不再按名字猜——分组改名之后名字就不作数了（见 ADR-036）。
 */
public record VaultGroupDto(
        String id,
        String group,
        List<VaultItemDto> items,
        @JsonProperty("isDefault") boolean isDefault) {
}
