package cn.wayuwa.wao.dto;

import java.util.List;

/** 仅保存当前成员的展示布局，不包含应用地址或权限。 */
public record NavigationLayoutDto(Integer schemaVersion, List<Group> groups, List<Item> items, Boolean locked) {
    public record Group(String id, String label) {}
    public record Item(String id, String label, String icon, String group, Integer order,
            Boolean visible, String openMode, String placement) {}
    public record Snapshot(String revision, NavigationLayoutDto layout) {}
    public record Save(String expectedRevision, NavigationLayoutDto layout) {}
}
