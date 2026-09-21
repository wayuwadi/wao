package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.TaskDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 按登录账号记录任务写入结果，与任务修改共用 MySQL 事务。 */
@Service
public class TaskWriteOperationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TaskService tasks;
    private static final String TYPE = "TASK_REPLACE";
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    public TaskWriteOperationService(JdbcTemplate jdbc, ObjectMapper json, TaskService tasks) {
        this.jdbc = jdbc;
        this.json = json;
        this.tasks = tasks;
    }

    public record Result(String status, TaskDto task) {}
    private record Row(String type, String taskId, String hash, byte[] result) {}

    /** 同账号同操作只提交一次；同键不同内容拒绝，已提交重放返回原始回执。 */
    @Transactional
    public TaskDto replace(String account, String member, String taskId, String operation, TaskDto request) {
        validate(account, member, taskId, operation);
        if (!Objects.equals(taskId, request.id())) throw new BadRequestException("任务标识与请求路径不一致");
        byte[] payload = encode(request);
        if (payload.length > MAX_BYTES) throw new BadRequestException("任务内容超过 4MiB，请减少单次保存内容");
        String hash = hash(payload);
        // The unique-key write waits for a concurrent operation to commit or roll back.
        // No separate IN_PROGRESS commit: a failed task mutation rolls back its receipt too.
        jdbc.update("""
                INSERT INTO wao_task_write_operation
                    (account_id, operation_id, operation_type, task_id, request_hash)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE operation_id = operation_id
                """, account, operation, TYPE, taskId, hash);
        Row row = rows(account, operation, true).get(0);
        if (!TYPE.equals(row.type()) || !taskId.equals(row.taskId()) || !hash.equals(row.hash()))
            throw new ConflictException("同一保存操作不能更换任务或内容，请先确认原操作结果");
        if (row.result() != null) {
            tasks.get(taskId, member); // Current permissions still apply after a successful earlier write.
            return decode(row.result());
        }
        if (request.editVersion() == null) {
            try {
                tasks.get(taskId, member);
                throw new ConflictException("任务已经存在，请确认原保存结果后读取最新版本");
            } catch (NotFoundException absent) { /* The operation is creating a new task. */ }
        }
        TaskDto saved = tasks.replace(taskId, request, member);
        byte[] result = encode(saved);
        if (result.length > MAX_BYTES) throw new BadRequestException("任务保存回执超过大小限制，草稿仍保留");
        jdbc.update("UPDATE wao_task_write_operation SET result_gzip = ? WHERE account_id = ? AND operation_id = ?",
                compress(result), account, operation);
        return saved;
    }

    /** 只查询当前账号的既有结果；未看到提交记录不等价于确认原请求失败。 */
    @Transactional(readOnly = true)
    public Result find(String account, String member, String taskId, String operation) {
        validate(account, member, taskId, operation);
        List<Row> rows = rows(account, operation, false);
        if (rows.isEmpty()) return new Result("UNCONFIRMED", null);
        Row row = rows.get(0);
        if (!TYPE.equals(row.type()) || !taskId.equals(row.taskId()))
            throw new ConflictException("保存操作与任务不匹配");
        tasks.get(taskId, member);
        return row.result() == null ? new Result("UNCONFIRMED", null) : new Result("COMMITTED", decode(row.result()));
    }

    private List<Row> rows(String account, String operation, boolean lock) {
        return jdbc.query("SELECT operation_type, task_id, request_hash, result_gzip FROM wao_task_write_operation "
                + "WHERE account_id = ? AND operation_id = ?" + (lock ? " FOR UPDATE" : ""),
                (r, n) -> new Row(r.getString(1), r.getString(2), r.getString(3), r.getBytes(4)), account, operation);
    }

    private static void validate(String account, String member, String taskId, String operation) {
        if (account == null || account.isBlank() || member == null || member.isBlank())
            throw new UnauthorizedException("请先登录后保存任务");
        if (taskId == null || taskId.isBlank() || taskId.length() > 32
                || operation == null || !operation.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
            throw new BadRequestException("保存操作标识不合法");
    }

    private byte[] encode(TaskDto value) {
        try { return json.writeValueAsBytes(value); }
        catch (IOException error) { throw new IllegalStateException("无法编码任务保存结果", error); }
    }

    private static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private static byte[] compress(byte[] value) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(out)) { gzip.write(value); }
            return out.toByteArray();
        } catch (IOException error) { throw new IllegalStateException("无法压缩任务保存结果", error); }
    }

    private TaskDto decode(byte[] value) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(value))) {
            byte[] plain = gzip.readNBytes(MAX_BYTES + 1);
            if (plain.length > MAX_BYTES) throw new IOException("Stored receipt exceeds the allowed size");
            return json.readValue(plain, TaskDto.class);
        } catch (IOException error) { throw new IllegalStateException("无法读取任务保存结果", error); }
    }
}
