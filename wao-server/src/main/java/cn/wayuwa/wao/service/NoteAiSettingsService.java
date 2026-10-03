package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 小记 AI 配置的唯一读写入口，沿用既有成员偏好键。 */
@Service
public class NoteAiSettingsService {
    public static final String DEFAULTS = "note-ai-defaults-v1";
    public static final List<String> KEYS = List.of(DEFAULTS, "note-ai-model-v1", "note-title-prompt-v1",
            "article-overview-image-prompt-v1", "text-polish.prompt.v1", "note-polish-prompt-v1");
    private final JdbcTemplate db;
    private final MemberPreferenceMapper preferences;
    private final ObjectMapper json;
    public NoteAiSettingsService(JdbcTemplate db, MemberPreferenceMapper preferences, ObjectMapper json) {
        this.db = db; this.preferences = preferences; this.json = json;
    }
    public record Settings(boolean title, boolean polish, boolean image, String executionMode, int maxConcurrent,
            String model, String titlePrompt, String imagePrompt, String polishPrompt,
            long titleEpoch, long imageEpoch, long polishEpoch) {
        public boolean enabled(String stage) { return switch(stage) { case "title" -> title; case "image" -> image; default -> polish; }; }
        public long epoch(String stage) { return switch(stage) { case "title" -> titleEpoch; case "image" -> imageEpoch; default -> polishEpoch; }; }
        public int capacity() { return "serial".equals(executionMode) ? 1 : maxConcurrent; }
    }
    public record View(Settings settings, String version, boolean configured, String availability) { }
    public record Save(Settings settings, String expectedVersion) { }
    /** 读取成功且无配置时才应用默认；坏配置作为真实错误报告。 */
    @Transactional(readOnly=true,noRollbackFor=RuntimeException.class)
    public View get(String member) {
        requireMember(member);
        Map<String,String> values = new LinkedHashMap<>();
        for (String key : KEYS) values.put(key, preferences.find(member,key));
        return parse(values);
    }
    /** 纯解析用于配置完整性测试，不修改历史配置。 */
    public View parse(Map<String,String> values) {
        String raw = values.get(DEFAULTS);
        JsonNode node;
        try { node = raw == null || raw.isBlank() ? json.createObjectNode() : json.readTree(raw); }
        catch (Exception error) { throw new BadRequestException("小记 AI 设置格式异常，请在设置中重新保存；正文可正常保存"); }
        if (!node.isObject()) throw new BadRequestException("小记 AI 设置格式异常");
        for (String key : List.of("title","image","polish"))
            if (node.has(key) && !node.get(key).isBoolean()) throw new BadRequestException("小记 AI 开关格式异常");
        String canonical = values.get("text-polish.prompt.v1");
        Settings s = new Settings(node.path("title").asBoolean(true),node.path("polish").asBoolean(true),node.path("image").asBoolean(true),
            "serial".equals(node.path("executionMode").asText()) ? "serial" : "parallel",
            Math.max(1,Math.min(5,node.path("maxConcurrent").asInt(3))), text(values.get("note-ai-model-v1")),
            text(values.get("note-title-prompt-v1")),text(values.get("article-overview-image-prompt-v1")),
            text(canonical == null || canonical.isBlank() ? values.get("note-polish-prompt-v1") : canonical),
            node.path("titleEpoch").asLong(),node.path("imageEpoch").asLong(),node.path("polishEpoch").asLong());
        return new View(s,hash(write(values)), raw != null && !raw.isBlank(), "沿用服务主机已获准 AI 配置；实际任务失败会保留原因");
    }
    /** 锁定成员以保护多设备设置与工作流并发占用。 */
    public void lockMember(String member) {
        requireMember(member);
        if (db.queryForList("SELECT id FROM member WHERE id=? FOR UPDATE",member).isEmpty()) throw new NotFoundException("成员不存在");
    }
    /** 聚合 CAS 保存全部配置；关闭代次只能由服务端递增。 */
    @Transactional
    public View save(String member, Save request) {
        lockMember(member);
        View old = get(member);
        if (request == null || request.settings() == null || !Objects.equals(old.version(),request.expectedVersion()))
            throw new ConflictException("小记 AI 设置已在其他设备更新，请重新读取；未保存设置仍保留");
        Settings next = request.settings();
        if (next.maxConcurrent()<1 || next.maxConcurrent()>5 || !List.of("serial","parallel").contains(next.executionMode()))
            throw new BadRequestException("执行方式或并发上限无效");
        for (String value : List.of(text(next.model()),text(next.titlePrompt()),text(next.imagePrompt()),text(next.polishPrompt())))
            if (value.length()>4000) throw new BadRequestException("设置内容最多 4000 字符");
        ObjectNode defaults;
        try { String raw=preferences.find(member,DEFAULTS); defaults=raw==null||raw.isBlank()?json.createObjectNode():(ObjectNode)json.readTree(raw); }
        catch (Exception e) { throw new BadRequestException("已有设置格式异常"); }
        defaults.put("title",next.title()).put("polish",next.polish()).put("image",next.image())
            .put("executionMode",next.executionMode()).put("maxConcurrent",next.maxConcurrent());
        for(String stage:List.of("title","image","polish")) defaults.put(stage+"Epoch",old.settings().epoch(stage)+(old.settings().enabled(stage)&&!next.enabled(stage)?1:0));
        preferences.upsert(member,DEFAULTS,write(defaults));
        preferences.upsert(member,"note-ai-model-v1",text(next.model()));
        preferences.upsert(member,"note-title-prompt-v1",text(next.titlePrompt()));
        preferences.upsert(member,"article-overview-image-prompt-v1",text(next.imagePrompt()));
        preferences.upsert(member,"text-polish.prompt.v1",text(next.polishPrompt()));
        return get(member);
    }
    /** 老偏好入口也递增关闭代次，并以同一成员锁串行化。 */
    @Transactional
    public void saveLegacy(String member,String key,String value) {
        lockMember(member);
        if (DEFAULTS.equals(key)) {
            View old=get(member);
            try {
                ObjectNode next=(ObjectNode)json.readTree(value);
                Map<String,String> check=new LinkedHashMap<>(); for(String k:KEYS)check.put(k,preferences.find(member,k));check.put(key,value);
                Settings parsed=parse(check).settings();
                ObjectNode previous=(ObjectNode)json.valueToTree(json.readTree(Optional.ofNullable(preferences.find(member,key)).filter(v->!v.isBlank()).orElse("{}")));
                previous.setAll(next);
                for(String stage:List.of("title","image","polish"))previous.put(stage+"Epoch",old.settings().epoch(stage)+(old.settings().enabled(stage)&&!parsed.enabled(stage)?1:0));
                value=write(previous);
            } catch(BadRequestException e){throw e;} catch(Exception e){throw new BadRequestException("小记 AI 设置格式异常");}
        }
        preferences.upsert(member,"note-polish-prompt-v1".equals(key)?"text-polish.prompt.v1":key,value);
    }
    /** 无损序列化持久状态。 */
    public String write(Object value) { try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);} }
    /** 比较策略版本或内容副本的稳定摘要。 */
    public static String hash(String value) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);} }
    private static String text(String value){return value==null?"":value;}
    private static void requireMember(String member){if(member==null||member.isBlank())throw new UnauthorizedException("请先登录");}
}
