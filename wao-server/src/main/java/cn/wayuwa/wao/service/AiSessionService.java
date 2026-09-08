package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.dto.*;
import cn.wayuwa.wao.mapper.AiTaskRunMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import cn.wayuwa.wao.common.NotFoundException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 云端会话目录；实际调度与精确 session 恢复仍由 AiRun 和既有 Runner 负责。 */
@Service
public class AiSessionService {
    private final JdbcTemplate jdbc;
    private final ProjectService projects;
    private final AiTaskRunMapper runs;
    private final TaskMapper tasks;
    private final AiRunService ai;

    public AiSessionService(JdbcTemplate jdbc, ProjectService projects, AiTaskRunMapper runs,
            TaskMapper tasks, AiRunService ai) {
        this.jdbc = jdbc; this.projects = projects; this.runs = runs; this.tasks = tasks; this.ai = ai;
    }

    public record Page(List<AiSessionDto> items, long total, int page, int size) {}
    public record Detail(AiRunDto run, AiSessionPreference preference, String prompt) {}

    /** 在数据库分页搜索所有可见项目的 AiRun，包含排队、活动和已结束的历史会话。 */
    public Page list(String member, String query, String status, String engine, boolean archived, int page) {
        var visible = projects.visibleProjectIds(member);
        page = Math.max(1, page);
        if (visible.isEmpty()) return new Page(List.of(), 0, page, 25);
        var args = new ArrayList<Object>(); args.add(member); args.addAll(visible); args.add(archived);
        String from = " FROM ai_task_run r LEFT JOIN task t ON t.id=r.task_id "
                + "LEFT JOIN project p ON p.id=r.project_id LEFT JOIN ai_runner_node n ON n.id=r.runner_id "
                + "LEFT JOIN ai_session_preference s ON s.run_id=r.id AND s.member_id=? WHERE r.project_id IN ("
                + String.join(",", Collections.nCopies(visible.size(), "?")) + ") AND COALESCE(s.archived,FALSE)=?";
        if (status != null && !status.isBlank()) {
            if (status.equals("active")) from += " AND r.status IN ('queued','claimed','running')";
            else if (Set.of("failed", "succeeded", "cancelled").contains(status)) {
                from += " AND r.status=?"; args.add(status);
            } else throw new BadRequestException("未知会话状态");
        }
        if (engine != null && !engine.isBlank()) {
            if (!Set.of("codex", "claude", "qoder").contains(engine)) throw new BadRequestException("未知 AI 工具");
            from += " AND r.engine=?"; args.add(engine);
        }
        if (query != null && !query.isBlank()) {
            if (query.length() > 160) throw new BadRequestException("搜索内容最多 160 字");
            from += " AND (LOCATE(?, COALESCE(s.title,t.title,''))>0 OR LOCATE(?,COALESCE(t.title,''))>0 OR LOCATE(?,COALESCE(r.session_id,''))>0 OR r.id=?)";
            args.add(query.trim()); args.add(query.trim()); args.add(query.trim()); args.add(query.trim());
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, args.toArray());
        args.add(25); args.add((long)(page-1)*25);
        var items = jdbc.query("SELECT r.id,r.task_id,COALESCE(NULLIF(s.title,''),t.title,'历史任务') AS title,"
                + "p.name AS project_name,r.engine,r.requested_model,r.status,r.session_id,n.name AS runner_name,"
                + "LEFT(r.latest_progress,240) AS preview,COALESCE(r.progress_updated_at,r.created_at) AS updated_at,"
                + "COALESCE(s.pinned,FALSE) AS pinned,COALESCE(s.archived,FALSE) AS archived" + from
                + " ORDER BY pinned DESC,updated_at DESC,r.id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new AiSessionDto(rs.getString("id"),rs.getString("task_id"),rs.getString("title"),
                        rs.getString("project_name"),rs.getString("engine"),rs.getString("requested_model"),
                        rs.getString("status"),rs.getString("session_id"),rs.getString("runner_name"),
                        rs.getString("preview"),rs.getString("updated_at"),rs.getBoolean("pinned"),rs.getBoolean("archived")),
                args.toArray());
        return new Page(items,total == null ? 0 : total,page,25);
    }

    /** 先验证项目权限，再读取会话及初始任务；不允许通过猜测 runId 跨项目读取。 */
    public Detail detail(String member, String id) {
        requireVisible(member,id);
        var run = ai.runDetail(id);
        var preference = jdbc.query("SELECT title,pinned,archived FROM ai_session_preference WHERE member_id=? AND run_id=?",
                (rs,row) -> new AiSessionPreference(rs.getString(1),rs.getBoolean(2),rs.getBoolean(3)),member,id);
        var task = tasks.selectById(run.taskId());
        return new Detail(run,preference.isEmpty() ? new AiSessionPreference(null,false,false) : preference.get(0),
                task == null ? "原任务已移除，可继续回看已保存的会话。" : task.getDescription());
    }

    /** 归档只整理当前成员的列表，不停止运行，不删除服务器会话。 */
    public AiSessionPreference save(String member, String id, AiSessionPreference preference) {
        requireVisible(member,id);
        String title = preference.title() == null || preference.title().isBlank() ? null : preference.title().trim();
        if (title != null && title.length() > 160) throw new BadRequestException("会话名称最多 160 字");
        jdbc.update("INSERT INTO ai_session_preference(member_id,run_id,title,pinned,archived) VALUES (?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE title=VALUES(title),pinned=VALUES(pinned),archived=VALUES(archived)",
                member,id,title,preference.pinned(),preference.archived());
        return new AiSessionPreference(title,preference.pinned(),preference.archived());
    }

    /** 读写会话共用权限边界，与任务项目可见性保持一致。 */
    public void requireVisible(String member, String id) {
        var run = runs.selectById(id);
        if (run == null) throw new NotFoundException("会话不存在");
        projects.get(run.getProjectId(),member);
    }
}
