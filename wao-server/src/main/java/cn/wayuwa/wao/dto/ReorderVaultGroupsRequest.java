package cn.wayuwa.wao.dto;

import java.util.List;

/** 密钥库分组排序请求，ids 按用户期望的展示顺序排列。 */
public record ReorderVaultGroupsRequest(List<String> ids) {
}
