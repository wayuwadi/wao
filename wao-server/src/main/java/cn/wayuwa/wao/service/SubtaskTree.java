package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.dto.Step;
import java.time.LocalDate;
import java.util.*;

/** 内嵌子任务树约束；兼容历史根节点，拒绝孤节点、重复标识和循环。 */
public final class SubtaskTree {
    private SubtaskTree() {}

    /** 对持久化子任务快照取摘要；前端只回传，不自行计算。 */
    public static String version(List<Step> subs) {
        try {
            byte[] bytes = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(subs == null ? List.of() : subs);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) { throw new IllegalStateException("子任务摘要计算失败", ex); }
    }

    /** 校验完整树及日期；不改变调用方的列表。 */
    public static void validate(List<Step> subs) {
        if (subs == null) return;
        Map<String, Step> byId = new HashMap<>();
        for (Step sub : subs) {
            if (sub.id() != null && byId.put(sub.id(), sub) != null) throw new BadRequestException("子任务标识重复");
            if (sub.due() != null && !sub.due().isBlank()) {
                try { LocalDate.parse(sub.due()); } catch (RuntimeException e) { throw new BadRequestException("子任务截止日期格式错误"); }
            }
        }
        for (Step sub : subs) {
            Set<String> seen = new HashSet<>();
            if (sub.id() != null) seen.add(sub.id());
            String parent = sub.parentId();
            while (parent != null && !parent.isBlank()) {
                if (!seen.add(parent)) throw new BadRequestException("子任务不能移到自己或后代下面");
                Step next = byId.get(parent);
                if (next == null) throw new BadRequestException("父子任务不存在，请刷新后重试");
                parent = next.parentId();
            }
        }
    }

    /** 返回根节点及全部后代的稳定 id。 */
    public static Set<String> descendants(List<Step> subs, String id) {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(id);
        boolean changed;
        do {
            changed = false;
            for (Step sub : subs) if (ids.contains(sub.parentId()) && ids.add(sub.id())) changed = true;
        } while (changed);
        return ids;
    }
}
