package cn.wayuwa.wao.dto;
/** 单节点关系与顺序变更；beforeSibling 为空时追加到目标层末尾。 */
public record MoveKnowledgeNodeRequest(String node, String parent, String expectedParent, String beforeSibling) {
    public MoveKnowledgeNodeRequest(String node, String parent, String expectedParent) {
        this(node, parent, expectedParent, null);
    }
}
