package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.dto.*;
import cn.wayuwa.wao.mapper.AccountMapper;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.service.WorkspaceMcpTokenService.AuthenticatedToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Predicate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 日常工作区操作适配器，复用业务服务和 CAS，不接入私人 AI 来源或执行权限。 */
@Service
public class WorkspaceMcpOperations {
    private final ProjectService projects;
    private final TaskService tasks;
    private final KnowledgeService knowledge;
    private final NoteTagService tags;
    private final MemberService members;
    private final TaskHierarchyService hierarchy;
    private final SubtaskMoveService subtaskMove;
    private final TodoDayService days;
    private final TodoOperationService daily;
    private final AccountMapper accounts;
    private final DocMapper docs;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public WorkspaceMcpOperations(ProjectService projects, TaskService tasks, KnowledgeService knowledge,
            NoteTagService tags, MemberService members, TaskHierarchyService hierarchy,
            SubtaskMoveService subtaskMove, TodoDayService days, TodoOperationService daily,
            AccountMapper accounts, DocMapper docs, JdbcTemplate jdbc, ObjectMapper json) {
        this.projects = projects; this.tasks = tasks; this.knowledge = knowledge; this.tags = tags;
        this.members = members; this.hierarchy = hierarchy; this.subtaskMove = subtaskMove;
        this.days = days; this.daily = daily; this.accounts = accounts; this.docs = docs;
        this.jdbc = jdbc; this.json = json;
    }

    /** 构建明确、可验证的扩展目录；每日范围仅在显式授权后出现。 */
    public static void catalog(ArrayNode tools, boolean write, boolean daily) {
        tool(tools, "get_workspace_info", "读取连接身份与权限", true, "");
        tool(tools, "list_members", "列出组织成员的编号与姓名", true, "");
        tool(tools, "list_tasks", "分页查找任务及已完成历史；from/to 按截止日期", true,
                "keyword:s project_id:s mine_only:b status:s owner:s from:s to:s offset:i limit:i");
        tool(tools, "list_notes", "分页查找小记，默认本人；tags 为全部匹配；from/to 按创建日期", true,
                "keyword:s tags:a mine_only:b from:s to:s offset:i limit:i");
        tool(tools, "list_note_tags", "读取共享小记标签字典", true, "");
        tool(tools, "list_doc_templates", "读取本人的文档模板", true, "");
        tool(tools, "list_archived_documents", "分页读取指定知识库的归档文档", true, "kb_id:s! offset:i limit:i");
        tool(tools, "search_workspace", "全文检索可见任务与当前文档，返回片段及业务链接", true,
                "keyword:s! kinds:a mine_only:b offset:i limit:i");
        if (write) {
            tool(tools, "create_note_tag", "幂等创建小记标签", false, "name:s!");
            tool(tools, "create_note", "创建到本人小记文件夹；稳定 UUID 文档 id 可安全重试；不自动调用 AI", false,
                    "document_id:s title:s text:s blocks:a content:o tags:a");
            tool(tools, "update_note_tags", "仅修改小记标签，不替换正文；需当前修订号", false,
                    "document_id:s! mode:s! tags:a expected_rev:i!");
            tool(tools, "create_doc_template", "从可见来源文档创建本人模板", false,
                    "name:s! description:s source_document_id:s! blocks:a content:o");
            tool(tools, "update_doc_template", "以读取时 updatedAt 更新本人模板", false,
                    "template_id:s! name:s description:s blocks:a content:o expected_updated_at:s!");
            tool(tools, "archive_document", "归档文档；需修订号，保留正文", false, "document_id:s! expected_rev:i!");
            tool(tools, "unarchive_document", "取消归档文档；需修订号", false, "document_id:s! expected_rev:i!");
            tool(tools, "create_subtask", "在读取时任务版本上新增子任务", false,
                    "task_id:s! subtask_id:s text:s! description:s parent_id:s? owner:s due:s priority:s expected_edit_version:s!");
            tool(tools, "update_subtask", "只修改指定子任务，保留其他内容", false,
                    "task_id:s! subtask_id:s! text:s description:s parent_id:s? owner:s due:s priority:s expected_edit_version:s!");
            tool(tools, "reorder_subtasks", "按完整 id 清单重排子任务，不能删除或复制项", false,
                    "task_id:s! subtask_ids:a! expected_edit_version:s!");
            tool(tools, "move_subtask", "事务移动整棵子树；必须带两边的子任务版本", false,
                    "task_id:s! subtask_id:s! target_task_id:s! parent_id:s? expected_source_version:s! expected_target_version:s!");
            tool(tools, "move_task", "同项目内调整完整任务父级；null 或空串表示顶层", false,
                    "task_id:s! parent_task_id:s?! expected_parent_id:s?!");
            tool(tools, "add_task_comment", "以令牌成员身份追加任务评论", false, "task_id:s! text:s!");
            tool(tools, "update_task_comment", "修改本人任务评论", false, "task_id:s! comment_id:s! text:s!");
            tool(tools, "link_task_document", "增删一个可见文档关联，保留其他任务字段", false,
                    "task_id:s! document_id:s! mode:s! expected_edit_version:s!");
        }
        if (daily) {
            tool(tools, "get_todo_day", "读取本人的每日安排和真实进展", true, "date:s!");
            tool(tools, "list_todo_days", "读取本人最多 31 天的每日摘要", true, "from:s! to:s!");
            tool(tools, "get_daily_operation", "读取本人操作号回执；未知号返回 UNCONFIRMED", true, "operation_id:s!");
            if (write) {
                tool(tools, "schedule_task", "CAS 保存执行安排；四个安排字段均须显式提供，可为 null，不改截止", false,
                        "task_id:s! operation_id:s! expected_edit_version:s! start:s?! start_at:s?! end_at:s?! schedule_period:s?!");
                tool(tools, "record_daily_note", "原子记录当日/补记进展及任务关联；不自动调用 AI", false,
                        "operation_id:s! document_id:s! business_date:s! title:s text:s blocks:a content:o tags:a task_id:s expected_task_edit_version:s expected_rev:i");
            }
        }
    }

    /** 调用普通或单独授权的个人每日操作，每次校验当前可见性。 */
    @Transactional
    public Object call(String name, JsonNode a, AuthenticatedToken token) {
        String member = token.memberId();
        return switch (name) {
            case "get_workspace_info" -> Map.of("version", "2.0.0", "member", Map.of("id", member,
                    "name", members.list().stream().filter(m -> member.equals(m.id())).map(MemberDto::name).findFirst().orElse(member)),
                    "permissions", Map.of("write", token.canWrite(), "daily", token.canUseDaily(), "hostedHtml", token.canPublishHostedPage()),
                    "capabilities", WorkspaceCapabilityPolicy.ALL.stream().filter(token::permits).toList());
            case "list_members" -> members.list().stream().map(m -> Map.of("id", m.id(), "name", m.name())).toList();
            case "list_tasks" -> listTasks(a, token);
            case "list_notes" -> listNotes(a, token);
            case "list_note_tags" -> tags.list();
            case "create_note_tag" -> tags.create(text(a, "name", true, 32));
            case "create_note" -> createNote(a, member);
            case "update_note_tags" -> updateTags(a, member);
            case "list_doc_templates" -> knowledge.listTemplates(member);
            case "create_doc_template" -> {
                DocDto source = doc(text(a, "source_document_id", true, 64), member);
                yield knowledge.createTemplate(new CreateDocTemplateRequest(text(a, "name", true, 128),
                        text(a, "description", false, 512), source.id(), blocks(a), content(a)), member);
            }
            case "update_doc_template" -> knowledge.updateTemplate(text(a, "template_id", true, 64),
                    new PatchDocTemplateRequest(text(a, "name", false, 128), text(a, "description", false, 512),
                            blocks(a), content(a), text(a, "expected_updated_at", true, 40)), member);
            case "list_archived_documents" -> {
                String kb = text(a, "kb_id", true, 64); kb(kb, member);
                yield page(knowledge.listArchivedDocs(kb).stream().map(WorkspaceMcpOperations::summary).toList(), a);
            }
            case "archive_document", "unarchive_document" -> lifecycle(name, a, member);
            case "create_subtask", "update_subtask", "reorder_subtasks" -> editSubtask(name, a, member);
            case "move_subtask" -> moveSubtask(a, member);
            case "move_task" -> hierarchy.move(text(a, "task_id", true, 64), text(a, "parent_task_id", false, 64),
                    text(a, "expected_parent_id", false, 64), member);
            case "add_task_comment" -> tasks.addComment(text(a, "task_id", true, 64), member, text(a, "text", true, 20000));
            case "update_task_comment" -> tasks.updateComment(text(a, "task_id", true, 64),
                    text(a, "comment_id", true, 64), member, text(a, "text", true, 20000));
            case "link_task_document" -> link(a, member);
            case "search_workspace" -> search(a, token);
            case "get_todo_day" -> days.day(date(a, "date", true), member, t -> token.permitsProject(t.project()),
                    token.permitsKnowledgeBase(KnowledgeService.NOTES_KB_ID) && visible(KnowledgeService.NOTES_KB_ID, member));
            case "list_todo_days" -> days.month(date(a, "from", true), date(a, "to", true), member,
                    t -> token.permitsProject(t.project()), token.permitsKnowledgeBase(KnowledgeService.NOTES_KB_ID)
                            && visible(KnowledgeService.NOTES_KB_ID, member));
            case "get_daily_operation" -> receipt(a, token);
            case "schedule_task" -> daily.schedule(text(a, "task_id", true, 64), text(a, "operation_id", true, 36),
                    new TodoOperationService.ScheduleRequest(text(a, "expected_edit_version", true, 64),
                            blank(text(a, "start", false, 10)), blank(text(a, "start_at", false, 40)),
                            blank(text(a, "end_at", false, 40)), blank(text(a, "schedule_period", false, 20))), account(member), member);
            case "record_daily_note" -> recordDaily(a, member);
            default -> throw new BadRequestException("不存在的工具：" + name);
        };
    }

    /** 扩展操作同步执行逐项授权的资源范围检查；跨任务写先按稳定顺序锁住双方。 */
    public void requireScope(String name, JsonNode a, AuthenticatedToken token) {
        if (token.policy() == null) return;
        if (Set.of("create_note", "list_notes", "record_daily_note").contains(name)) {
            kb(KnowledgeService.NOTES_KB_ID, token.memberId());
            if (!token.permitsKnowledgeBase(KnowledgeService.NOTES_KB_ID)) throw new BadRequestException("小记知识库不在连接授权范围内");
        }
        for (String field : List.of("kb_id", "project_id")) if (a.hasNonNull(field)) {
            String id = text(a, field, true, 64);
            if ("kb_id".equals(field) ? !token.permitsKnowledgeBase(id) : !token.permitsProject(id))
                throw new BadRequestException("目标资源不在连接授权范围内");
        }
        if (token.policy().projectIds() != null) {
            TreeSet<String> ids = new TreeSet<>();
            for (String field : List.of("task_id", "target_task_id", "parent_task_id")) if (a.hasNonNull(field) && !a.path(field).asText().isBlank()) ids.add(text(a, field, true, 64));
            for (String id : ids) {
                TaskDto t = WorkspaceCapabilityPolicy.WRITE.contains(name) ? tasks.lockWorkspaceTask(id, token.memberId()) : tasks.get(id, token.memberId());
                if (!token.permitsProject(t.project())) throw new BadRequestException("任务所属项目不在连接授权范围内");
            }
        }
        if (token.policy().knowledgeBaseIds() != null) {
            for (String field : List.of("document_id", "source_document_id")) if (a.hasNonNull(field)
                    && !Set.of("create_note", "record_daily_note").contains(name)) {
                DocDto d = doc(text(a, field, true, 64), token.memberId());
                if (!token.permitsKnowledgeBase(d.kbId())) throw new BadRequestException("文档所属知识库不在连接授权范围内");
            }
        }
    }

    private Object receipt(JsonNode a, AuthenticatedToken token) {
        String operation = text(a, "operation_id", true, 36), accountId = account(token.memberId());
        try { if (!UUID.fromString(operation).toString().equals(operation)) throw new IllegalArgumentException(); }
        catch (RuntimeException ex) { throw new BadRequestException("operation_id 必须是规范 UUID"); }
        // 浏览器每日规则也使用同一回执表，但未包含在本次工作区授权中，不能借操作号读取。
        List<String> types = jdbc.query("SELECT operation_type FROM wao_todo_operation_receipt WHERE account_id = ? AND operation_id = ?",
                (rs, row) -> rs.getString(1), accountId, operation);
        if (types.isEmpty() || !Set.of("NOTE", "SCHEDULE").contains(types.get(0)))
            return new TodoOperationService.Receipt("UNCONFIRMED", operation, null, null);
        TodoOperationService.Receipt result = daily.receipt(operation, accountId, token.memberId());
        TaskDto task = result.result() instanceof TodoOperationService.ScheduleResult schedule ? schedule.task()
                : result.result() instanceof TodoOperationService.NoteResult note ? note.task() : null;
        DocDto document = result.result() instanceof TodoOperationService.NoteResult note ? note.doc() : null;
        if ((task != null && !token.permitsProject(task.project())) || (document != null && !token.permitsKnowledgeBase(document.kbId())))
            throw new BadRequestException("回执目标不在连接授权范围内");
        return result;
    }

    /** 更新任务的指定字段；扩展字段以完整任务 CAS 保存，未指定字段原样保留。 */
    @Transactional
    public TaskDto updateTask(JsonNode a, String member) {
        TaskDto old = editable(a, member);
        ObjectNode next = json.valueToTree(old);
        copy(a, next, "title", "title"); copy(a, next, "description", "desc");
        copy(a, next, "status", "status"); copy(a, next, "priority", "pr");
        copy(a, next, "owner", "owner"); copy(a, next, "due", "due");
        copy(a, next, "parts", "parts"); copy(a, next, "group_id", "groupId");
        copy(a, next, "steps", "steps");
        if (a.hasNonNull("owner")) requireMember(text(a, "owner", true, 64));
        if (a.hasNonNull("parts")) strings(a, "parts", 100).forEach(this::requireMember);
        if (a.hasNonNull("title")) text(a, "title", true, 200);
        if (a.hasNonNull("description")) text(a, "description", false, 20000);
        if (a.hasNonNull("priority") && !Set.of("P0", "P1", "P2", "P3").contains(a.path("priority").asText()))
            throw new BadRequestException("priority 的取值无效");
        if (a.hasNonNull("status") && !Set.of("待办", "收集中", "进行中", "AI 进行中", "待验证", "已完成").contains(a.path("status").asText()))
            throw new BadRequestException("status 的取值无效");
        date(a, "due", false);
        if (a.hasNonNull("steps")) validateSteps(next.path("steps"));
        if (a.hasNonNull("group_id")) requireGroup(old.project(), a.path("group_id").asText(), member);
        return tasks.replace(old.id(), json.convertValue(next, TaskDto.class), member);
    }

    /** 校验负责人标识，不开放成员账号或认证资料。 */
    public void requireMember(String id) {
        if (members.list().stream().noneMatch(m -> id.equals(m.id()))) throw new BadRequestException("成员不存在：" + id);
    }

    /** 分组必须真实存在于目标项目；空串表示移出分组。 */
    public void requireGroup(String project, String group, String member) {
        if (group != null && !group.isBlank() && nn(projects.get(project, member).taskGroups()).stream().noneMatch(g -> group.equals(g.id())))
            throw new BadRequestException("目标项目没有这个任务分组");
    }

    /** 同步有效标签到共享字典；和调用方业务事务一并提交。 */
    public void ensureTags(List<String> values) {
        if (values != null) NoteTagService.normalize(values).forEach(tags::create);
    }

    private Object createNote(JsonNode a, String member) {
        String id = text(a, "document_id", false, 32);
        if (id == null) id = UUID.randomUUID().toString().replace("-", "");
        if (!id.matches("[a-f0-9]{32}")) throw new BadRequestException("document_id 必须是 32 位无横线的小写 UUID");
        var identity = accounts.findByMemberId(member);
        if (identity == null || !"ACTIVE".equals(identity.getStatus())) throw new NotFoundException("账号不存在");
        jdbc.queryForList("SELECT id FROM account WHERE id = ? FOR UPDATE", identity.getId());
        ObjectNode request = ((ObjectNode) a).deepCopy(); request.remove("document_id");
        String hash = hash(request);
        List<String> receipts = jdbc.query("SELECT request_hash FROM workspace_mcp_note_receipt WHERE member_id = ? AND document_id = ?",
                (rs, row) -> rs.getString(1), member, id);
        if (!receipts.isEmpty()) {
            if (!hash.equals(receipts.get(0))) throw new ConflictException("document_id 已用于另一份内容，请使用新 id");
            DocDto existing = doc(id, member);
            if (!member.equals(existing.author())) throw new NotFoundException("小记不存在");
            return existing;
        }
        if (docs.selectById(id) != null) throw new ConflictException("小记 id 已存在，请先读取，不能覆盖");
        CreateNoteRequest requestDto = new CreateNoteRequest(text(a, "title", false, 200), body(a), content(a),
                tags(a), false, false, false, null);
        ensureTags(requestDto.tags());
        String selectedId = id;
        DocDto result = NoteAiManagedService.withoutAuto(member, () -> knowledge.createNote(selectedId, requestDto, member));
        jdbc.update("INSERT INTO workspace_mcp_note_receipt (member_id, document_id, request_hash) VALUES (?, ?, ?)", member, id, hash);
        return result;
    }

    private Object updateTags(JsonNode a, String member) {
        DocDto current = doc(text(a, "document_id", true, 64), member);
        if (!KnowledgeService.NOTES_KB_ID.equals(current.kbId())) throw new BadRequestException("只有小记支持标签");
        int rev = integer(a, "expected_rev", null, 0, Integer.MAX_VALUE);
        LinkedHashSet<String> values = new LinkedHashSet<>(nn(current.tags()));
        String mode = text(a, "mode", true, 10);
        List<String> supplied = "clear".equals(mode) ? List.of() : requiredTags(a);
        switch (mode) {
            case "add" -> values.addAll(supplied);
            case "remove" -> values.removeAll(supplied);
            case "replace" -> { values.clear(); values.addAll(supplied); }
            case "clear" -> { if (a.has("tags")) throw new BadRequestException("clear 不接受 tags"); values.clear(); }
            default -> throw new BadRequestException("mode 必须为 add/remove/replace/clear");
        }
        List<String> normalized = NoteTagService.normalize(List.copyOf(values));
        ensureTags(normalized);
        return NoteAiManagedService.withoutAuto(member, () -> knowledge.updateDoc(current.id(),
                new PatchDocRequest(null, null, null, null, null, rev, normalized, null)));
    }

    private Object lifecycle(String name, JsonNode a, String member) {
        String id = text(a, "document_id", true, 64);
        docs.selectByIdForUpdate(id);
        DocDto current = doc(id, member);
        if (!Objects.equals(current.rev(), integer(a, "expected_rev", null, 0, Integer.MAX_VALUE)))
            throw new ConflictException("文档已更新，请重新读取后确认归档操作");
        return "archive_document".equals(name) ? knowledge.archiveDoc(id, member) : knowledge.unarchiveDoc(id);
    }

    private TaskDto editable(JsonNode a, String member) {
        TaskDto current = tasks.get(text(a, "task_id", true, 64), member);
        if (!Objects.equals(current.editVersion(), text(a, "expected_edit_version", true, 64)))
            throw new ConflictException("任务已更新，请保留输入并重新读取");
        return current;
    }

    private Object editSubtask(String name, JsonNode a, String member) {
        TaskDto current = editable(a, member);
        List<Step> values = new ArrayList<>(nn(current.subs()));
        if ("reorder_subtasks".equals(name)) {
            List<String> ids = strings(a, "subtask_ids", 5000);
            if (ids == null || ids.size() != values.size() || new HashSet<>(ids).size() != ids.size())
                throw new BadRequestException("subtask_ids 必须包含全部且不重复的子任务 id");
            Map<String, Step> byId = new HashMap<>(); values.forEach(s -> byId.put(s.id(), s));
            values.clear();
            for (String id : ids) { if (!byId.containsKey(id)) throw new BadRequestException("子任务 id 不存在"); values.add(byId.get(id)); }
        } else {
            String id = text(a, "subtask_id", !"create_subtask".equals(name), 128);
            if (id == null) id = "s" + UUID.randomUUID().toString().replace("-", "");
            int index = -1;
            for (int i = 0; i < values.size(); i++) if (id.equals(values.get(i).id())) index = i;
            ObjectNode next;
            if ("create_subtask".equals(name)) {
                if (index >= 0) throw new ConflictException("子任务 id 已存在，请读取后确认");
                next = json.createObjectNode().put("id", id).put("text", text(a, "text", true, 200))
                        .put("done", false).put("createdBy", member).put("createdAt", OffsetDateTime.now().toString());
            } else {
                if (index < 0) throw new NotFoundException("子任务不存在");
                next = json.valueToTree(values.get(index));
            }
            for (String[] field : List.of(new String[]{"text", "text"}, new String[]{"description", "desc"},
                    new String[]{"parent_id", "parentId"}, new String[]{"owner", "owner"},
                    new String[]{"due", "due"}, new String[]{"priority", "pr"})) copy(a, next, field[0], field[1]);
            if (a.has("parent_id")) next.set("parentId", a.get("parent_id"));
            if (a.hasNonNull("text")) text(a, "text", true, 200);
            if (a.hasNonNull("description")) text(a, "description", false, 20000);
            if (a.hasNonNull("owner") && !a.path("owner").asText().isBlank()) requireMember(a.path("owner").asText());
            Step selected = json.convertValue(next, Step.class);
            if (index < 0) values.add(selected); else values.set(index, selected);
        }
        SubtaskTree.validate(values);
        ObjectNode next = json.valueToTree(current); next.set("subs", json.valueToTree(values));
        return tasks.replace(current.id(), json.convertValue(next, TaskDto.class), member);
    }

    private Object moveSubtask(JsonNode a, String member) {
        TaskDto source = tasks.get(text(a, "task_id", true, 64), member);
        TaskDto target = tasks.get(text(a, "target_task_id", true, 64), member);
        if (!Objects.equals(source.subtaskVersion(), text(a, "expected_source_version", true, 64))
                || !Objects.equals(target.subtaskVersion(), text(a, "expected_target_version", true, 64)))
            throw new ConflictException("子任务已更新，请重新读取源任务与目标任务");
        return subtaskMove.move(source.id(), text(a, "subtask_id", true, 128),
                new MoveSubtaskRequest(target.id(), text(a, "parent_id", false, 128), source.subs(), target.subs()), member);
    }

    private Object link(JsonNode a, String member) {
        TaskDto current = editable(a, member);
        DocDto document = doc(text(a, "document_id", true, 64), member);
        LinkedHashSet<String> values = new LinkedHashSet<>(nn(current.docs()));
        switch (text(a, "mode", true, 10)) {
            case "add" -> values.add(document.id());
            case "remove" -> values.remove(document.id());
            default -> throw new BadRequestException("mode 必须为 add/remove");
        }
        ObjectNode next = json.valueToTree(current); next.set("docs", json.valueToTree(values));
        return tasks.replace(current.id(), json.convertValue(next, TaskDto.class), member);
    }

    private Object recordDaily(JsonNode a, String member) {
        ensureTags(tags(a));
        // 新每日范围不隐式开启 AI。显式 false 也防止 afterCommit 重新选择自动阶段。
        return NoteAiManagedService.withoutAuto(member, () -> daily.noteWithoutAi(text(a, "operation_id", true, 36),
                        new TodoOperationService.NoteRequest(text(a, "document_id", true, 32),
                        a.hasNonNull("expected_rev") ? integer(a, "expected_rev", null, 0, Integer.MAX_VALUE) : null,
                        text(a, "expected_task_edit_version", false, 64), date(a, "business_date", true),
                        text(a, "task_id", false, 64), text(a, "title", false, 200), body(a), content(a), tags(a),
                        false, false, false, null), account(member), member));
    }

    private String account(String member) {
        var account = accounts.findByMemberId(member);
        if (account == null || !"ACTIVE".equals(account.getStatus())) throw new NotFoundException("账号不存在或已停用");
        return account.getId();
    }

    private Object listTasks(JsonNode a, AuthenticatedToken token) {
        String member = token.memberId();
        String project = text(a, "project_id", false, 64), owner = text(a, "owner", false, 64);
        if (project != null) projects.requireAccess(project, member);
        String status = text(a, "status", false, 20), keyword = text(a, "keyword", false, 200);
        Predicate<String> dates = range(a);
        List<TaskDto> selected = tasks.list(member).stream()
                .filter(t -> token.permitsProject(t.project()))
                .filter(t -> project == null || project.equals(t.project()))
                .filter(t -> owner == null || owner.equals(t.owner()))
                .filter(t -> !bool(a, "mine_only", true) || member.equals(t.owner()))
                .filter(t -> status == null || ("open".equals(status) ? !closed(t) : status.equals(t.status())))
                .filter(t -> dates.test(t.due()))
                .filter(t -> match(taskText(t), keyword))
                .sorted(Comparator.comparing(TaskDto::id)).toList();
        return page(selected.stream().map(t -> Map.of("id", t.id(), "title", nz(t.title()), "project", nz(t.project()),
                "status", nz(t.status()), "owner", nz(t.owner()), "due", nz(t.due()), "start", nz(t.start()),
                "closed", closed(t), "editVersion", nz(t.editVersion()))).toList(), a);
    }

    private Object listNotes(JsonNode a, AuthenticatedToken token) {
        String member = token.memberId();
        kb(KnowledgeService.NOTES_KB_ID, member);
        String keyword = text(a, "keyword", false, 200);
        List<String> selectedTags = tags(a);
        Predicate<String> dates = range(a);
        List<DocDto> selected = knowledge.listDocCatalog().stream()
                .filter(d -> KnowledgeService.NOTES_KB_ID.equals(d.kbId()))
                .filter(d -> !bool(a, "mine_only", true) || member.equals(d.author()))
                .filter(d -> dates.test(d.created()))
                .filter(d -> selectedTags == null || nn(d.tags()).containsAll(selectedTags))
                .filter(d -> keyword == null || match(docText(d.bodyLoaded() ? d : doc(d.id(), member)), keyword))
                .sorted(Comparator.comparing((DocDto d) -> nz(d.created())).reversed().thenComparing(DocDto::id)).toList();
        return page(selected.stream().map(WorkspaceMcpOperations::summary).toList(), a);
    }

    private Object search(JsonNode a, AuthenticatedToken token) {
        String member = token.memberId();
        String keyword = text(a, "keyword", true, 200);
        List<String> kinds = strings(a, "kinds", 3);
        if (kinds == null) kinds = List.of("task", "note", "document");
        if (!Set.of("task", "note", "document").containsAll(kinds)) throw new BadRequestException("kinds 只支持 task/note/document");
        List<SearchHit> hits = new ArrayList<>();
        if (kinds.contains("task")) for (TaskDto t : tasks.list(member)) {
            if (!token.permitsProject(t.project())) continue;
            if (bool(a, "mine_only", false) && !member.equals(t.owner())) continue;
            String text = taskText(t);
            if (match(text, keyword)) hits.add(new SearchHit("task", t.id(), t.title(), snippet(text, keyword),
                    "/projects/" + t.project() + "?task=" + t.id()));
        }
        Set<String> permitted = new HashSet<>();
        knowledge.listKbs().stream().filter(k -> token.permitsKnowledgeBase(k.id()) && visible(k.id(), member)).forEach(k -> permitted.add(k.id()));
        for (DocDto metadata : knowledge.listDocCatalog()) {
            String kind = KnowledgeService.NOTES_KB_ID.equals(metadata.kbId()) ? "note" : "document";
            if (!kinds.contains(kind) || !permitted.contains(metadata.kbId())
                    || (bool(a, "mine_only", false) && !member.equals(metadata.author()))) continue;
            DocDto d = metadata.bodyLoaded() ? metadata : knowledge.getDoc(metadata.id());
            String text = docText(d);
            if (match(text, keyword)) hits.add(new SearchHit(kind, d.id(), d.title(), snippet(text, keyword),
                    "/kb/" + d.kbId() + "?doc=" + d.id()));
        }
        hits.sort(Comparator.comparing(SearchHit::kind).thenComparing(SearchHit::id));
        return page(hits, a);
    }

    private record SearchHit(String kind, String id, String title, String snippet, String url) {}
    /** 有界分页响应，total 与 hasMore 均来自完整权限过滤后的集合。 */
    public record Page<T>(List<T> items, int total, int offset, int limit, boolean hasMore) {}
    private static <T> Page<T> page(List<T> values, JsonNode a) {
        int offset = integer(a, "offset", 0, 0, Integer.MAX_VALUE), limit = integer(a, "limit", 50, 1, 100);
        int from = Math.min(offset, values.size()), to = (int) Math.min((long) from + limit, values.size());
        return new Page<>(values.subList(from, to), values.size(), offset, limit, to < values.size());
    }
    private static Map<String, Object> summary(DocDto d) {
        return Map.of("id", d.id(), "title", nz(d.title()), "author", nz(d.author()), "created", nz(d.created()),
                "updated", nz(d.updated()), "rev", d.rev() == null ? 0 : d.rev(), "kbId", d.kbId(),
                "folderId", nz(d.folderId()), "tags", nn(d.tags()));
    }
    private DocDto doc(String id, String member) { DocDto d = knowledge.getDoc(id); kb(d.kbId(), member); return d; }
    private void kb(String id, String member) { knowledge.getKb(id); if (!visible(id, member)) throw new NotFoundException("知识库不存在"); }
    private boolean visible(String id, String member) {
        String project = knowledge.projectIdForKb(id);
        if (project == null || project.isBlank()) return true;
        try { projects.requireAccess(project, member); return true; } catch (NotFoundException ex) { return false; }
    }
    private static boolean closed(TaskDto t) { return TaskService.DONE.equals(t.status()) && nn(t.subs()).stream().allMatch(Step::done); }
    private static String taskText(TaskDto t) { return nz(t.title()) + "\n" + nz(t.desc()) + "\n" + nn(t.subs()).stream().map(s -> nz(s.text()) + "\n" + nz(s.desc())).reduce("", (x, y) -> x + "\n" + y); }
    private static String docText(DocDto d) {
        return nz(d.title()) + "\n" + (d.content() != null ? nz(d.content().markdown()) : nn(d.blocks()).stream()
                .map(b -> String.join("\n", nz(b.h()), nz(b.p()), nz(b.call()), String.join("\n", nn(b.ul())), String.join("\n", nn(b.q()))))
                .reduce("", (x, y) -> x + "\n" + y));
    }
    private static boolean match(String value, String term) { return term == null || value.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT)); }
    private static String snippet(String value, String term) { int i = value.toLowerCase(Locale.ROOT).indexOf(term.toLowerCase(Locale.ROOT)); int from = Math.max(0, i - 40); return value.substring(from, Math.min(value.length(), Math.max(0, i) + 120)); }
    private static Predicate<String> range(JsonNode a) {
        LocalDate from = date(a, "from", false), to = date(a, "to", false);
        if (from != null && to != null && from.isAfter(to)) throw new BadRequestException("from 不能晚于 to");
        return raw -> {
            if (from == null && to == null) return true;
            try { LocalDate value = LocalDate.parse(raw.substring(0, 10)); return (from == null || !value.isBefore(from)) && (to == null || !value.isAfter(to)); }
            catch (RuntimeException ex) { return false; }
        };
    }
    private static LocalDate date(JsonNode a, String key, boolean required) {
        String value = text(a, key, required, 10);
        if (value == null || value.isEmpty()) return null;
        try { return LocalDate.parse(value); } catch (RuntimeException ex) { throw new BadRequestException(key + " 必须为有效 yyyy-MM-dd"); }
    }
    private static String text(JsonNode a, String key, boolean required, int max) {
        JsonNode raw = a.get(key);
        if (raw == null || raw.isNull()) { if (required) throw new BadRequestException(key + " 不能为空"); return null; }
        if (!raw.isTextual() || raw.asText().length() > max || (required && raw.asText().isBlank()))
            throw new BadRequestException(key + " 必须是最多 " + max + " 字符的字符串");
        return raw.asText(); // 正文与来源保持原样，不 trim。
    }
    private static int integer(JsonNode a, String key, Integer fallback, int min, int max) {
        JsonNode raw = a.get(key);
        if (raw == null || raw.isNull()) { if (fallback != null) return fallback; throw new BadRequestException("需要 " + key); }
        if (!raw.isIntegralNumber() || !raw.canConvertToInt() || raw.intValue() < min || raw.intValue() > max)
            throw new BadRequestException(key + " 必须是 " + min + " 到 " + max + " 之间的整数");
        return raw.intValue();
    }
    private static boolean bool(JsonNode a, String key, boolean fallback) { return a.hasNonNull(key) ? a.get(key).booleanValue() : fallback; }
    private List<String> strings(JsonNode a, String key, int max) {
        JsonNode raw = a.get(key); if (raw == null || raw.isNull()) return null;
        if (!raw.isArray() || raw.size() > max) throw new BadRequestException(key + " 必须是最多 " + max + " 项的数组");
        List<String> values = new ArrayList<>(); for (JsonNode n : raw) { if (!n.isTextual()) throw new BadRequestException(key + " 必须为字符串数组"); values.add(n.asText()); } return values;
    }
    private List<String> tags(JsonNode a) { List<String> values = strings(a, "tags", 20); return values == null ? null : NoteTagService.normalize(values); }
    private List<String> requiredTags(JsonNode a) { List<String> values = tags(a); if (values == null) throw new BadRequestException("需要 tags 数组"); return values; }
    private List<DocBlockDto> blocks(JsonNode a) {
        JsonNode raw = a.get("blocks"); if (raw == null || raw.isNull()) return null;
        if (!raw.isArray() || raw.size() > 5000 || raw.toString().length() > 1000000) throw new BadRequestException("blocks 大小超限");
        for (JsonNode block : raw) if (!block.isObject()) throw new BadRequestException("blocks 每项必须是对象");
        return json.convertValue(raw, new TypeReference<List<DocBlockDto>>() {});
    }
    private RichDocContentDto content(JsonNode a) { return a.hasNonNull("content") ? json.convertValue(a.get("content"), RichDocContentDto.class) : null; }
    private List<DocBlockDto> body(JsonNode a) {
        if (a.hasNonNull("text") && (a.hasNonNull("blocks") || a.hasNonNull("content"))) throw new BadRequestException("text 与 blocks/content 只能选一种");
        List<DocBlockDto> blocks = blocks(a); String text = text(a, "text", false, 1000000);
        if (blocks == null && text != null) return List.of(new DocBlockDto(null, null, text, null, null));
        if (blocks == null && !a.hasNonNull("content")) return List.of();
        return blocks;
    }
    private static void validateSteps(JsonNode raw) {
        if (!raw.isArray() || raw.size() > 5000) throw new BadRequestException("steps 大小超限");
        for (JsonNode n : raw) if (!n.isObject() || !n.path("text").isTextual() || n.path("text").asText().isBlank()
                || !n.path("done").isBoolean()) throw new BadRequestException("每个步骤需要 text 和布尔 done");
        for (JsonNode n : raw) n.fieldNames().forEachRemaining(key -> {
            if (!Set.of("id", "text", "done").contains(key)) throw new BadRequestException("步骤只接受 id/text/done");
        });
    }
    private static void copy(JsonNode from, ObjectNode to, String key, String target) { if (from.hasNonNull(key)) to.set(target, from.get(key)); }
    private static String nz(String value) { return value == null ? "" : value; }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value; }
    private static <T> List<T> nn(List<T> value) { return value == null ? List.of() : value; }
    private String hash(JsonNode value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(canonical(value)).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("请求摘要失败", ex); }
    }
    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) { ObjectNode next = json.createObjectNode(); TreeSet<String> keys = new TreeSet<>(); value.fieldNames().forEachRemaining(keys::add); keys.forEach(k -> next.set(k, canonical(value.get(k)))); return next; }
        if (value.isArray()) { ArrayNode next = json.createArrayNode(); value.forEach(n -> next.add(canonical(n))); return next; } return value;
    }

    private static void tool(ArrayNode list, String name, String description, boolean readOnly, String fields) {
        ObjectNode schema = JsonNodeFactory.instance.objectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode props = schema.putObject("properties"); ArrayNode required = schema.putArray("required");
        if (!fields.isBlank()) for (String spec : fields.split(" ")) {
            String[] pair = spec.split(":"); String key = pair[0], type = pair[1];
            boolean mandatory = type.endsWith("!"), nullable = type.contains("?");
            String base = switch (type.charAt(0)) { case 's' -> "string"; case 'i' -> "integer"; case 'b' -> "boolean"; case 'a' -> "array"; default -> "object"; };
            ObjectNode prop = props.putObject(key); if (nullable) prop.putArray("type").add(base).add("null"); else prop.put("type", base);
            if ("string".equals(base)) prop.put("maxLength", Set.of("text", "description").contains(key) ? 1000000 : 200);
            if ("array".equals(base)) {
                prop.put("maxItems", Set.of("tags", "kinds").contains(key) ? 20 : 5000);
                prop.putObject("items").put("type", "blocks".equals(key) ? "object" : "string");
            }
            if ("integer".equals(base)) {
                prop.put("minimum", "limit".equals(key) ? 1 : 0);
                if ("limit".equals(key)) prop.put("maximum", 100);
            }
            if ("content".equals(key)) {
                prop.put("additionalProperties", false);
                ObjectNode shape = prop.putObject("properties");
                shape.putObject("version").put("type", "integer").putArray("enum").add(1);
                shape.putObject("document").putArray("type").add("object").add("null");
                shape.putObject("markdown").put("type", "string").put("maxLength", 1000000);
                prop.putArray("required").add("version").add("markdown");
                prop.put("description", "高级 Markdown 正文：{version:1, document:null, markdown:字符串}，也可保留 Tiptap document 对象");
            }
            if ("mode".equals(key)) {
                ArrayNode modes = prop.putArray("enum").add("add").add("remove");
                if ("update_note_tags".equals(name)) modes.add("replace").add("clear");
            }
            if ("schedule_period".equals(key)) prop.putArray("enum").add("MORNING").add("AFTERNOON").add("EVENING").addNull();
            if ("priority".equals(key)) prop.putArray("enum").add("P0").add("P1").add("P2").add("P3");
            if (mandatory) required.add(key);
        }
        String title = switch (name) {
            case "get_workspace_info" -> "读取连接身份与权限"; case "list_members" -> "列出成员";
            case "list_tasks" -> "查询任务"; case "list_notes" -> "查询小记";
            case "list_note_tags" -> "读取小记标签"; case "create_note_tag" -> "创建小记标签";
            case "create_note" -> "创建小记"; case "update_note_tags" -> "修改小记标签";
            case "list_doc_templates" -> "读取文档模板"; case "create_doc_template" -> "创建文档模板";
            case "update_doc_template" -> "修改文档模板"; case "list_archived_documents" -> "查询归档文档";
            case "archive_document" -> "归档文档"; case "unarchive_document" -> "恢复归档文档";
            case "create_subtask" -> "新增子任务"; case "update_subtask" -> "修改子任务";
            case "reorder_subtasks" -> "调整子任务顺序"; case "move_subtask" -> "跨任务移动子任务";
            case "move_task" -> "调整任务层级"; case "add_task_comment" -> "追加任务评论";
            case "update_task_comment" -> "修改本人评论"; case "link_task_document" -> "修改任务文档关联";
            case "search_workspace" -> "全文搜索"; case "get_todo_day" -> "读取每日安排与进展";
            case "list_todo_days" -> "读取日期范围摘要"; case "get_daily_operation" -> "查询操作回执";
            case "schedule_task" -> "保存任务执行安排"; case "record_daily_note" -> "记录每日进展";
            default -> description;
        };
        ObjectNode item = list.addObject().put("name", name).put("title", title).put("description", description);
        item.set("inputSchema", schema);
        item.putObject("annotations").put("readOnlyHint", readOnly).put("destructiveHint", false)
                .put("idempotentHint", readOnly).put("openWorldHint", false);
    }
}
