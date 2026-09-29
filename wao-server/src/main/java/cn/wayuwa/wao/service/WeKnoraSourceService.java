package cn.wayuwa.wao.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 显式字段白名单的知识导出；私有记录始终限定到配置者。 */
@Service
public class WeKnoraSourceService {
    public record Source(String key, String module, String title, String url, String content, String hash,
                         String originalUrl) { }
    public record Module(String id, String label, String scope, long count) { }
    record Definition(String id, String label, String scope, String select, String from, String where,
                      String route, boolean personal) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    static final int PART_SIZE = 15000;
    private static final String SHARED = "共享业务内容";
    private static final String OWN = "仅配置者本人";
    static final List<Definition> DEFINITIONS = List.of(
        d("docs", "知识库文档与随手记", SHARED, "d.id,d.title,COALESCE(NULLIF(d.content_markdown,''),d.blocks_json) AS 正文,d.tags_json AS 标签", "doc d JOIN kb k ON k.id=d.kb_id", "d.deleted_at IS NULL AND k.deleted_at IS NULL AND (d.author_id=? OR k.perm <> '仅自己')", "/s/d/", true),
        d("project-memory", "项目工程记忆", SHARED, "id,title,content AS 正文,category AS 分类,evidence_links_json AS 证据", "project_memory", "EXISTS (SELECT 1 FROM project p WHERE p.id=project_memory.project_id AND (p.owner_id=? OR JSON_CONTAINS(p.detail_json,JSON_QUOTE(?),'$.members')))", "/projects?memory=", false),
        d("projects", "项目", SHARED, "id,name AS title,goal AS 目标,status AS 状态,detail_json AS 详情", "project", "owner_id=? OR JSON_CONTAINS(detail_json,JSON_QUOTE(?),'$.members')", "/projects/", true),
        d("tasks", "任务与子任务", SHARED, "id,title,description AS 描述,status AS 状态,detail_json AS 详情,due_date AS 截止日期", "task", "owner_id=? OR created_by=? OR EXISTS (SELECT 1 FROM project p WHERE p.id=task.project_id AND (p.owner_id=? OR JSON_CONTAINS(p.detail_json,JSON_QUOTE(?),'$.members')))", "/tasks/", false),
        d("ideas", "点子", SHARED, "id,title,raw_idea AS 原始想法,problem_text AS 问题,target_outcome AS 预期结果,stage AS 阶段,detail_json AS 详情", "idea", "1=1", "/ideas/", false),
        d("assistant", "AI 助手对话（按会话整理）", OWN, "c.id,c.title,m.role AS 发言角色,m.content AS 正文,m.created_at AS 时间", "ai_assistant_message m JOIN ai_assistant_conversation c ON c.id=m.conversation_id", "c.member_id=?", "/ai-assistant?message=", true),
        d("chat", "共享聊天（按天整理）", SHARED, "DATE(created_at) AS id,CONCAT('聊天 · ',DATE(created_at)) AS title,created_at AS 时间,member_id AS 发言人,content AS 正文,image_name AS 图片名称", "shared_chat_message", "deleted=0", "/chat?message=", false),
        d("reviews", "人生回顾", OWN, "id,CONCAT(period_type,' · ',period_key) AS title,report_json AS 回顾", "life_review", "member_id=?", "/life-review?id=", true),
        d("polish", "文字润色", OWN, "id,CONCAT('文字润色 · ',created_at) AS title,original_text AS 原文,polished_text AS 润色结果", "text_polish_record", "member_id=?", "/text-polish?id=", true),
        d("dates", "家庭重要日期", OWN, "id,name AS title,event_date AS 日期,calendar_type AS 历法,note AS 备注", "family_important_date", "member_id=?", "/family-dates?id=", true),
        d("drawings", "画图中的文本", OWN, "id,title,scene_json AS 内容", "drawing_file", "owner_member_id=?", "/drawings/", true),
        d("iterations", "点子迭代与结果", SHARED, "i.id,CONCAT(d.title,' · 迭代') AS title,i.objective AS 目标,i.acceptance_criteria AS 验收条件,i.feedback AS 反馈,i.result_summary AS 结果", "idea_iteration i JOIN idea d ON d.id=i.idea_id", "1=1", "/ideas?iteration=", false),
        d("skills", "已发布 Skill 正文", SHARED, "s.id,s.display_name AS title,v.skill_md AS 正文,v.changelog AS 变更记录", "skill s JOIN skill_version v ON v.id=s.published_version_id", "v.state='PUBLISHED' AND s.status='PUBLISHED'", "/wao/skills?id=", false),
        d("tianren", "天人日课记录", OWN, "id,CONCAT('天人日课 · ',reading_date) AS title,lines_json AS 日课,primary_number AS 本卦,relating_number AS 变卦", "tianren_daily_reading", "member_id=?", "/tianren?id=", true),
        d("ai-runs", "AI 执行对话与结果", OWN, "r.id,COALESCE(t.title,r.id) AS title,p.kind AS 类型,p.content AS 正文,p.created_at AS 时间", "ai_run_progress p JOIN ai_task_run r ON r.id=p.run_id LEFT JOIN task t ON t.id=r.task_id", "r.requested_by=?", "/?console=runs&run=", true),
        d("pages", "托管页面文本", SHARED, "id,title,html_content AS 正文", "hosted_page", "created_by=? OR EXISTS (SELECT 1 FROM project p WHERE p.id=hosted_page.project_id AND (p.owner_id=? OR JSON_CONTAINS(p.detail_json,JSON_QUOTE(?),'$.members')))", "/pages/", false)
    );
    public WeKnoraSourceService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    private static Definition d(String id,String label,String scope,String select,String from,String where,String route,boolean personal) {
        return new Definition(id,label,scope,select,from,where,route,personal);
    }
    /** 查询每类真实记录数，不读取正文，不调用模型。 */
    public List<Module> catalog(String owner) {
        return DEFINITIONS.stream().map(d -> new Module(d.id,d.label,d.scope,
            jdbc.queryForObject("SELECT COUNT(*) FROM " + d.from + " WHERE " + d.where, Long.class, args(d,owner)))).toList();
    }
    /** 获取完整且有确定顺序的源清单；任何查询失败整体失败，禁止据此删除远端。 */
    public List<Source> snapshot(String owner, List<String> modules) {
        return snapshot(owner, modules, null);
    }
    /** 只读取当前成员可见的指定文档，供编辑页面批量核对服务器版本。 */
    public List<Source> documents(String owner, List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id.length() > 64))
            throw new IllegalArgumentException("每次可查询 1–100 篇文档");
        return snapshot(owner, List.of("docs"), ids);
    }
    private List<Source> snapshot(String owner, List<String> modules, List<String> ids) {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("缺少同步所属成员");
        validateModules(modules);
        List<Source> result = new ArrayList<>();
        for (Definition d : DEFINITIONS) {
            if (!modules.contains(d.id)) continue;
            Map<String,StringBuilder> bodies=new LinkedHashMap<>();
            Map<String,String> titles=new HashMap<>();
            Map<String,String> urls=new HashMap<>();
            String select=d.select, where=d.where;
            List<Object> parameters=new ArrayList<>(Arrays.asList(args(d,owner)));
            if (d.id.equals("docs")) select += ",d.kb_id AS source_kb_id";
            if (ids != null) {
                where="("+where+") AND d.id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")";
                parameters.addAll(ids);
            }
            String order=switch(d.id) {case "assistant" -> "1,m.created_at,m.id";case "chat" -> "1,created_at,shared_chat_message.id";case "ai-runs" -> "1,p.created_at,p.id";default -> "1";};
            jdbc.query("SELECT " + select + " FROM " + d.from + " WHERE " + where + " ORDER BY "+order+" LIMIT 50001", rs -> {
                String id = rs.getString(1), title = Objects.toString(rs.getString(2), d.label);
                titles.put(id,title);
                if (d.id.equals("docs")) urls.put(id,"/kb/"+java.net.URLEncoder.encode(rs.getString("source_kb_id"),StandardCharsets.UTF_8)+"?doc="+java.net.URLEncoder.encode(id,StandardCharsets.UTF_8));
                StringBuilder body=bodies.computeIfAbsent(id,k->new StringBuilder());
                for (int c=3;c<=rs.getMetaData().getColumnCount();c++) {
                    if ("source_kb_id".equalsIgnoreCase(rs.getMetaData().getColumnLabel(c))) continue;
                    String text = render(Objects.toString(rs.getString(c), ""));
                    if (!text.isBlank()) body.append("\n## ").append(rs.getMetaData().getColumnLabel(c)).append("\n").append(text).append('\n');
                }
                if(rs.getRow()>50000 || body.length()>5_000_000)throw new IllegalStateException("单类来源超过读取上限，已停止；请缩小范围或扩展分批读取");
            }, parameters.toArray());
            bodies.forEach((id,body)->result.addAll(parts(d.id,id,titles.get(id),urls.getOrDefault(id,""),body.toString())));

        }
        return result;
    }
    /** 拒绝未登记的模块，不能通过客户端传入表名或 SQL。 */
    public static void validateModules(List<String> modules) {
        if (modules == null || modules.isEmpty() || modules.stream().anyMatch(id -> DEFINITIONS.stream().noneMatch(d -> d.id.equals(id))))
            throw new IllegalArgumentException("请选择已支持的同步范围");
    }
    private static Object[] args(Definition d,String owner) { return java.util.Collections.nCopies((int)d.where.chars().filter(c -> c=='?').count(), owner).toArray(); }
    /** 稳定分段保留全部正文，指纹不包含导出时刻。 */
    static List<Source> parts(String module,String id,String title,String url,String text) {
        List<Source> result = new ArrayList<>();
        String originalUrl=url;
        String clean = redact(text);
        for (int pos=0,part=1; pos<Math.max(1,clean.length());part++) {
            int end=Math.min(clean.length(),pos+PART_SIZE);
            if (end<clean.length() && end>pos && Character.isHighSurrogate(clean.charAt(end-1))) end--;
            String key=module+":"+id+":"+part;
            int titleEnd=Math.min(title.length(),160);
            if(titleEnd>0 && Character.isHighSurrogate(title.charAt(titleEnd-1)))titleEnd--;
            String label=title.substring(0,titleEnd) + (clean.length()>PART_SIZE ? " · 第"+part+"部分" : "");
            String marker="wao-sync:"+hash(key).substring(0,24);
            url="https://wao.wayuwa.cn/apps/weknora?tab=sync&source="+java.net.URLEncoder.encode(key,StandardCharsets.UTF_8);
            String content="# "+label+"\n\n来源：[在 WAO 查看]("+url+")\n\n同步标识："+marker+"\n\n"+clean.substring(pos,end);
            result.add(new Source(key,module,label+" ["+marker+"]",url,content,hash(content),originalUrl));
            if (end==clean.length()) break;
            pos=end;
        }
        return result;
    }
    String render(String raw) {
        try { return flatten(json.readTree(raw)); } catch (Exception ignored) {
            return raw.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "")
                .replaceAll("(?s)<[^>]+>", "");
        }
    }
    private String flatten(JsonNode n) {
        if (n==null || n.isNull()) return "";
        if (n.isTextual()) return n.asText();
        if (n.isValueNode()) return n.asText();
        StringBuilder out=new StringBuilder();
        if (n.isArray()) n.forEach(v -> out.append(flatten(v)).append('\n'));
        else n.fields().forEachRemaining(e -> {
            String k=e.getKey();
            if (k.matches("(?i).*(password|secret|token|api.?key|cipher|nonce|cookie|authorization|base64|dataURL).*")) return;
            if (Set.of("id","type","attrs","marks","style","x","y","width","height","seed","version","versionNonce","updated","groupIds","boundElements").contains(k)) return;
            String value=flatten(e.getValue());
            if (!value.isBlank()) out.append(Set.of("text","content","children","blocks","elements").contains(k) ? "" : k+": ").append(value).append('\n');
        });
        return out.toString();
    }
    static String redact(String text) {
        return text.replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]{12,}", "$1[已隐藏]")
            .replaceAll("\\bsk-[A-Za-z0-9_-]{16,}", "[已隐藏 API Key]")
            .replaceAll("(?i)([?&](?:token|api_key|key|secret|password)=)[^&\\s)]+", "$1[已隐藏]");
    }
    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
