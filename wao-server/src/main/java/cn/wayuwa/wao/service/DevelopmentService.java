package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.UnauthorizedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 成员隔离的开发协调状态；业务 AI 和旧 Runner 不参与代码执行。 */
@Service
public class DevelopmentService {
    public record Feature(String id, String name) {}
    public record Policy(boolean enabled, long version, int intervalHours, int maxParallel) {}
    public record Worker(boolean online, LocalDateTime lastSeenAt, String message) {}
    public record Decision(String id, long revision, String question, String reason, String status,
            String deliveryStatus, String reply) {}
    public record Run(String id, String featureId, String title, String goal, String status, String phase,
            JsonNode plan, JsonNode agents, JsonNode evidence, String error, Decision decision,
            LocalDateTime createdAt, LocalDateTime updatedAt, long version) {}
    public record Overview(boolean canManage, Policy policy, Worker worker, List<Feature> features, List<Run> runs) {}
    public record Lease(String id, String memberId, String featureId, String token, String worktree, String sessionId) {}
    public static final List<Feature> FEATURES = List.of(new Feature("home", "首页"),
            new Feature("notes", "小记"), new Feature("projects", "项目与任务"),
            new Feature("todo", "待办"), new Feature("calendar", "日历"),
            new Feature("knowledge", "知识库"), new Feature("tianren", "天人一卦"),
            new Feature("automation", "自动化"), new Feature("text-polish", "文本润色"));
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final NotificationSettingsService permissions;
    private final String authorizedMember;
    public DevelopmentService(JdbcTemplate db, ObjectMapper json, NotificationSettingsService permissions) {
        this(db,json,permissions,"");
    }
    @org.springframework.beans.factory.annotation.Autowired
    public DevelopmentService(JdbcTemplate db,ObjectMapper json,NotificationSettingsService permissions,
            @org.springframework.beans.factory.annotation.Value("${wao.development.member-id:}") String authorizedMember) {
        this.db=db;this.json=json;this.permissions=permissions;this.authorizedMember=authorizedMember;
    }
    /** 只读取本人任务；无成员或非管理员不能取得代码执行数据。 */
    @Transactional
    public Overview overview(String member) {
        if(member==null)throw new UnauthorizedException("请先登录");
        if(!allowed(member))return new Overview(false,new Policy(false,0,24,2),new Worker(false,null,"当前成员未授权开发执行"),List.of(),List.of());
        requireManager(member);
        expireDecisions(member);
        return new Overview(true, policy(member), worker(member), FEATURES,
                db.query("SELECT * FROM development_run WHERE member_id=? ORDER BY created_at DESC LIMIT 100",
                        (rs,n)->run(rs), member).stream().map(this::withDecision).toList());
    }
    /** 保存带版本的策略，拒绝覆盖另一个页面尚未获知的修改。 */
    @Transactional
    public Policy savePolicy(String member, Policy request) {
        requireManager(member);
        if (request == null || request.intervalHours()<1 || request.intervalHours()>168
                || request.maxParallel()<1 || request.maxParallel()>3)
            throw new BadRequestException("审视间隔须为 1–168 小时，并行上限须为 1–3");
        policy(member);
        if(db.update("UPDATE development_policy SET enabled=?,interval_hours=?,max_parallel=?,version=version+1,"
                + "next_scan_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE member_id=? AND version=?",
                request.enabled(),request.intervalHours(),request.maxParallel(),member,request.version())!=1)
            throw new ConflictException("自主建设策略已改变，请刷新后保留草稿重新确认");
        return policy(member);
    }
    /** 建立一个功能审视任务；同成员同功能的未收口任务不能重复派工。 */
    @Transactional
    public Run create(String member, String feature) {
        requireManager(member);
        Feature selected=FEATURES.stream().filter(x->x.id().equals(feature)).findFirst()
                .orElseThrow(()->new BadRequestException("功能不在当前浏览器维护范围"));
        String id=id("dev");
        try {
            db.update("INSERT INTO development_run(id,member_id,feature_id,title,goal,status,phase,active_key) "
                    + "VALUES(?,?,?,?,?,'QUEUED','等待功能审视',?)", id,member,feature,
                    selected.name()+"功能审视", "根据当前代码和真实证据判断是否有值得改进的问题",member+":"+feature);
        } catch(DuplicateKeyException ignored) {
            String existing=db.queryForObject("SELECT id FROM development_run WHERE active_key=?",String.class,member+":"+feature);
            return get(member,existing);
        }
        return get(member,id);
    }
    /** 读取本人运行详情，决策也按同一个成员绑定。 */
    public Run get(String member, String id) {
        requireManager(member);
        return db.query("SELECT * FROM development_run WHERE id=? AND member_id=?",(rs,n)->run(rs),id,member)
                .stream().findFirst().map(this::withDecision).orElseThrow(()->new NotFoundException("自主建设任务不存在"));
    }
    /** 失败重试只恢复既有任务，保留方案、会话和交付证据供执行器核对。 */
    @Transactional
    public Run retry(String member, String id, long version) {
        get(member,id);
        if(db.update("UPDATE development_run SET plan_json=CASE WHEN status='PAUSED' THEN NULL ELSE plan_json END,agents_json=CASE WHEN status='PAUSED' THEN NULL ELSE agents_json END,"
                + "status='QUEUED',phase='等待恢复核对',error=NULL,"
                + "version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND member_id=? AND version=? "
                + "AND status IN ('FAILED','PAUSED') AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)",id,member,version)!=1)
            throw new ConflictException("任务已经改变或仍在运行，不能重复重试");
        return get(member,id);
    }
    /** 明确确认或退回一个指定版本；决定和继续排队在同一事务内保存。 */
    @Transactional
    public Run decide(String member, String decisionId, long revision, String action, String reply) {
        requireManager(member);
        if(!List.of("approve","reject").contains(action) || reply==null || reply.isBlank() || reply.length()>2000)
            throw new BadRequestException("请填写明确回复，并选择确认或退回");
        List<Map<String,Object>> rows=db.queryForList("SELECT run_id,status FROM development_decision "
                + "WHERE id=? AND member_id=? AND revision=? AND expires_at>CURRENT_TIMESTAMP FOR UPDATE",decisionId,member,revision);
        if(rows.isEmpty()) throw new ConflictException("决定已过期、方案版本改变或无权回复");
        String run=rows.get(0).get("run_id").toString();
        if(!"PENDING".equals(rows.get(0).get("status"))) return get(member,run);
        String status="approve".equals(action)?"APPROVED":"REJECTED";
        db.update("UPDATE development_decision SET status=?,reply=? WHERE id=? AND status='PENDING'",status,reply.trim(),decisionId);
        if(db.update("UPDATE development_run SET status=?,phase=?,error=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP "
                + "WHERE id=? AND member_id=? AND status='WAITING_DECISION' AND version=?",
                "approve".equals(action)?"QUEUED":"PAUSED", "approve".equals(action)?"决定已确认，等待续跑":"方案已退回",run,member,revision)!=1)
            throw new ConflictException("当前任务方案已改变，未采用这次决定");
        return get(member,run);
    }
    /** 当前策略，首屏读取只创建关闭的默认策略，不启动后台代码执行。 */
    public Policy policy(String member) {
        db.update("INSERT INTO development_policy(member_id) VALUES(?) ON DUPLICATE KEY UPDATE member_id=member_id",member);
        return db.queryForObject("SELECT * FROM development_policy WHERE member_id=?",(rs,n)->
                new Policy(rs.getBoolean("enabled"),rs.getLong("version"),rs.getInt("interval_hours"),rs.getInt("max_parallel")),member);
    }
    /** 执行器以真实心跳报告可用性；离线不伪装为正在执行。 */
    public void heartbeat(String member, String instance, String message) {
        requireManager(member);
        db.update("INSERT INTO development_worker(member_id,instance_id,last_seen_at,message) VALUES(?,?,CURRENT_TIMESTAMP,?) "
                + "ON DUPLICATE KEY UPDATE instance_id=VALUES(instance_id),last_seen_at=CURRENT_TIMESTAMP,message=VALUES(message)",member,instance,message);
    }
    /** 产生幂等功能审视请求；没有可用执行器时不无限积累队列。 */
    @Transactional
    public void schedule(String member) {
        requireManager(member);
        expireDecisions(member);
        Policy p=policy(member);
        if(!p.enabled() || !worker(member).online()) return;
        var rows=db.queryForList("SELECT feature_cursor FROM development_policy WHERE member_id=? AND enabled=TRUE "
                + "AND next_scan_at<=CURRENT_TIMESTAMP FOR UPDATE",member);
        if(rows.isEmpty()) return;
        int cursor=((Number)rows.get(0).get("feature_cursor")).intValue();
        create(member,FEATURES.get(Math.floorMod(cursor,FEATURES.size())).id());
        db.update("UPDATE development_policy SET feature_cursor=feature_cursor+1,next_scan_at=? WHERE member_id=?",
                LocalDateTime.now().plusHours(p.intervalHours()),member);
    }
    /** 只领取显式配置成员的任务，用数据库租约阻止两个主机重复执行。 */
    @Transactional
    public Lease claim(String member) {
        return claim(member,false);
    }
    /** 只读联调仅领取尚未形成计划的任务，正式执行器仍可接续已审视计划。 */
    @Transactional
    public Lease claim(String member,boolean auditOnly) {
        requireManager(member);
        expireDecisions(member);
        policy(member);
        var policies=db.queryForList("SELECT enabled,max_parallel FROM development_policy WHERE member_id=? FOR UPDATE",member);
        if(!Boolean.TRUE.equals(policies.get(0).get("enabled")))return null;
        db.update("UPDATE development_run SET status='FAILED',phase='执行中断，待恢复核对',error='执行器租约过期；重试前核对会话、PR 和发布副作用',"
                + "version=version+1,lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP "
                + "WHERE member_id=? AND lease_until<CURRENT_TIMESTAMP AND status NOT IN ('RELEASED','NO_CHANGE','WAITING_DECISION','PAUSED','FAILED')",member);
        Integer running=db.queryForObject("SELECT COUNT(*) FROM development_run WHERE member_id=? AND lease_until>CURRENT_TIMESTAMP",Integer.class,member);
        if(running>=((Number)policies.get(0).get("max_parallel")).intValue())return null;
        var rows=db.queryForList("SELECT id,feature_id,worktree_path,session_id FROM development_run WHERE member_id=? "
                + "AND status='QUEUED' AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP) "
                +(auditOnly?"AND plan_json IS NULL ":"")+"ORDER BY created_at LIMIT 1 FOR UPDATE",member);
        if(rows.isEmpty()) return null;
        var row=rows.get(0);String token=id("lease");String id=row.get("id").toString();
        db.update("UPDATE development_run SET status='ANALYZING',phase='审视功能与恢复记录',lease_token=?,lease_until=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                token,LocalDateTime.now().plusMinutes(2),id);
        return new Lease(id,member,row.get("feature_id").toString(),token,
                (String)row.get("worktree_path"),(String)row.get("session_id"));
    }
    /** 定期续租；续租失败的旧执行器必须停止而不能回写终态。 */
    public boolean renew(Lease lease) {
        return db.update("UPDATE development_run SET lease_until=? WHERE id=? AND lease_token=? AND lease_until>CURRENT_TIMESTAMP",
                LocalDateTime.now().plusMinutes(2),lease.id(),lease.token())==1;
    }
    /** 写入阶段与标准化产物，所有回写都核对尚未过期的租约。 */
    public void progress(Lease lease, String status, String phase, JsonNode plan, JsonNode agents, JsonNode evidence,
            String worktree, String session) {
        int changed=db.update("UPDATE development_run SET status=?,phase=?,plan_json=COALESCE(?,plan_json),"
                + "agents_json=COALESCE(?,agents_json),evidence_json=COALESCE(?,evidence_json),worktree_path=COALESCE(?,worktree_path),"
                + "session_id=COALESCE(?,session_id),goal=COALESCE(?,goal),title=COALESCE(?,title),version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND lease_token=? AND lease_until>CURRENT_TIMESTAMP",
                status,phase,write(plan),write(agents),write(evidence),worktree,session,
                plan==null?null:plan.path("goal").asText(),plan==null?null:plan.path("title").asText(),lease.id(),lease.token());
        if(changed!=1) throw new ConflictException("开发租约已改变，不能采用旧执行结果");
    }
    /** 本地只读联调保存真实方案后交回队列，由正式执行器接续。 */
    public void deferAfterAudit(Lease lease) {
        progress(lease,"QUEUED","审视已完成，等待正式执行器接续",null,null,null,null,null);
        release(lease);
    }
    /** 大事先保存决定并释放执行槽，不让等待占据正在执行的 Agent。 */
    @Transactional
    public void requestDecision(Lease lease, String question, String reason) {
        progress(lease,"WAITING_DECISION","等待你的明确决定",null,null,null,null,null);
        long revision=db.queryForObject("SELECT version FROM development_run WHERE id=?",Long.class,lease.id());
        db.update("INSERT INTO development_decision(id,run_id,member_id,revision,question,reason,expires_at) VALUES(?,?,?,?,?,?,?)",
                id("decision"),lease.id(),lease.memberId(),revision,question,reason,LocalDateTime.now().plusDays(7));
        release(lease);
    }
    /** 终态保留真实错误与成果；未上线不写成成功。 */
    public void finish(Lease lease, String status, String phase, String error, JsonNode evidence) {
        if(!List.of("RELEASED","NO_CHANGE","FAILED").contains(status)) throw new BadRequestException("不支持的收口状态");
        progress(lease,status,phase,null,null,evidence,null,null);
        db.update("UPDATE development_run SET error=?,active_key=CASE WHEN status IN ('RELEASED','NO_CHANGE') THEN NULL ELSE active_key END "
                + "WHERE id=? AND lease_token=?",error,lease.id(),lease.token());
        release(lease);
    }
    /** 已发送决定按具体消息与应用查询，裸同意不能匹配最近任务。 */
    public Map<String,Object> decisionMessage(String decision, String message, String app, String open) {
        String sql="SELECT * FROM development_decision WHERE message_id=? AND app_id=? AND open_id=?";
        var rows=decision==null?db.queryForList(sql,message,app,open):db.queryForList(sql+" AND id=?",message,app,open,decision);
        return rows.stream().findFirst().orElse(null);
    }
    /** 返回投递等待项，不包含执行凭据。 */
    public List<Map<String,Object>> pendingDecisions() {
        return db.queryForList("SELECT * FROM development_decision WHERE status='PENDING' AND delivery_status IN ('PENDING','FAILED','SENDING') "
                + "AND available_at<=CURRENT_TIMESTAMP AND expires_at>CURRENT_TIMESTAMP AND send_attempts<5 LIMIT 10");
    }
    /** 准备投递时绑定应用和批准人；仍由稳定 uuid 对发送去重。 */
    public boolean delivering(String id, String app, String open) {
        return db.update("UPDATE development_decision SET app_id=?,open_id=?,delivery_status='SENDING',send_attempts=send_attempts+1,available_at=? "
                + "WHERE id=? AND status='PENDING' AND available_at<=CURRENT_TIMESTAMP",app,open,LocalDateTime.now().plusMinutes(5),id)==1;
    }
    /** 只有飞书返回真实消息 ID 才记录已发送。 */
    public void delivered(String id, String message) {
        db.update("UPDATE development_decision SET message_id=?,delivery_status='SENT' WHERE id=? AND delivery_status='SENDING'",message,id);
    }
    /** 投递失败仍保留决定，浏览器可以明确回复，不用失败冒充已送达。 */
    public void deliveryFailed(String id) {
        db.update("UPDATE development_decision SET send_attempts=send_attempts+CASE WHEN delivery_status='SENDING' THEN 0 ELSE 1 END,delivery_status='FAILED',available_at=? WHERE id=? AND status='PENDING' AND delivery_status<>'SENT'",
                LocalDateTime.now().plusMinutes(5),id);
    }
    /** 只查询精确运行是否已批准，不继承其他运行的决定。 */
    public boolean approved(String run) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT COUNT(*)>0 FROM development_decision WHERE run_id=? AND status='APPROVED'",Boolean.class,run));
    }
    private void release(Lease lease) {
        db.update("UPDATE development_run SET lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?",lease.id(),lease.token());
    }
    private void expireDecisions(String member) {
        db.update("UPDATE development_run SET status='PAUSED',phase='决定已过期，等待重新审视',error='决定已过期，请重试创建新的方案与决定',version=version+1,updated_at=CURRENT_TIMESTAMP "
                +"WHERE member_id=? AND status='WAITING_DECISION' AND id IN (SELECT run_id FROM development_decision WHERE member_id=? AND status='PENDING' AND expires_at<=CURRENT_TIMESTAMP AND revision=development_run.version)",member,member);
        db.update("UPDATE development_decision SET status='EXPIRED' WHERE member_id=? AND status='PENDING' AND expires_at<=CURRENT_TIMESTAMP",member);
    }
    private Worker worker(String member) {
        return db.query("SELECT * FROM development_worker WHERE member_id=?",(rs,n)-> {
            LocalDateTime seen=rs.getTimestamp("last_seen_at").toLocalDateTime();
            return new Worker(seen.isAfter(LocalDateTime.now().minusSeconds(90)),seen,rs.getString("message"));
        },member).stream().findFirst().orElse(new Worker(false,null,"开发执行器未连接；任务保留在队列中"));
    }
    private Run run(ResultSet rs) throws SQLException {
        String id=rs.getString("id");
        return new Run(id,rs.getString("feature_id"),rs.getString("title"),rs.getString("goal"),rs.getString("status"),rs.getString("phase"),
                read(rs.getString("plan_json")),array(rs.getString("agents_json")),read(rs.getString("evidence_json")),rs.getString("error"),null,
                rs.getTimestamp("created_at").toLocalDateTime(),rs.getTimestamp("updated_at").toLocalDateTime(),rs.getLong("version"));
    }
    private Run withDecision(Run run) {
        Decision decision=db.query("SELECT * FROM development_decision WHERE run_id=? ORDER BY revision DESC LIMIT 1",(d,n)->
                new Decision(d.getString("id"),d.getLong("revision"),d.getString("question"),d.getString("reason"),d.getString("status"),
                        d.getString("delivery_status"),d.getString("reply")),run.id()).stream().findFirst().orElse(null);
        return new Run(run.id(),run.featureId(),run.title(),run.goal(),run.status(),run.phase(),run.plan(),run.agents(),run.evidence(),
                run.error(),decision,run.createdAt(),run.updatedAt(),run.version());
    }
    private JsonNode read(String value) {try{return value==null?null:json.readTree(value);}catch(Exception ex){throw new IllegalStateException("开发产物无法读取");}}
    private JsonNode array(String value) {return value==null?json.createArrayNode():read(value);}
    private String write(JsonNode value) {return value==null?null:value.toString();}
    private boolean allowed(String member) {return member!=null&&(member.equals(authorizedMember)||permissions.canManageSystem(member));}
    private void requireManager(String member) {if(!allowed(member))throw new UnauthorizedException("只有显式授权的成员或已登录管理员可以维护本人自主建设");}
    private static String id(String prefix) {return prefix+UUID.randomUUID().toString().replace("-","");}
}
