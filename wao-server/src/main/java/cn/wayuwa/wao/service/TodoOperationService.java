package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.CreateNoteRequest;
import cn.wayuwa.wao.dto.DocBlockDto;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.PatchDocRequest;
import cn.wayuwa.wao.dto.RichDocContentDto;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/** Atomic, account-scoped daily operations and durable idempotency receipts. */
@Service
public class TodoOperationService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TaskMapper taskMapper;
    private final DocMapper docMapper;
    private final TaskService tasks;
    private final KnowledgeService knowledge;
    private final TodoHistoryService history;
    private final NoteAiWorkflowService noteAi;
    private final DailyRoutineService routines;

    public TodoOperationService(JdbcTemplate jdbc, ObjectMapper json, TaskMapper taskMapper, DocMapper docMapper,
            TaskService tasks, KnowledgeService knowledge, TodoHistoryService history, NoteAiWorkflowService noteAi) {
        this(jdbc, json, taskMapper, docMapper, tasks, knowledge, history, noteAi, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TodoOperationService(JdbcTemplate jdbc, ObjectMapper json, TaskMapper taskMapper, DocMapper docMapper,
            TaskService tasks, KnowledgeService knowledge, TodoHistoryService history,
            NoteAiWorkflowService noteAi, DailyRoutineService routines) {
        this.jdbc = jdbc;
        this.json = json;
        this.taskMapper = taskMapper;
        this.docMapper = docMapper;
        this.tasks = tasks;
        this.knowledge = knowledge;
        this.history = history;
        this.noteAi = noteAi;
        this.routines = routines;
    }

    public record ScheduleRequest(String expectedEditVersion, String start, String startAt,
            String endAt, String schedulePeriod) {}
    public record Schedule(String start, String startAt, String endAt, String schedulePeriod) {}
    public record ScheduleResult(String operationId, TaskDto task, Schedule previousSchedule,
            String appliedAt) {}
    public record NoteRequest(String docId, Integer expectedDocRev, String expectedTaskEditVersion,
            LocalDate businessDate, String taskId, String title, List<DocBlockDto> blocks,
            RichDocContentDto content, List<String> tags, Boolean autoTitle, Boolean autoImage,
            Boolean autoPolish, List<String> sourceInputIds, cn.wayuwa.wao.dto.DigitalMemberDtos.IdentitySelection identitySelection) {
        public NoteRequest(String docId,Integer expectedDocRev,String expectedTaskEditVersion,LocalDate businessDate,String taskId,String title,List<DocBlockDto> blocks,RichDocContentDto content,List<String> tags,Boolean autoTitle,Boolean autoImage,Boolean autoPolish,List<String> sourceInputIds){this(docId,expectedDocRev,expectedTaskEditVersion,businessDate,taskId,title,blocks,content,tags,autoTitle,autoImage,autoPolish,sourceInputIds,null);}
        public NoteRequest(String docId,Integer expectedDocRev,String expectedTaskEditVersion,LocalDate businessDate,String taskId,String title,List<DocBlockDto> blocks,RichDocContentDto content,List<String> tags,Boolean autoTitle,Boolean autoImage,Boolean autoPolish){this(docId,expectedDocRev,expectedTaskEditVersion,businessDate,taskId,title,blocks,content,tags,autoTitle,autoImage,autoPolish,null);}
    }
    public record NoteResult(String operationId, DocDto doc, TaskDto task, LocalDate businessDate,
            boolean late) {}
    public record Receipt(String status, String operationId, String type, Object result) {}
    private record ScheduleReceiptRef(String taskId, Schedule previousSchedule, String appliedAt) {}
    private record NoteReceiptRef(String docId, Integer docRev, String taskId,
            LocalDate businessDate, boolean late) {}

    /** Saves only the execution schedule with an edit-version precondition and a stable retry key. */
    @Transactional
    public ScheduleResult schedule(String id, String operationId, ScheduleRequest request,
            String accountId, String memberId) {
        requireIdentity(accountId, memberId);
        requireUuid(operationId);
        requireSchedule(request);
        lockAccount(accountId);
        String hash = hash("SCHEDULE", id, request);
        StoredReceipt previous = stored(accountId, operationId);
        if (previous != null) {
            checkReplay(previous, "SCHEDULE", hash);
            ScheduleReceiptRef ref = read(previous.resultJson(), ScheduleReceiptRef.class);
            return scheduleResult(operationId, ref, memberId);
        }
        tasks.get(id, memberId);
        TaskEntity entity = taskMapper.lockById(id);
        if (entity == null) throw new NotFoundException("任务不存在");
        TaskDto current = tasks.get(id, memberId);
        if (!Objects.equals(request.expectedEditVersion(), current.editVersion()))
            throw new ConflictException("任务已更新，请保留排时草稿并刷新后再试");
        TaskEntity before = history.copy(entity);
        ObjectNode detail = detail(entity);
        Schedule old = new Schedule(entity.getStartDate(), text(detail, "startAt"),
                text(detail, "endAt"), text(detail, "schedulePeriod"));
        putNullable(detail, "startAt", request.startAt());
        putNullable(detail, "endAt", request.endAt());
        putNullable(detail, "schedulePeriod", request.schedulePeriod());
        String encoded = write(detail);
        taskMapper.updateSchedule(id, request.start(), encoded);
        entity.setStartDate(request.start());
        entity.setDetailJson(encoded);
        history.record(before, entity, memberId, "SCHEDULE_CHANGED", operationId);
        ScheduleResult result = new ScheduleResult(operationId, tasks.get(id, memberId), old,
                OffsetDateTime.now(SHANGHAI).toString());
        save(accountId, operationId, "SCHEDULE", hash,
                new ScheduleReceiptRef(id, old, result.appliedAt()));
        return result;
    }

    /** Saves one canonical note, its day context and an optional task.docs link in one transaction. */
    @Transactional
    public NoteResult note(String operationId, NoteRequest request, String accountId, String memberId) {
        return note(operationId, request, accountId, memberId, true);
    }

    /** 工作区普通写入不触发浏览器的自动 AI 续跑，仍保存来源和每日回执。 */
    @Transactional
    public NoteResult noteWithoutAi(String operationId, NoteRequest request, String accountId, String memberId) {
        return note(operationId, request, accountId, memberId, false);
    }

    private NoteResult note(String operationId, NoteRequest request, String accountId, String memberId, boolean allowAi) {
        requireIdentity(accountId, memberId);
        requireUuid(operationId);
        if (request == null || request.businessDate() == null || !StringUtils.hasText(request.docId()))
            throw new BadRequestException("需要小记 id 与业务日期");
        if (request.businessDate().isAfter(LocalDate.now(SHANGHAI)))
            throw new BadRequestException("不能提前记录未来日期的进展");
        if (request.blocks() == null && request.content() == null)
            throw new BadRequestException("需要小记正文");
        lockAccount(accountId);
        String hash = hash("NOTE", null, request);
        StoredReceipt previous = stored(accountId, operationId);
        if (previous != null) {
            checkReplay(previous, "NOTE", hash);
            return noteResult(operationId, read(previous.resultJson(), NoteReceiptRef.class), memberId);
        }
        TaskEntity linkedTask = null;
        if (StringUtils.hasText(request.taskId())) {
            tasks.get(request.taskId(), memberId);
            linkedTask = taskMapper.lockById(request.taskId());
            if (linkedTask == null) throw new NotFoundException("任务不存在");
            if (!StringUtils.hasText(request.expectedTaskEditVersion())
                    || !request.expectedTaskEditVersion().equals(tasks.get(request.taskId(), memberId).editVersion()))
                throw new ConflictException("任务已更新，请确认小记关联后重试");
        }
        DocEntity existing = docMapper.selectById(request.docId());
        if (existing != null) {
            requireNoteVisible(existing.getId(), memberId);
            if (request.expectedDocRev() == null)
                throw new ConflictException("小记已经存在，请先读取最新版本");
        } else if (request.expectedDocRev() != null) {
            throw new ConflictException("小记已删除或不存在，请保留草稿");
        }
        List<LocalDate> contexts = jdbc.query("SELECT business_date FROM wao_todo_day_note_context WHERE doc_id = ? AND member_id = ?",
                (rs, row) -> rs.getDate(1).toLocalDate(), request.docId(), memberId);
        if (!contexts.isEmpty() && !contexts.get(0).equals(request.businessDate()))
            throw new ConflictException("小记已归属另一日期，请刷新后重试");
        CreateNoteRequest noteRequest = new CreateNoteRequest(request.title(), request.blocks(), request.content(),
                request.tags(), request.autoTitle(), request.autoImage(), request.autoPolish(), request.sourceInputIds(), null, null, request.identitySelection());
        DocDto doc = existing == null ? knowledge.createNote(request.docId(), noteRequest, memberId)
                : knowledge.updateDoc(request.docId(), new PatchDocRequest(request.title(), request.blocks(),
                        request.content(), null, null, request.expectedDocRev(), request.tags(), request.sourceInputIds()));
        TaskDto task = null;
        if (linkedTask != null) {
            ObjectNode detail = detail(linkedTask);
            ArrayNode docs = detail.withArray("docs");
            boolean present = false;
            for (JsonNode value : docs) if (request.docId().equals(value.asText())) present = true;
            if (!present) {
                docs.add(request.docId());
                String encoded = write(detail);
                taskMapper.updateDetail(linkedTask.getId(), encoded);
                linkedTask.setDetailJson(encoded);
            }
            history.recordProgress(linkedTask, memberId, operationId, request.businessDate());
            task = tasks.get(linkedTask.getId(), memberId);
        }
        LocalDateTime now = LocalDateTime.now(SHANGHAI);
        boolean late = request.businessDate().isBefore(now.toLocalDate());
        if (contexts.isEmpty()) {
            jdbc.update("""
                    INSERT INTO wao_todo_day_note_context
                      (doc_id, member_id, business_date, created_at, late, task_id)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, request.docId(), memberId, request.businessDate(), now, late,
                    linkedTask == null ? null : linkedTask.getId());
        } else if (linkedTask != null) {
            jdbc.update("UPDATE wao_todo_day_note_context SET task_id = ? WHERE doc_id = ? AND member_id = ?",
                    linkedTask.getId(), request.docId(), memberId);
        }
        NoteResult result = new NoteResult(operationId, doc, task, request.businessDate(), late);
        save(accountId, operationId, "NOTE", hash,
                new NoteReceiptRef(doc.id(), doc.rev(), task == null ? null : task.id(), request.businessDate(), late));
        boolean created = existing == null;
        if (allowAi) afterCommit(() -> {
            try {
                if (created) noteAi.beginIfRequested(memberId, doc, noteRequest);
                else noteAi.refreshAfterSave(memberId, doc.id());
            } catch (RuntimeException ignored) {
                // The note is already committed; AI scheduling is best effort and never changes the receipt.
            }
        });
        return result;
    }

    /** Returns a durable result after rechecking current access, or UNCONFIRMED for an unknown key. */
    public Receipt receipt(String operationId, String accountId, String memberId) {
        requireIdentity(accountId, memberId);
        requireUuid(operationId);
        StoredReceipt stored = stored(accountId, operationId);
        if (stored == null) return new Receipt("UNCONFIRMED", operationId, null, null);
        if ("ROUTINE_RULE".equals(stored.type()) || "ROUTINE_OCCURRENCE".equals(stored.type())) {
            if (routines == null) throw new IllegalStateException("日常规则回执服务未配置");
            DailyRoutineService.Receipt routine = routines.receipt(operationId, accountId, memberId);
            return new Receipt(routine.status(), routine.operationId(), routine.type(), routine.result());
        }
        try {
            if ("SCHEDULE".equals(stored.type())) {
                return new Receipt("COMMITTED", operationId, stored.type(),
                        scheduleResult(operationId, read(stored.resultJson(), ScheduleReceiptRef.class), memberId));
            }
            if ("NOTE".equals(stored.type()))
                return new Receipt("COMMITTED", operationId, stored.type(),
                        noteResult(operationId, read(stored.resultJson(), NoteReceiptRef.class), memberId));
            return new Receipt("UNCONFIRMED", operationId, null, null);
        } catch (NotFoundException ex) {
            return new Receipt("UNCONFIRMED", operationId, null, null);
        }
    }

    private ScheduleResult scheduleResult(String operationId, ScheduleReceiptRef ref, String memberId) {
        return new ScheduleResult(operationId, tasks.get(ref.taskId(), memberId),
                ref.previousSchedule(), ref.appliedAt());
    }

    private NoteResult noteResult(String operationId, NoteReceiptRef ref, String memberId) {
        requireNoteVisible(ref.docId(), memberId);
        DocDto doc = knowledge.getDoc(ref.docId());
        TaskDto task = ref.taskId() == null ? null : tasks.get(ref.taskId(), memberId);
        return new NoteResult(operationId, doc, task, ref.businessDate(), ref.late());
    }

    private void requireNoteVisible(String id, String memberId) {
        DocEntity doc = docMapper.selectById(id);
        if (doc == null || !KnowledgeService.NOTES_KB_ID.equals(doc.getKbId())
                || !Objects.equals(memberId, doc.getAuthorId()) || doc.getDeletedAt() != null
                || doc.getArchivedAt() != null) throw new NotFoundException("小记不存在");
    }

    private void lockAccount(String accountId) {
        List<String> locked = jdbc.query("SELECT id FROM account WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getString(1), accountId);
        if (locked.isEmpty()) throw new NotFoundException("账号不存在");
    }

    private void requireIdentity(String accountId, String memberId) {
        if (!StringUtils.hasText(accountId) || !StringUtils.hasText(memberId))
            throw new NotFoundException("请先登录");
    }

    private void requireUuid(String operationId) {
        try {
            if (!UUID.fromString(operationId).toString().equals(operationId)) throw new IllegalArgumentException();
        } catch (RuntimeException ex) {
            throw new BadRequestException("operationId 必须是规范 UUID");
        }
    }

    private void requireSchedule(ScheduleRequest request) {
        if (request == null || !StringUtils.hasText(request.expectedEditVersion()))
            throw new BadRequestException("需要 expectedEditVersion");
        try {
            if (request.start() != null) LocalDate.parse(request.start());
            if (request.startAt() != null || request.endAt() != null) {
                if (request.startAt() == null || request.endAt() == null || request.start() == null)
                    throw new IllegalArgumentException();
                LocalDateTime startAt = LocalDateTime.parse(request.startAt());
                LocalDateTime endAt = LocalDateTime.parse(request.endAt());
                if (!endAt.isAfter(startAt) || !startAt.toLocalDate().equals(LocalDate.parse(request.start())))
                    throw new IllegalArgumentException();
            }
        } catch (RuntimeException ex) {
            throw new BadRequestException("执行日期或精确时段无效");
        }
        if (request.schedulePeriod() != null) {
            if (!Set.of("MORNING", "AFTERNOON", "EVENING").contains(request.schedulePeriod())
                    || request.start() == null || request.startAt() != null)
                throw new BadRequestException("粗粒度时段只能与无精确时间的执行日期并存");
        }
    }

    private StoredReceipt stored(String accountId, String operationId) {
        return jdbc.query("""
                SELECT operation_type, request_hash, result_json
                  FROM wao_todo_operation_receipt WHERE account_id = ? AND operation_id = ?
                """, (rs, row) -> new StoredReceipt(rs.getString(1), rs.getString(2), rs.getString(3)),
                accountId, operationId).stream().findFirst().orElse(null);
    }

    private record StoredReceipt(String type, String hash, String resultJson) {}

    private void checkReplay(StoredReceipt receipt, String type, String hash) {
        if (!receipt.type().equals(type) || !receipt.hash().equals(hash))
            throw new ConflictException("operationId 已用于另一请求，请创建新的操作");
    }

    private void save(String accountId, String operationId, String type, String hash, Object result) {
        jdbc.update("""
                INSERT INTO wao_todo_operation_receipt
                  (account_id, operation_id, operation_type, request_hash, result_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, accountId, operationId, type, hash, write(result), LocalDateTime.now(SHANGHAI));
    }

    private String hash(String type, String target, Object request) {
        try {
            // 新增可选来源字段不得改变旧客户端已经持久化的保存回执摘要。
            if(request instanceof NoteRequest note && (note.sourceInputIds()==null || note.sourceInputIds().isEmpty())) {
                ObjectNode compatible=json.valueToTree(request);
                compatible.remove("sourceInputIds");
                request=compatible;
            }
            byte[] bytes = json.writeValueAsBytes(List.of(type, target == null ? "" : target, request));
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("无法计算操作摘要", ex);
        }
    }

    private ObjectNode detail(TaskEntity entity) {
        try {
            JsonNode node = json.readTree(entity.getDetailJson() == null ? "{}" : entity.getDetailJson());
            if (node instanceof ObjectNode object) return object;
            throw new IllegalArgumentException();
        } catch (Exception ex) {
            throw new BadRequestException("任务详情无法读取");
        }
    }

    private static String text(ObjectNode node, String key) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static void putNullable(ObjectNode node, String key, String value) {
        if (value == null) node.putNull(key); else node.put(key, value);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("无法序列化日操作", ex); }
    }

    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception ex) { throw new IllegalStateException("无法读取操作回执", ex); }
    }

    private static void afterCommit(Runnable callback) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { callback.run(); }
        });
    }
}
