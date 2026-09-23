package cn.wayuwa.wao.service;

import java.util.*;
import cn.wayuwa.wao.common.BadRequestException;

/** 知识库的混合目录；f: 为分组，d: 为文档，空字符串为根目录。 */
public final class KnowledgeDirectory {
    private KnowledgeDirectory() {}

    /** 校验移动后的完整父子关系，禁止环、自引用、悬空父级及没有所属分组的文档。 */
    public static void validate(Map<String, String> parents) {
        for (String node : parents.keySet()) {
            Set<String> seen = new HashSet<>();
            String cursor = node;
            boolean folder = node.startsWith("f:");
            while (!cursor.isEmpty()) {
                if (!seen.add(cursor)) throw new BadRequestException("不能移动到自身或后代节点中");
                String parent = parents.get(cursor);
                if (parent == null) throw new BadRequestException("目标目录不存在，请刷新后重试");
                folder |= cursor.startsWith("f:");
                cursor = parent;
            }
            if (!folder) throw new BadRequestException("文档需要放在分组或已有分组内的文档中");
        }
    }

    /** 返回文档最近的分组祖先，保持已有文档归属和检索接口兼容。 */
    public static String folderOf(String node, Map<String, String> parents) {
        String cursor = parents.get(node);
        while (cursor != null && !cursor.isEmpty()) {
            if (cursor.startsWith("f:")) return cursor.substring(2);
            cursor = parents.get(cursor);
        }
        throw new BadRequestException("文档缺少所属分组");
    }
}
