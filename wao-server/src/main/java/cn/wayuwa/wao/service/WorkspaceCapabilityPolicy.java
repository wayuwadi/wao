package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 设备签发时固定的操作白名单和可选资源范围；null 范围表示沿用成员可见性。 */
public record WorkspaceCapabilityPolicy(List<String> permissions, List<String> projectIds,
        List<String> knowledgeBaseIds) {
    public static final List<String> LEGACY_READ = List.of("list_projects", "get_project", "list_open_tasks", "get_task",
            "list_knowledge_bases", "get_knowledge_base", "list_documents", "get_document");
    public static final List<String> LEGACY_WRITE = List.of("create_project", "update_project", "create_task", "update_task",
            "update_subtask_status", "create_knowledge_base", "update_knowledge_base", "create_folder",
            "rename_folder", "move_knowledge_node", "create_document", "update_document");
    public static final List<String> READ = java.util.stream.Stream.concat(LEGACY_READ.stream(), java.util.stream.Stream.of(
            "get_workspace_info", "list_members", "list_tasks", "list_notes", "list_note_tags", "list_doc_templates",
            "list_archived_documents", "search_workspace", "get_todo_day", "list_todo_days", "get_daily_operation")).toList();
    public static final List<String> WRITE = java.util.stream.Stream.concat(LEGACY_WRITE.stream(), java.util.stream.Stream.of(
            "create_note_tag", "create_note", "update_note_tags", "create_doc_template", "update_doc_template",
            "archive_document", "unarchive_document", "create_subtask", "update_subtask", "reorder_subtasks",
            "move_subtask", "move_task", "add_task_comment", "update_task_comment", "link_task_document",
            "schedule_task", "record_daily_note")).toList();
    public static final List<String> ALL = java.util.stream.Stream.concat(
            java.util.stream.Stream.concat(READ.stream(), WRITE.stream()),
            java.util.stream.Stream.of("publish_html", "list_my_pets", "upload_pet", "share_pet")).toList();

    public WorkspaceCapabilityPolicy {
        if (permissions == null || permissions.isEmpty() || permissions.size() > ALL.size()
                || permissions.stream().anyMatch(p -> p == null || !ALL.contains(p))
                || new HashSet<>(permissions).size() != permissions.size()) {
            throw new BadRequestException("请选择至少一项有效操作，不能包含未知或重复权限");
        }
        Set<String> granted = Set.copyOf(permissions);
        permissions = ALL.stream().filter(granted::contains).toList();
        projectIds = scope(projectIds);
        knowledgeBaseIds = scope(knowledgeBaseIds);
    }

    private static List<String> scope(List<String> ids) {
        if (ids == null) return null;
        if (ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id.isBlank()
                || id.length() > 64 || !id.equals(id.trim())) || new HashSet<>(ids).size() != ids.size()) {
            throw new BadRequestException("指定范围必须包含 1 到 100 个有效且不重复的资源标识");
        }
        return List.copyOf(ids);
    }

    /** 是否允许指定操作；未知操作始终拒绝。 */
    public boolean permits(String operation) { return permissions.contains(operation); }
    /** 是否在项目范围内；业务成员可见性仍需额外检查。 */
    public boolean permitsProject(String id) { return projectIds == null || projectIds.contains(id); }
    /** 是否在知识库范围内；业务成员可见性仍需额外检查。 */
    public boolean permitsKnowledgeBase(String id) { return knowledgeBaseIds == null || knowledgeBaseIds.contains(id); }
    /** 用于兼容非敏感元数据的写操作标记，不能代替逐项检查。 */
    public boolean canWrite() { return permissions.stream().anyMatch(p -> WRITE.contains(p) || List.of("upload_pet", "share_pet").contains(p)); }
}
