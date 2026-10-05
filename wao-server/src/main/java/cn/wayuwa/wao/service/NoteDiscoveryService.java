package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.domain.*;
import cn.wayuwa.wao.mapper.*;
import static cn.wayuwa.wao.mapper.NoteDiscoveryMapper.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 成员私有发现闭环；数据库租约和冻结代次控制分析与发布。 */
@Service
public class NoteDiscoveryService {
    public static final String PREFERENCE_KEY="note-discovery-v1", PIPELINE="discovery-v3";
    public static final String GENERATE="NOTE_DISCOVERY_GENERATE", REVIEW="NOTE_DISCOVERY_REVIEW";
    private final NoteDiscoveryMapper db;
    private final DocMapper docs;
    private final AiAssistantRunMapper runs;
    private final LocalNoteAiService local;
    private final LocalCodexModelService models;
    private final MemberPreferenceMapper preferences;
    private final NoteDiscoveryIndexService index;
    private final NoteDiscoveryValidator validator;
    private final ObjectMapper json;
    private final VaultCryptoService crypto;
    private final Environment env;
    private final TransactionTemplate tx;
    public NoteDiscoveryService(NoteDiscoveryMapper db,DocMapper docs,AiAssistantRunMapper runs,LocalNoteAiService local,
            LocalCodexModelService models,MemberPreferenceMapper preferences,NoteDiscoveryIndexService index,
            NoteDiscoveryValidator validator,ObjectMapper json,VaultCryptoService crypto,Environment env,PlatformTransactionManager manager) {
        this.db=db;this.docs=docs;this.runs=runs;this.local=local;this.models=models;this.preferences=preferences;
        this.index=index;this.validator=validator;this.json=json;this.crypto=crypto;this.env=env;this.tx=new TransactionTemplate(manager);
    }
    /** 全局开关与显式启用本机执行同时满足才启动任务。 */
    public boolean available() { return env.getProperty("wao.ai.discoveries.enabled",Boolean.class,false)&&local.enabled()&&local.ready(); }
    private boolean availableFor(String member) {
        String allowed=env.getProperty("wao.ai.discoveries.member-ids","");
        return available()&&(allowed.isBlank()||Arrays.asList(allowed.split(",")).contains(member));
    }
    /** 返回真实成员的偏好；没有记录即未开启。 */
    public Map<String,Object> preference(String member) {
        var s=db.one("SELECT * FROM note_discovery_schedule WHERE member_id=?",member);
        return Map.of("enabled",flag(s,"enabled"),"version",number(s,"version"),"available",availableFor(member),
                "message",availableFor(member)?"读取本人小记，在后台寻找有依据的联系，可随时暂停":"自动发现暂不可用；已保存内容仍可查看");
    }
    /** 在同一事务保存版本化偏好及开启代次；暂停废弃迟到任务。 */
    public Map<String,Object> savePreference(String member,boolean enabled,long version) {
        return tx.execute(status -> {
            var s=db.lock(member); if(number(s,"version")!=version)throw new ConflictException("自动发现设置已在其他窗口更新，请重新读取");
            long generation=number(s,"enable_generation")+(enabled!=flag(s,"enabled")?1:0);
            if(enabled!=flag(s,"enabled")) {
                db.update("UPDATE note_mining_job SET status='CANCELLED',result_type='PAUSED',finished_at=CURRENT_TIMESTAMP(6) WHERE member_id=? AND origin='AUTO' AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED','SUPERSEDED')",member);
                db.update("UPDATE note_discovery_schedule SET active_job_id=NULL,lease_token=NULL,lease_until=NULL WHERE member_id=?",member);
            }
            db.update("UPDATE note_discovery_schedule SET enabled=?,enable_generation=?,version=version+1,next_run_at=CURRENT_TIMESTAMP(6) WHERE member_id=?",enabled,generation,member);
            preferences.upsert(member,PREFERENCE_KEY,write(Map.of("enabled",enabled,"version",version+1,"generation",generation)));
            return preference(member);
        });
    }
    /** 默认列表最多一条，读取不启动或物化分析；实时校验来源。 */
    public Map<String,Object> current(String member) {
        var s=db.one("SELECT * FROM note_discovery_schedule WHERE member_id=?",member);
        Map<String,Object> result=new LinkedHashMap<>(); Map<String,Object> item=null;
        String id=string(s,"current_artifact_id");
        if(id!=null) {
            var a=db.one("SELECT * FROM note_mining_artifact WHERE id=? AND member_id=? AND validity='VALID' AND expires_at>CURRENT_TIMESTAMP(6)",id,member);
            if(a!=null&&!NoteDiscoveryValidator.hasInternalDetails(string(a,"headline"),string(a,"content_text"),string(a,"rationale_text"),string(a,"next_step"))&&!flagFeedback(member,id,"dismissed_at")) { item=artifact(member,a); if(Boolean.TRUE.equals(item.get("restricted")))item=null; }
        }
        var job=db.one("SELECT result_type,status FROM note_mining_job WHERE member_id=? AND origin='AUTO' ORDER BY created_at DESC,id DESC LIMIT 1",member);
        String state=!flag(s,"enabled")?(number(s,"enable_generation")==0?"OFF":"PAUSED"):!availableFor(member)?"UNAVAILABLE":
                job!=null&&"FAILED".equals(string(job,"status"))?"FAILED":job!=null&&"DEFERRED".equals(string(job,"status"))?"DEFERRED":string(s,"active_job_id")!=null?"PROCESSING":"READY";
        result.put("discovery",item);result.put("automationState",state);result.put("lastOutcome",string(job,"result_type"));
        result.put("revision",number(s,"change_seq")+":"+Objects.toString(id,""));
        var coverage=db.one("SELECT COUNT(*) AS eligible,SUM(CASE WHEN n.indexed_rev IS NOT NULL AND n.indexed_rev=d.rev THEN 1 ELSE 0 END) AS indexed FROM doc d JOIN kb k ON k.id=d.kb_id LEFT JOIN note_discovery_note_state n ON n.doc_id=d.id AND n.member_id=? WHERE d.author_id=? AND k.notes=1 AND k.deleted_at IS NULL AND d.deleted_at IS NULL AND d.archived_at IS NULL AND COALESCE(d.auto,0)=0 AND d.updated_at>=TIMESTAMPADD(DAY,-90,CURRENT_TIMESTAMP(6))",member,member);
        var latest=db.one("SELECT source_count FROM note_mining_job WHERE member_id=? AND origin='AUTO' ORDER BY created_at DESC,id DESC LIMIT 1",member);
        result.put("coverage",Map.of("days",90,"eligible",number(coverage,"eligible"),"indexed",number(coverage,"indexed"),"analyzed",number(latest,"source_count")));return result;
    }
    private boolean flagFeedback(String member,String id,String field) {
        var f=db.one("SELECT * FROM note_discovery_feedback WHERE member_id=? AND item_id=?",member,id);return f!=null&&f.get(field)!=null;
    }
    /** 收藏和历史产物游标分页；来源失效也不泄露收藏摘录。 */
    public Map<String,Object> page(String member,boolean saved,String cursor,int limit) {
        if(limit<1||limit>50)throw new BadRequestException("每页须为 1–50 条");
        String time="9999-12-31 23:59:59.999999",after="~";
        if(cursor!=null&&!cursor.isBlank())try {
            JsonNode c=json.readTree(Base64.getUrlDecoder().decode(cursor));time=c.path("time").asText();after=c.path("id").asText();
            if(time.length()>32||after.length()>96||!time.matches("[0-9 :.T-]+"))throw new IllegalArgumentException();
        }catch(Exception ex){throw new BadRequestException("分页游标无效");}
        String select=saved?"SELECT f.item_id AS item_id,f.saved_at AS item_time FROM note_discovery_feedback f WHERE f.member_id=? AND f.saved_at IS NOT NULL":
            "SELECT a.id AS item_id,a.created_at AS item_time FROM note_mining_artifact a WHERE a.member_id=? AND a.origin='LEGACY' UNION ALL SELECT CONCAT('visual:',v.job_id,':',v.lens),v.created_at FROM note_mining_visual v WHERE v.member_id=?";
        List<Object> args=new ArrayList<>();args.add(member);if(!saved)args.add(member);args.add(time);args.add(time);args.add(after);args.add(limit+1);
        var rows=db.rows("SELECT * FROM ("+select+") q WHERE (item_time<? OR (item_time=? AND item_id<?)) ORDER BY item_time DESC,item_id DESC LIMIT ?",args.toArray());
        boolean more=rows.size()>limit;var shown=rows.subList(0,Math.min(limit,rows.size()));List<Map<String,Object>> items=new ArrayList<>();
        for(var row:shown)items.add(item(member,string(row,"item_id")));
        String next=null;if(more&&!shown.isEmpty()){var last=shown.get(shown.size()-1);next=Base64.getUrlEncoder().withoutPadding().encodeToString(write(Map.of("time",string(last,"item_time"),"id",string(last,"item_id"))).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        Map<String,Object> page=new LinkedHashMap<>();page.put("items",items);page.put("nextCursor",next);return page;
    }
    /** 返回本人产物或旧图片，其他成员及不存在资源统一拒绝。 */
    public Map<String,Object> item(String member,String id) {
        if(id.startsWith("visual:")) {
            String[] parts=id.split(":",3);if(parts.length!=3)throw new NotFoundException("发现不存在");
            var v=db.one("SELECT * FROM note_mining_visual WHERE job_id=? AND lens=? AND member_id=?",parts[1],parts[2],member);
            var job=db.one("SELECT * FROM note_mining_job WHERE id=? AND member_id=?",parts[1],member);
            if(v==null||job==null)throw new NotFoundException("发现不存在");
            List<Map<String,Object>> evidence=new ArrayList<>();boolean safe=true;
            JsonNode snapshots=read(string(job,"source_snapshot_json"));
            for(JsonNode ordinal:read(string(v,"source_indexes_json"))) {
                JsonNode source=snapshots.path(ordinal.asInt(-1));String doc=source.path("id").asText();
                if(!db.eligible(member,doc)){safe=false;break;}
                evidence.add(Map.of("docId",doc,"docRev",source.path("rev").asInt(),"title",source.path("title").asText(),"quote",source.path("excerpt").asText()));
            }
            safe&=!evidence.isEmpty();var out=base(member,id,"LEGACY",!safe);
            out.put("headline",safe?string(v,"caption"):"来源已不可访问");out.put("observation",safe?string(v,"caption"):"");
            out.put("valueReason",safe?string(v,"rationale"):"");out.put("evidence",safe?evidence:List.of());out.put("imageUrl",safe?string(v,"image_url"):null);return out;
        }
        var a=db.one("SELECT * FROM note_mining_artifact WHERE id=? AND member_id=?",id,member);
        if(a==null) {
            if(db.one("SELECT item_id FROM note_discovery_feedback WHERE member_id=? AND item_id=?",member,id)==null)throw new NotFoundException("发现不存在");
            var expired=base(member,id,"AUTO",true);expired.put("headline","这条发现已过期");expired.put("observation","");expired.put("valueReason","");expired.put("evidence",List.of());expired.put("validity","EXPIRED");return expired;
        }return artifact(member,a);
    }
    private Map<String,Object> artifact(String member,Map<String,Object> a) {
        String id=string(a,"id");var sources=db.rows("SELECT * FROM note_mining_artifact_source WHERE artifact_id=? ORDER BY source_ord",id);
        boolean internalDetails="AUTO".equals(string(a,"origin"))&&NoteDiscoveryValidator.hasInternalDetails(string(a,"headline"),string(a,"content_text"),string(a,"rationale_text"),string(a,"next_step"));
        boolean safe=!sources.isEmpty()&&!internalDetails;List<Map<String,Object>> evidence=new ArrayList<>();
        for(var source:sources) {
            String doc=string(source,"doc_id");if(!db.eligible(member,doc)){safe=false;break;}
            DocEntity live=docs.selectById(doc);
            if("AUTO".equals(string(a,"origin")) && (!Objects.equals(string(source,"body_fingerprint"),NoteDiscoveryIndexService.hash(index.text(live)))||number(source,"doc_rev")!=(live.getRev()==null?1:live.getRev()))) {safe=false;break;}
            List<String> quotes=source.get("quotes_json")==null?List.of(string(source,"excerpt")):readStrings(string(source,"quotes_json"));
            for(String quote:quotes)evidence.add(Map.of("docId",doc,"docRev",number(source,"doc_rev"),"title",Objects.toString(string(source,"doc_title"),"小记"),"quote",quote,"sourceBasis",Objects.toString(string(source,"source_basis"),"LEGACY_UNKNOWN"),"basisRev",number(source,"basis_rev")));
        }
        var out=base(member,id,string(a,"origin"),!safe);out.put("kind",string(a,"kind"));
        out.put("headline",safe?Objects.toString(string(a,"headline"),string(a,"content_text")):internalDetails?"这条发现暂不可展示":"来源已不可访问");
        out.put("observation",safe?string(a,"content_text"):"");out.put("valueReason",safe?string(a,"rationale_text"):"");
        out.put("optionalNextStep",safe?string(a,"next_step"):null);out.put("evidence",safe?evidence:List.of());
        out.put("validity",string(a,"validity"));out.put("publishedAt",string(a,"published_at"));return out;
    }
    private Map<String,Object> base(String member,String id,String origin,boolean restricted) {
        var out=new LinkedHashMap<String,Object>();out.put("id",id);out.put("origin",origin);out.put("restricted",restricted);
        var f=db.one("SELECT * FROM note_discovery_feedback WHERE member_id=? AND item_id=?",member,id);
        Map<String,Object> feedback=new LinkedHashMap<>();feedback.put("saved",f!=null&&f.get("saved_at")!=null);feedback.put("dismissed",f!=null&&f.get("dismissed_at")!=null);
        feedback.put("correction",string(f,"correction_text"));feedback.put("version",number(f,"version"));out.put("feedback",feedback);return out;
    }
    /** 独立版本化反馈，省略字段保留，收藏与否定互不覆盖。 */
    public Map<String,Object> feedback(String member,String id,long version,Map<String,Object> patch) {
        return tx.execute(status -> {
            item(member,id);
            db.update("INSERT IGNORE INTO note_discovery_feedback(member_id,item_id,item_kind) VALUES(?,?,?)",member,id,id.startsWith("visual:")?"LEGACY_VISUAL":"ARTIFACT");
            var f=db.one("SELECT * FROM note_discovery_feedback WHERE member_id=? AND item_id=? FOR UPDATE",member,id);
            if(!id.startsWith("visual:")) {var a=db.one("SELECT topic_id,conclusion_hash,evidence_hash FROM note_mining_artifact WHERE id=? AND member_id=?",id,member);if(a!=null)db.update("UPDATE note_discovery_feedback SET topic_id=?,conclusion_hash=?,evidence_hash=? WHERE member_id=? AND item_id=?",string(a,"topic_id"),string(a,"conclusion_hash"),string(a,"evidence_hash"),member,id);}
            if(number(f,"version")!=version)throw new ConflictException("这条发现已在其他窗口更新，请重新读取后重试");
            for(String field:List.of("saved","dismissed")) if(patch.containsKey(field)) {
                if(!(patch.get(field) instanceof Boolean))throw new BadRequestException("反馈状态须为布尔值");
                db.update("UPDATE note_discovery_feedback SET "+(field.equals("saved")?"saved_at":"dismissed_at")+"="+(Boolean.TRUE.equals(patch.get(field))?"CURRENT_TIMESTAMP(6)":"NULL")+" WHERE member_id=? AND item_id=?",member,id);
            }
            if(patch.containsKey("correction")) {
                Object value=patch.get("correction");if(value!=null&&(!(value instanceof String)||((String)value).length()>500))throw new BadRequestException("补充内容最多 500 字");
                db.update("UPDATE note_discovery_feedback SET correction_text=?,corrected_at=CURRENT_TIMESTAMP(6) WHERE member_id=? AND item_id=?",value,member,id);
            }
            db.update("UPDATE note_discovery_feedback SET version=version+1 WHERE member_id=? AND item_id=?",member,id);
            // 否定独立保存在反馈中；撤销不能把已解决主题改回 OPEN。
            return item(member,id);
        });
    }
    /** 旧反馈协议适配为独立状态，不能取消已有收藏。 */
    public void legacyFeedback(String member,String id,String status) {
        var current=item(member,id);
        var f=(Map<?,?>)current.get("feedback");
        if("SAVED".equals(status))feedback(member,id,((Number)f.get("version")).longValue(),Map.of("saved",true));
        else if("NOT_ACCURATE".equals(status))feedback(member,id,((Number)f.get("version")).longValue(),Map.of("dismissed",true));
    }
    /** 校验来源属于该产物且当前仍可见，供现有阅读器调用。 */
    public void requireSource(String member,String id,String doc) {
        var item=item(member,id);if(Boolean.TRUE.equals(item.get("restricted")))throw new NotFoundException("来源已不可访问");
        boolean found=((List<?>)item.get("evidence")).stream().anyMatch(e->e instanceof Map<?,?> m&&doc.equals(m.get("docId")));
        if(!found||!db.eligible(member,doc))throw new NotFoundException("来源已不可访问");
    }

    private long lastPollNanos;
    /** 推进有权限且到期的成员；本地验收必须显式限定成员白名单。 */
    public synchronized void tick() {
        if(!available())return;
        long now=System.nanoTime();if(lastPollNanos!=0&&now-lastPollNanos<java.util.concurrent.TimeUnit.SECONDS.toNanos(20))return;lastPollNanos=now;
        String allowed=env.getProperty("wao.ai.discoveries.member-ids","");
        for(var a:db.rows("SELECT a.*,r.member_id FROM note_discovery_ai_attempt a JOIN ai_assistant_run r ON r.id=a.ai_run_id WHERE a.execution_scope=? AND a.status IN ('QUEUED','RUNNING') AND r.status IN ('failed','succeeded')",local.capabilityId())) {
            String member=string(a,"member_id");if(!allowed.isBlank()&&!Arrays.asList(allowed.split(",")).contains(member))continue;
            tx.executeWithoutResult(status -> {
                var locked=db.one("SELECT * FROM note_discovery_ai_attempt WHERE id=? FOR UPDATE",string(a,"id"));
                if(!Set.of("QUEUED","RUNNING").contains(string(locked,"status")))return;
                if("QUEUED".equals(string(locked,"status")))refund(locked,member);
                var run=runs.selectById(string(a,"ai_run_id"));
                boolean succeeded=run!=null&&"succeeded".equals(run.getStatus());
                db.update("UPDATE note_discovery_ai_attempt SET status=?,error_code=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",succeeded?"SUCCEEDED":"FAILED",succeeded?null:"CALL_FAILED",string(a,"id"));
            });
        }
        for(var s:db.rows("SELECT member_id FROM note_discovery_schedule WHERE enabled=1 AND next_run_at<=CURRENT_TIMESTAMP(6) AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP(6)) ORDER BY next_run_at,member_id LIMIT 30")) {
            String member=string(s,"member_id");if(!allowed.isBlank()&&!Arrays.asList(allowed.split(",")).contains(member))continue;
            advance(member);
        }
    }
    /** 领取和推进一个成员；故障仅保存脱敏原因，不隐藏为零发现。 */
    public void advance(String member) {
        String token=UUID.randomUUID().toString().replace("-","");
        if(db.claim(member,token,120)!=1)return;
        try {
            local.requireAvailable();
            var s=db.one("SELECT * FROM note_discovery_schedule WHERE member_id=?",member);
            String model=null;
            if(string(s,"active_job_id")==null)model=models.validate(preferences.find(member,"note-ai-model-v1"));
            String chosen=model;
            tx.executeWithoutResult(status -> {
                var locked=db.lock(member);if(!token.equals(string(locked,"lease_token"))||!flag(locked,"enabled"))return;
                if(string(locked,"active_job_id")!=null)progress(locked,token);
                else { indexMember(member); freeze(db.lock(member),token,chosen); }
            });
        } catch(RuntimeException ex) {
            tx.executeWithoutResult(status -> {
                var s=db.lock(member);if(!token.equals(string(s,"lease_token")))return;
                String id=string(s,"active_job_id");
                String code=ex instanceof IllegalArgumentException?"PROTOCOL_ERROR":ex instanceof BadRequestException?"CONFIG_UNAVAILABLE":"SOURCE_UNAVAILABLE";
                if(id!=null) {
                    if(code.equals("PROTOCOL_ERROR")&&number(db.one("SELECT COUNT(*) AS n FROM note_discovery_ai_attempt WHERE job_id=? AND phase=?",id,string(db.one("SELECT phase FROM note_mining_job WHERE id=?",id),"phase")),"n")<2&&number(db.one("SELECT attempt_count FROM note_mining_job WHERE id=?",id),"attempt_count")<4) {
                        db.update("UPDATE note_mining_job SET status='DEFERRED',result_type='PROTOCOL_ERROR',error_code='PROTOCOL_ERROR',ai_run_id=NULL,retry_at=TIMESTAMPADD(MINUTE,5,CURRENT_TIMESTAMP(6)) WHERE id=?",id);delay(member,5);return;
                    }
                    if(code.equals("CONFIG_UNAVAILABLE")) db.update("UPDATE note_mining_job SET status='DEFERRED',result_type=?,error_code=?,retry_at=TIMESTAMPADD(MINUTE,15,CURRENT_TIMESTAMP(6)) WHERE id=?",code,code,id);
                    else { terminal(db.one("SELECT * FROM note_mining_job WHERE id=?",id),"FAILED",code); }
                } else {
                    db.update("INSERT INTO note_mining_job(id,member_id,source_snapshot_json,source_count,origin,status,result_type,error_code,finished_at) VALUES(?,?,'[]',0,'AUTO','DEFERRED',?,?,CURRENT_TIMESTAMP(6))","nd"+UUID.randomUUID().toString().replace("-",""),member,code,code);
                }
                db.update("UPDATE note_discovery_schedule SET next_run_at=TIMESTAMPADD(MINUTE,15,CURRENT_TIMESTAMP(6)),active_job_id=? WHERE member_id=?",code.equals("CONFIG_UNAVAILABLE")?id:null,member);
            });
        } finally { db.update("UPDATE note_discovery_schedule SET lease_token=NULL,lease_until=NULL WHERE member_id=? AND lease_token=?",member,token); }
    }
    private void indexMember(String member) {
        var s=db.lock(member);
        if(s.get("backfill_high_water_json")==null) {
            String high=string(db.one("SELECT CURRENT_TIMESTAMP(6) AS moment"),"moment");
            db.update("UPDATE note_discovery_schedule SET backfill_high_water_json=?,backfill_cursor_json=? WHERE member_id=?",write(Map.of("time",high)),write(Map.of("phase","RECENT")),member);
            s=db.lock(member);
        }
        String high=read(string(s,"backfill_high_water_json")).path("time").asText();
        JsonNode c=s.get("backfill_cursor_json")==null?json.createObjectNode():read(string(s,"backfill_cursor_json"));
        String phase=c.path("phase").asText("RECENT"),cursorTime=c.path("time").asText(high),cursorId=c.path("id").asText("~");
        var budget=budget(member);int remaining=200-(int)number(budget,"indexed_docs");if(remaining<=0)return;
        List<Object> args=new ArrayList<>(List.of(member,high,high,cursorTime,cursorTime,cursorId));
        String allow=env.getProperty("wao.ai.discoveries.document-ids","");
        String scoped="";
        if(!allow.isBlank()) { List<String> ids=Arrays.asList(allow.split(","));scoped=" AND d.id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")";args.addAll(ids); }
        int size=Math.min(20,remaining);args.add(size);
        var rows=flag(s,"index_complete")?List.<Map<String,Object>>of():db.rows("SELECT d.id,d.created_at FROM doc d JOIN kb k ON k.id=d.kb_id WHERE d.author_id=? AND k.notes=1 AND k.deleted_at IS NULL AND d.deleted_at IS NULL AND d.archived_at IS NULL AND COALESCE(d.auto,0)=0 AND d.created_at<=? AND d.created_at"+(phase.equals("RECENT")?">=":"<")+"TIMESTAMPADD(DAY,-90,?) AND (d.created_at<? OR (d.created_at=? AND d.id<?))"+scoped+" ORDER BY d.created_at DESC,d.id DESC LIMIT ?",args.toArray());
        for(var row:rows) {
            String id=string(row,"id");indexDoc(member,docs.selectById(id));remaining--;countIndex(member);
            cursorTime=string(row,"created_at");cursorId=id;
        }
        boolean complete=flag(s,"index_complete");
        if(!complete&&rows.size()<size) {
            if(phase.equals("RECENT")){phase="HISTORY";cursorTime=high;cursorId="~";}else complete=true;
        }
        db.update("UPDATE note_discovery_schedule SET backfill_cursor_json=?,index_complete=? WHERE member_id=?",write(Map.of("phase",phase,"time",cursorTime,"id",cursorId)),complete,member);
        if(remaining>0)for(var row:db.rows("SELECT doc_id FROM note_discovery_note_state WHERE member_id=? AND eligible=1 AND indexed_rev IS NULL AND stable_after<=CURRENT_TIMESTAMP(6) ORDER BY change_seq LIMIT ?",member,Math.min(8,remaining))) {
            String id=string(row,"doc_id");if(allow.isBlank()||Arrays.asList(allow.split(",")).contains(id)){indexDoc(member,docs.selectById(id));countIndex(member);}
        }
    }
    private void countIndex(String member) { db.update("UPDATE note_discovery_daily_budget SET indexed_docs=indexed_docs+1 WHERE budget_day=DATE(UTC_TIMESTAMP()+INTERVAL 8 HOUR) AND scope='MEMBER' AND scope_id=?",member); }
    private void indexDoc(String member,DocEntity doc) {
        if(doc==null||!db.eligible(member,doc.getId()))return;
        String text=index.text(doc),hash=NoteDiscoveryIndexService.hash(text);var old=db.one("SELECT * FROM note_discovery_note_state WHERE doc_id=?",doc.getId());
        if(old!=null&&number(old,"indexed_rev")==doc.getRev()&&NoteDiscoveryIndexService.VERSION.equals(string(old,"index_version")))return;
        if(old==null)db.update("INSERT IGNORE INTO note_discovery_note_state(doc_id,member_id,doc_rev,body_fingerprint,change_origin,provenance,stable_after,eligible) VALUES(?,?,?,?,'BACKFILL','LEGACY_UNKNOWN',CURRENT_TIMESTAMP(6),1)",doc.getId(),member,doc.getRev()==null?1:doc.getRev(),hash);
        db.update("DELETE FROM note_discovery_index_term WHERE doc_id=?",doc.getId());
        var terms=new ArrayList<>(index.terms(text+"\n"+Objects.toString(doc.getTitle(),"")+"\n"+Objects.toString(doc.getTagsJson(),"")));
        for(int offset=0;offset<terms.size();offset+=200) {
            List<Object> values=new ArrayList<>();var part=terms.subList(offset,Math.min(offset+200,terms.size()));
            for(String term:part)values.addAll(List.of(member,doc.getId(),NoteDiscoveryIndexService.hash(term),term,NoteDiscoveryIndexService.VERSION));
            db.update("INSERT INTO note_discovery_index_term(member_id,doc_id,term_hash,term_text,term_type,weight,index_version) VALUES "+String.join(",",Collections.nCopies(part.size(),"(?,?,?,?,'TEXT',1,?)")),values.toArray());
        }
        db.update("UPDATE note_discovery_note_state SET indexed_rev=?,index_version=?,body_fingerprint=?,index_payload_json=? WHERE doc_id=?",doc.getRev(),NoteDiscoveryIndexService.VERSION,hash,write(Map.of("characters",text.length())),doc.getId());
        if(old==null||old.get("index_version")==null)db.update("UPDATE note_discovery_schedule SET index_seq=index_seq+1 WHERE member_id=?",member);
    }
    private void freeze(Map<String,Object> s,String token,String model) {
        String member=string(s,"member_id");long seq=number(s,"change_seq");
        var lastPipeline=db.one("SELECT pipeline_version FROM note_mining_job WHERE member_id=? AND origin='AUTO' AND pipeline_version IS NOT NULL AND status IN ('SUCCEEDED','FAILED') ORDER BY created_at DESC,id DESC LIMIT 1",member);
        if(seq<=number(s,"processed_seq")&&number(s,"index_seq")<=number(s,"processed_index_seq")&&flag(s,"index_complete")&&PIPELINE.equals(string(lastPipeline,"pipeline_version"))) { db.update("UPDATE note_discovery_schedule SET next_run_at=TIMESTAMPADD(MINUTE,15,CURRENT_TIMESTAMP(6)) WHERE member_id=?",member);return; }
        var seeds=db.rows("SELECT n.doc_id FROM note_discovery_note_state n JOIN doc d ON d.id=n.doc_id WHERE n.member_id=? AND n.eligible=1 AND n.indexed_rev IS NOT NULL AND n.stable_after<=CURRENT_TIMESTAMP(6) ORDER BY (n.change_seq>n.processed_seq) DESC,n.change_seq,d.created_at DESC LIMIT 2",member);
        List<NoteDiscoveryValidator.Material> materials;
        try { materials=retrieve(member,seeds); }catch(ContextLimitException ex){finishWithoutJob(member,seq,"CONTEXT_LIMIT",List.of());return;}
        if(materials.size()<2){finishWithoutJob(member,seq,"INSUFFICIENT_MATERIAL",materials);return;}
        if(materials.stream().mapToInt(m->m.body().length()).sum()>24000){finishWithoutJob(member,seq,"CONTEXT_LIMIT",materials);return;}
        String manifest=write(materials.stream().map(m->Map.of("id",m.docId(),"rev",m.docRev(),"hash",m.fingerprint())).toList());
        String fingerprint=NoteDiscoveryIndexService.hash(manifest);
        if(db.one("SELECT id FROM note_mining_job WHERE member_id=? AND enable_generation=? AND input_fingerprint=? AND pipeline_version=?",member,number(s,"enable_generation"),fingerprint,PIPELINE)!=null){finishWithoutJob(member,seq,"DUPLICATE",materials);return;}
        budget(member);if(db.update("UPDATE note_discovery_daily_budget SET batches=batches+1 WHERE budget_day=DATE(UTC_TIMESTAMP()+INTERVAL 8 HOUR) AND scope='MEMBER' AND scope_id=? AND batches<3",member)!=1){finishWithoutJob(member,seq,"BUDGET_DEFERRED",materials);delay(member,60);return;}
        String id="nd"+UUID.randomUUID().toString().replace("-","");
        String input=write(Map.of("materials",materials,"inputIncomplete",!flag(s,"index_complete"),"existing",existing(member)));
        var encrypted=crypto.encrypt(input);
        db.update("""
            INSERT INTO note_mining_job(id,member_id,source_snapshot_json,source_count,origin,enable_generation,input_seq,input_index_seq,input_fingerprint,
            pipeline_version,prompt_version,status,phase,execution_scope,model_snapshot,input_cipher,input_nonce,started_at)
            VALUES(?,?,?,?,'AUTO',?,?,?,?,?,?,'QUEUED','GENERATING',?,?,?,?,CURRENT_TIMESTAMP(6))
            """,id,member,manifest,materials.size(),number(s,"enable_generation"),seq,number(s,"index_seq"),fingerprint,PIPELINE,NoteDiscoveryValidator.PROMPT_VERSION,local.capabilityId(),model,encrypted.ciphertext(),encrypted.nonce());
        db.update("UPDATE note_discovery_schedule SET active_job_id=?,next_run_at=CURRENT_TIMESTAMP(6) WHERE member_id=?",id,member);
        submit(db.one("SELECT * FROM note_mining_job WHERE id=?",id),"GENERATING",validator.generationPrompt(input));
    }
    private List<NoteDiscoveryValidator.Material> retrieve(String member,List<Map<String,Object>> seeds) {
        Set<String> seedIds=new LinkedHashSet<>();Set<String> terms=new HashSet<>();
        for(var seed:seeds){String id=string(seed,"doc_id");DocEntity d=docs.selectById(id);if(d!=null&&db.eligible(member,id)){seedIds.add(id);terms.addAll(index.terms(index.text(d)));}}
        Map<String,Integer> scores=new HashMap<>();Set<String> counterIds=new LinkedHashSet<>();
        List<String> hashes=terms.stream().map(NoteDiscoveryIndexService::hash).sorted().toList();
        // 按倒排键召回，不扫描整位成员的词项。后续状态单独检索，不能被支持材料的前 40 名挤掉。
        for(int offset=0;offset<hashes.size();offset+=200) {
            var part=hashes.subList(offset,Math.min(offset+200,hashes.size()));List<Object> args=new ArrayList<>();args.add(member);args.addAll(part);
            String where="t.member_id=? AND EXISTS (SELECT 1 FROM note_discovery_note_state n JOIN doc live ON live.id=n.doc_id WHERE n.doc_id=t.doc_id AND n.eligible=1 AND n.indexed_rev=live.rev AND n.stable_after<=CURRENT_TIMESTAMP(6)) AND t.term_hash IN ("+String.join(",",Collections.nCopies(part.size(),"?"))+")";
            for(var row:db.rows("SELECT t.doc_id,COUNT(*) AS score FROM note_discovery_index_term t WHERE "+where+" GROUP BY t.doc_id ORDER BY score DESC LIMIT 40",args.toArray()))scores.merge(string(row,"doc_id"),(int)number(row,"score"),Integer::sum);
            for(var row:db.rows("SELECT DISTINCT t.doc_id,d.created_at FROM note_discovery_index_term t JOIN doc d ON d.id=t.doc_id WHERE "+where+" AND (COALESCE(d.content_markdown,'') REGEXP '已解决|解决了|存好了|已完成|不用再|不再需要|已经能|取消了|改变了做法|复发|又出现' OR COALESCE(d.content_json,d.blocks_json,'') REGEXP '已解决|解决了|存好了|已完成|不用再|不再需要|已经能|取消了|改变了做法|复发|又出现') ORDER BY d.created_at DESC LIMIT 13",args.toArray()))counterIds.add(string(row,"doc_id"));
        }
        seedIds.forEach(id->scores.merge(id,1,Integer::sum));
        if(counterIds.size()>12)throw new ContextLimitException();
        List<DocEntity> pool=scores.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed()).limit(28)
                .map(e->docs.selectById(e.getKey())).filter(Objects::nonNull).filter(d->db.eligible(member,d.getId())).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        for(String id:counterIds)if(pool.stream().noneMatch(d->id.equals(d.getId()))&&db.eligible(member,id)){DocEntity d=docs.selectById(id);if(d!=null)pool.add(d);}
        Comparator<DocEntity> recent=Comparator.comparing(d->d.getCreatedAt()==null?LocalDateTime.MIN:d.getCreatedAt());
        List<DocEntity> ordered=new ArrayList<>();
        pool.stream().filter(d->seedIds.contains(d.getId())).forEach(ordered::add);
        pool.stream().filter(d->!ordered.contains(d)&&resolutionCue(index.text(d))).sorted(recent.reversed()).forEach(ordered::add);
        if(ordered.size()>6)throw new ContextLimitException();
        pool.stream().filter(d->!ordered.contains(d)).sorted(Comparator.<DocEntity>comparingInt(d->scores.getOrDefault(d.getId(),0)).reversed().thenComparing(recent.reversed())).forEach(ordered::add);
        List<NoteDiscoveryValidator.Material> materials=new ArrayList<>();for(var d:ordered.stream().limit(6).toList()) {
            String liveBody=index.text(d),body=liveBody;if(body.isBlank())continue;
            var state=db.one("SELECT * FROM note_discovery_note_state WHERE doc_id=?",d.getId());
            String provenance=Objects.toString(string(state,"provenance"),"LEGACY_UNKNOWN");int basisRev=d.getRev()==null?1:d.getRev();
            if("AI_DERIVED".equals(provenance)) {
                var polish=db.one("SELECT * FROM note_polish_version WHERE doc_id=? AND member_id=?",d.getId(),member);
                if(polish==null)continue;
                String basis=NoteDiscoveryIndexService.normalize(Objects.toString(string(polish,"source_markdown"),"").replaceFirst("^!\\[文章总览图]\\([^\\n]+\\)\\s*",""));
                if(polish.get("verified_ai_job_id")==null||!NoteDiscoveryIndexService.hash(basis).equals(string(state,"user_body_fingerprint"))
                        ||!NoteDiscoveryIndexService.hash(basis).equals(string(polish,"source_body_fingerprint"))||!NoteDiscoveryIndexService.hash(liveBody).equals(string(polish,"verified_output_fingerprint")))continue;
                body=basis;provenance="USER_BASELINE";basisRev=(int)number(state,"user_basis_rev");
            }
            materials.add(new NoteDiscoveryValidator.Material(d.getId(),d.getRev()==null?1:d.getRev(),Objects.toString(d.getTitle(),"小记"),body,NoteDiscoveryIndexService.hash(liveBody),provenance,Objects.toString(d.getCreatedAt(),""),basisRev));
        }return materials;
    }
    private static class ContextLimitException extends RuntimeException { }
    private boolean resolutionCue(String text){return text.matches("(?s).*(已解决|解决了|存好了|已完成|不用再|不再需要|已经能|取消了|改变了做法).* ".strip());}
    private List<Map<String,Object>> existing(String member) {
        var result=new ArrayList<Map<String,Object>>(db.rows("SELECT a.id,a.topic_id,a.headline,a.content_text,a.evidence_hash,t.state,f.dismissed_at,f.correction_text FROM note_mining_artifact a LEFT JOIN note_discovery_topic_state t ON t.member_id=a.member_id AND t.topic_id=a.topic_id LEFT JOIN note_discovery_feedback f ON f.member_id=a.member_id AND f.item_id=a.id WHERE a.member_id=? AND a.origin='AUTO' ORDER BY a.created_at DESC LIMIT 100",member));
        result.addAll(db.rows("SELECT f.item_id AS id,f.topic_id,f.conclusion_hash,f.evidence_hash,f.dismissed_at FROM note_discovery_feedback f LEFT JOIN note_mining_artifact a ON a.id=f.item_id AND a.member_id=f.member_id WHERE f.member_id=? AND f.dismissed_at IS NOT NULL AND a.id IS NULL LIMIT 100",member));
        result.addAll(db.rows("SELECT t.last_artifact_id AS id,t.topic_id,t.object_key,t.conclusion_key,t.evidence_fingerprint,t.state,t.resolution_sources_json FROM note_discovery_topic_state t LEFT JOIN note_mining_artifact a ON a.id=t.last_artifact_id WHERE t.member_id=? AND t.state='RESOLVED' AND a.id IS NULL LIMIT 100",member));return result;
    }
    private void finishWithoutJob(String member,long seq,String result,List<NoteDiscoveryValidator.Material> materials) {
        var s=db.lock(member);
        String fingerprint=NoteDiscoveryIndexService.hash(result+":"+seq+":"+number(s,"index_seq"));
        boolean deferred="CONTEXT_LIMIT".equals(result)||"BUDGET_DEFERRED".equals(result);
        db.update("INSERT IGNORE INTO note_mining_job(id,member_id,source_snapshot_json,source_count,origin,enable_generation,input_seq,input_index_seq,input_fingerprint,pipeline_version,prompt_version,status,result_type,finished_at) VALUES(?,?,'[]',0,'AUTO',?,?,?,?,?,?,?, ?,CURRENT_TIMESTAMP(6))",
                "nd"+UUID.randomUUID().toString().replace("-",""),member,number(s,"enable_generation"),seq,number(s,"index_seq"),fingerprint,PIPELINE,NoteDiscoveryValidator.PROMPT_VERSION,deferred?"DEFERRED":"SUCCEEDED",result);
        if(!deferred) {
            seq=acknowledge(member,seq,materials);
            db.update("UPDATE note_discovery_schedule SET processed_seq=GREATEST(processed_seq,?),processed_index_seq=index_seq WHERE member_id=?",seq,member);
        }
        delay(member,15);
    }
    private void delay(String member,int minutes){db.update("UPDATE note_discovery_schedule SET next_run_at=TIMESTAMPADD(MINUTE,?,CURRENT_TIMESTAMP(6)) WHERE member_id=?",minutes,member);}
    private void submit(Map<String,Object> job,String phase,String prompt) {
        String member=string(job,"member_id"),id=string(job,"id");
        long count=number(db.one("SELECT COUNT(*) AS n FROM note_discovery_ai_attempt WHERE job_id=? AND phase=?",id,phase),"n");
        if(count>=2||number(job,"attempt_count")>=4){terminal(job,"FAILED","CALL_FAILED");return;}
        var b=budget(member);var g=db.one("SELECT * FROM note_discovery_daily_budget WHERE budget_day=DATE(UTC_TIMESTAMP()+INTERVAL 8 HOUR) AND scope='GLOBAL' AND scope_id='all' FOR UPDATE");
        if(number(db.one("SELECT COUNT(*) AS n FROM note_discovery_ai_attempt WHERE status IN ('QUEUED','RUNNING')"),"n")>=2){delay(member,1);return;}
        if(number(b,"reserved_calls")+number(b,"started_calls")>=env.getProperty("wao.ai.discoveries.member-daily-calls",Integer.class,8)
                ||number(g,"reserved_calls")+number(g,"started_calls")>=env.getProperty("wao.ai.discoveries.global-daily-calls",Integer.class,60)) {
            db.update("UPDATE note_mining_job SET status='DEFERRED',result_type='BUDGET_DEFERRED' WHERE id=?",id);delay(member,60);return;
        }
        db.update("UPDATE note_discovery_daily_budget SET reserved_calls=reserved_calls+1 WHERE budget_day=DATE(UTC_TIMESTAMP()+INTERVAL 8 HOUR) AND ((scope='MEMBER' AND scope_id=?) OR (scope='GLOBAL' AND scope_id='all'))",member);
        String attempt="na"+UUID.randomUUID().toString().replace("-","").substring(0,28);
        String run=local.enqueue(member,prompt,phase.equals("GENERATING")?GENERATE:REVIEW,string(job,"model_snapshot"));
        db.update("INSERT INTO note_discovery_ai_attempt(id,job_id,phase,attempt_no,ai_run_id,execution_scope,status,budget_day,model,prompt_version,input_hash) VALUES(?,?,?,?,?,?,'QUEUED',DATE(UTC_TIMESTAMP()+INTERVAL 8 HOUR),?,?,?)",attempt,id,phase,count+1,run,local.capabilityId(),string(job,"model_snapshot"),NoteDiscoveryValidator.PROMPT_VERSION,NoteDiscoveryIndexService.hash(prompt));
        db.update("UPDATE note_mining_job SET ai_run_id=?,phase=?,status=?,attempt_count=attempt_count+1,result_type=NULL WHERE id=?",run,phase,phase,id);
    }
    private Map<String,Object> budget(String member) {
        db.update("INSERT IGNORE INTO note_discovery_daily_budget(budget_day,scope,scope_id) VALUES(DATE(UTC_TIMESTAMP()+INTERVAL 8 HOUR),'GLOBAL','all')");
        db.one("SELECT scope_id FROM note_discovery_daily_budget WHERE budget_day=DATE(UTC_TIMESTAMP()+INTERVAL 8 HOUR) AND scope='GLOBAL' AND scope_id='all' FOR UPDATE");
        db.update("INSERT IGNORE INTO note_discovery_daily_budget(budget_day,scope,scope_id) VALUES(DATE(UTC_TIMESTAMP()+INTERVAL 8 HOUR),'MEMBER',?)",member);
        return db.one("SELECT * FROM note_discovery_daily_budget WHERE budget_day=DATE(UTC_TIMESTAMP()+INTERVAL 8 HOUR) AND scope='MEMBER' AND scope_id=? FOR UPDATE",member);
    }
    private String input(Map<String,Object> job){return crypto.decrypt((byte[])job.get("input_cipher"),(byte[])job.get("input_nonce"));}
    private List<NoteDiscoveryValidator.Material> materials(String input) {
        try{return json.convertValue(read(input).path("materials"),new TypeReference<List<NoteDiscoveryValidator.Material>>(){});}catch(Exception ex){throw new IllegalArgumentException("PROTOCOL_ERROR");}
    }
    private boolean fresh(Map<String,Object> job,List<NoteDiscoveryValidator.Material> materials) {
        String member=string(job,"member_id");var s=db.one("SELECT * FROM note_discovery_schedule WHERE member_id=?",member);
        if(!flag(s,"enabled")||number(s,"enable_generation")!=number(job,"enable_generation")||!availableFor(member)||!PIPELINE.equals(string(job,"pipeline_version"))||!NoteDiscoveryValidator.PROMPT_VERSION.equals(string(job,"prompt_version")))return false;
        for(var m:materials){String scope=env.getProperty("wao.ai.discoveries.document-ids","");if(!scope.isBlank()&&!Arrays.asList(scope.split(",")).contains(m.docId()))return false;if(!db.eligible(member,m.docId()))return false;var d=docs.selectById(m.docId());if(d==null||m.docRev()!=(d.getRev()==null?1:d.getRev())||!m.fingerprint().equals(NoteDiscoveryIndexService.hash(index.text(d))))return false;}return true;
    }
    private void progress(Map<String,Object> s,String token) {
        var job=db.one("SELECT * FROM note_mining_job WHERE id=? FOR UPDATE",string(s,"active_job_id"));if(job==null){db.update("UPDATE note_discovery_schedule SET active_job_id=NULL WHERE member_id=?",string(s,"member_id"));return;}
        if(!Objects.equals(local.capabilityId(),string(job,"execution_scope"))){delay(string(s,"member_id"),15);return;}
        if(job.get("input_cipher")==null){terminal(job,"FAILED","SOURCE_UNAVAILABLE");return;}
        String input=input(job);var materials=materials(input);
        if(!fresh(job,materials)){terminal(job,"SUPERSEDED",flag(s,"enabled")?"STALE_INPUT":"PAUSED");return;}
        String phase=string(job,"phase");String runId=string(job,"ai_run_id");
        if(runId==null||"DEFERRED".equals(string(job,"status"))){submit(job,phase,phase.equals("GENERATING")?validator.generationPrompt(input):validator.reviewPrompt(input,string(job,"candidate_json")));return;}
        var run=runs.selectById(runId);if(run==null){terminal(job,"FAILED","CALL_FAILED");return;}
        if(!string(job,"member_id").equals(run.getMemberId())||!string(job,"execution_scope").equals(run.getAiCapabilityId()))throw new IllegalStateException("来源范围失效");
        if(Set.of("secure_queued","provider_running").contains(run.getStatus()))return;
        if(!"succeeded".equals(run.getStatus())) {
            var a=db.one("SELECT * FROM note_discovery_ai_attempt WHERE ai_run_id=?",runId);
            if(number(a,"attempt_no")<2&&number(job,"attempt_count")<4){db.update("UPDATE note_mining_job SET status='DEFERRED',result_type='CALL_FAILED',ai_run_id=NULL WHERE id=?",string(job,"id"));delay(string(job,"member_id"),5);}else terminal(job,"FAILED","CALL_FAILED");return;
        }
        if(phase.equals("GENERATING")) {
            var result=validator.validate(run.getResponseText(),materials);
            if(result.candidates().isEmpty()){terminal(job,"SUCCEEDED","NO_FINDING");return;}
            String candidates=write(result.candidates());db.update("UPDATE note_mining_job SET candidate_json=?,ai_run_id=NULL,phase='REVIEWING' WHERE id=?",candidates,string(job,"id"));
            job.put("candidate_json",candidates);job.put("ai_run_id",null);submit(job,"REVIEWING",validator.reviewPrompt(input,candidates));
        }else publish(job,s,token,materials,run.getResponseText());
    }
    private void publish(Map<String,Object> job,Map<String,Object> s,String token,List<NoteDiscoveryValidator.Material> materials,String response) {
        JsonNode root=validator.parse(response),candidates=read(string(job,"candidate_json"));
        if(root.path("schemaVersion").asInt(-1)!=1||!root.path("reviews").isArray()||root.path("reviews").size()!=candidates.size())throw new IllegalArgumentException("PROTOCOL_ERROR");
        Map<String,JsonNode> reviews=validator.validateReviews(response,candidates,materials,read(input(job)).path("existing"));
        Map<String,NoteDiscoveryValidator.Material> allowed=new HashMap<>();materials.forEach(m->allowed.put(m.docId(),m));String member=string(job,"member_id");
        JsonNode selected=null;String topic=null,conclusion=null,evidenceHash=null;
        for(JsonNode c:candidates){JsonNode review=reviews.get(c.path("candidateId").asText());if(review==null)throw new IllegalArgumentException("PROTOCOL_ERROR");
            if(!"KEEP".equals(review.path("decision").asText())) {
                if("RESOLVED".equals(review.path("topicState").asText())&&!review.path("counterEvidenceDocIds").isEmpty()) {
                    String matched=review.path("matchedExistingItemId").asText("");
                    if(!matched.isBlank())db.update("UPDATE note_discovery_topic_state t JOIN note_mining_artifact a ON a.topic_id=t.topic_id AND a.member_id=t.member_id SET t.state='RESOLVED',t.resolution_sources_json=? WHERE a.id=? AND a.member_id=?",write(review.path("counterEvidenceDocIds")),matched,member);
                    db.update("UPDATE note_mining_artifact a JOIN note_discovery_topic_state t ON t.topic_id=a.topic_id AND t.member_id=a.member_id SET a.validity='INVALID',a.invalid_reason='RESOLVED' WHERE a.member_id=? AND t.state='RESOLVED' AND a.origin='AUTO'",member);
                }continue;
            }
            if(!review.path("unsupportedClaimIds").isArray()||!review.path("unsupportedClaimIds").isEmpty())continue;
            String matched=review.path("matchedExistingItemId").asText("");
            boolean recurrence=java.util.stream.StreamSupport.stream(review.path("reasons").spliterator(),false).anyMatch(r->r.asText().startsWith("RECURRENCE"));
            if(!matched.isBlank()&&!recurrence)continue;
            String key=c.path("topicKey").asText();String ct=NoteDiscoveryIndexService.hash(NoteDiscoveryIndexService.normalize(c.path("observation").asText()).replaceAll("\\s+",""));
            String eh=NoteDiscoveryIndexService.hash(write(java.util.stream.StreamSupport.stream(c.path("evidence").spliterator(),false).map(e->Map.of("docId",e.path("docId").asText(),"quote",e.path("quote").asText())).sorted(Comparator.comparing(Object::toString)).toList()));String tk=stableTopic(member,key,c.path("evidence"));
            var previous=db.one("SELECT * FROM note_discovery_topic_state WHERE member_id=? AND (topic_id=? OR conclusion_key=?)",member,tk,ct);
            if(!matched.isBlank()) {var old=db.one("SELECT topic_id FROM note_mining_artifact WHERE id=? AND member_id=?",matched,member);if(old==null)old=db.one("SELECT topic_id FROM note_discovery_topic_state WHERE member_id=? AND last_artifact_id=?",member,matched);if(old==null)old=db.one("SELECT topic_id FROM note_discovery_feedback WHERE member_id=? AND item_id=?",member,matched);if(old==null||string(old,"topic_id")==null)continue;tk=string(old,"topic_id");previous=db.one("SELECT * FROM note_discovery_topic_state WHERE member_id=? AND topic_id=?",member,tk);}
            if(db.one("SELECT f.item_id FROM note_discovery_feedback f WHERE f.member_id=? AND f.dismissed_at IS NOT NULL AND (f.conclusion_hash=? OR (f.topic_id=? AND f.evidence_hash=?)) LIMIT 1",member,ct,tk,eh)!=null)continue;
            if(previous!=null) {
                if(eh.equals(string(previous,"evidence_fingerprint")))continue;
                if(recurrence||"RESOLVED".equals(string(previous,"state"))||"DISMISSED".equals(string(previous,"state"))) {
                    long priorSeq=number(previous,"last_user_change_seq");boolean newFacts=false;
                    for(JsonNode e:c.path("evidence")){var note=db.one("SELECT change_seq,change_origin FROM note_discovery_note_state WHERE member_id=? AND doc_id=?",member,e.path("docId").asText());if(number(note,"change_seq")>priorSeq&&"USER".equals(string(note,"change_origin")))newFacts=true;}
                    if(!recurrence||!newFacts)continue;
                }
            }
            if(db.one("SELECT id FROM note_mining_artifact WHERE member_id=? AND conclusion_hash=?",member,ct)!=null)continue;
            selected=c;topic=tk;conclusion=ct;evidenceHash=eh;break;
        }
        if(selected==null){terminal(job,"SUCCEEDED","NO_FINDING");return;}
        if(s.get("last_published_at")!=null&&db.one("SELECT member_id FROM note_discovery_schedule WHERE member_id=? AND last_published_at>TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6))",member,-Math.max(0,env.getProperty("wao.ai.discoveries.publish-interval-seconds",Integer.class,86400)))!=null){terminal(job,"SUCCEEDED","NO_FINDING");return;}
        if(!fresh(job,materials)||db.renew(member,token,number(s,"lease_epoch"),120)!=1){terminal(job,"SUPERSEDED","STALE_INPUT");return;}
        String id="nda"+UUID.randomUUID().toString().replace("-","");
        db.update("INSERT INTO note_mining_artifact(id,job_id,member_id,artifact_type,content_text,rationale_text,origin,kind,headline,next_step,topic_id,conclusion_hash,evidence_hash,validity,published_at,expires_at,supersedes_id) VALUES(?,?,?,'INSIGHT',?,?,'AUTO',?,?,?,?,?,?,'VALID',CURRENT_TIMESTAMP(6),TIMESTAMPADD(DAY,7,CURRENT_TIMESTAMP(6)),?)",id,string(job,"id"),member,selected.path("observation").asText(),selected.path("valueReason").asText(),selected.path("kind").asText(),selected.path("headline").asText(),selected.path("optionalNextStep").isTextual()?selected.path("optionalNextStep").asText():null,topic,conclusion,evidenceHash,string(s,"current_artifact_id"));
        Map<String,List<JsonNode>> grouped=new LinkedHashMap<>();for(JsonNode e:selected.path("evidence"))grouped.computeIfAbsent(e.path("docId").asText(),ignored->new ArrayList<>()).add(e);
        int ord=0;for(var entry:grouped.entrySet()){var m=allowed.get(entry.getKey());List<String> quotes=entry.getValue().stream().map(e->e.path("quote").asText()).toList();
            db.update("INSERT INTO note_mining_artifact_source(artifact_id,doc_id,doc_rev,doc_title,excerpt,source_ord,body_fingerprint,source_basis,basis_rev,claim_ids_json,quotes_json) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,m.docId(),m.docRev(),m.title(),quotes.get(0),ord++,m.fingerprint(),m.provenance(),m.basisRev(),write(entry.getValue().stream().map(e->e.path("supports")).toList()),write(quotes));}
        db.update("INSERT INTO note_discovery_topic_state(member_id,topic_id,object_key,conclusion_key,state,evidence_fingerprint,last_artifact_id,last_user_change_seq) VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE conclusion_key=VALUES(conclusion_key),state=VALUES(state),evidence_fingerprint=VALUES(evidence_fingerprint),last_artifact_id=VALUES(last_artifact_id),last_user_change_seq=VALUES(last_user_change_seq),version=version+1",member,topic,selected.path("topicKey").asText(),conclusion,"RESOLVED".equals(reviews.get(selected.path("candidateId").asText()).path("topicState").asText())?"RESOLVED":"OPEN",evidenceHash,id,number(job,"input_seq"));
        db.update("UPDATE note_discovery_schedule SET current_artifact_id=?,last_published_at=CURRENT_TIMESTAMP(6) WHERE member_id=? AND lease_token=? AND enable_generation=?",id,member,token,number(job,"enable_generation"));terminal(job,"SUCCEEDED","PUBLISHED");
    }
    private String stableTopic(String member,String object,JsonNode evidence) {
        Set<String> ids=new LinkedHashSet<>();for(JsonNode e:evidence)ids.add(e.path("docId").asText());
        List<Object> args=new ArrayList<>();args.add(member);args.addAll(ids);String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        var topics=db.rows("SELECT DISTINCT t.topic_id,t.object_key FROM note_discovery_topic_state t JOIN note_mining_artifact a ON a.topic_id=t.topic_id AND a.member_id=t.member_id JOIN note_mining_artifact_source src ON src.artifact_id=a.id WHERE t.member_id=? AND src.doc_id IN ("+placeholders+") LIMIT 40",args.toArray());
        Set<String> words=index.terms(object);String best=null;int bestScore=19;
        for(var prior:topics){Set<String> overlap=new HashSet<>(index.terms(string(prior,"object_key")));overlap.retainAll(words);int score=overlap.stream().filter(w->w.length()>=2).mapToInt(w->Math.min(8,w.length())*Math.min(8,w.length())).sum();if(overlap.size()>=3&&score>bestScore){bestScore=score;best=string(prior,"topic_id");}}
        return best!=null?best:NoteDiscoveryIndexService.hash(object.replaceAll("\\s+",""));
    }

    private void terminal(Map<String,Object> job,String state,String result) {
        db.update("UPDATE note_mining_job SET status=?,result_type=?,finished_at=CURRENT_TIMESTAMP(6),error_code=IF(?='FAILED',?,error_code),materialized_at=IF(?='SUCCEEDED',CURRENT_TIMESTAMP(6),materialized_at) WHERE id=?",state,result,state,result,state,string(job,"id"));
        long seq="SUPERSEDED".equals(state)?0:number(job,"input_seq");
        if(seq>0&&job.get("input_cipher")!=null)seq=acknowledge(string(job,"member_id"),seq,materials(input(job)));
        db.update("UPDATE note_discovery_schedule SET active_job_id=NULL,processed_seq=GREATEST(processed_seq,?),processed_index_seq=GREATEST(processed_index_seq,?),next_run_at=TIMESTAMPADD(SECOND,20,CURRENT_TIMESTAMP(6)) WHERE member_id=? AND active_job_id=?",seq,"SUPERSEDED".equals(state)?0:number(job,"input_index_seq"),string(job,"member_id"),string(job,"id"));
    }

    private long acknowledge(String member,long frozenSeq,List<NoteDiscoveryValidator.Material> materials) {
        for(var m:materials)db.update("UPDATE note_discovery_note_state SET processed_seq=GREATEST(processed_seq,LEAST(change_seq,?)) WHERE member_id=? AND doc_id=?",frozenSeq,member,m.docId());
        var pending=db.one("SELECT MIN(change_seq) AS pending_seq FROM note_discovery_note_state WHERE member_id=? AND eligible=1 AND change_seq>processed_seq",member);
        return pending!=null&&pending.get("pending_seq")!=null?Math.min(frozenSeq,Math.max(0,number(pending,"pending_seq")-1)):frozenSeq;
    }

    /** 本机 worker 在实际启动前再次检查暂停、来源和预算；仅返回是否可以调用。 */
    public boolean beforeExecution(AiAssistantRunEntity run) {
        if(!Set.of(GENERATE,REVIEW).contains(run.getUsagePurpose()))return true;
        return Boolean.TRUE.equals(tx.execute(status -> {
            var a=db.one("SELECT * FROM note_discovery_ai_attempt WHERE ai_run_id=? FOR UPDATE",run.getId());if(a==null||!"QUEUED".equals(string(a,"status")))return false;
            var job=db.one("SELECT * FROM note_mining_job WHERE id=?",string(a,"job_id"));
            if(job==null||!Objects.equals(run.getMemberId(),string(job,"member_id"))||!Objects.equals(run.getAiCapabilityId(),string(a,"execution_scope"))||!Set.of("GENERATING","REVIEWING").contains(string(job,"status"))||!fresh(job,materials(input(job)))) {
                db.update("UPDATE note_discovery_ai_attempt SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",string(a,"id"));refund(a,run.getMemberId());return false;
            }
            // 跨日排队的调用在实际执行日重新预占，旧预占只在明确未启动时返还。
            String today=string(db.one("SELECT DATE(UTC_TIMESTAMP()+INTERVAL 8 HOUR) AS day"),"day");
            if(!today.equals(string(a,"budget_day"))) {
                refund(a,run.getMemberId());var b=budget(run.getMemberId());var g=db.one("SELECT * FROM note_discovery_daily_budget WHERE budget_day=DATE(UTC_TIMESTAMP()+INTERVAL 8 HOUR) AND scope='GLOBAL' AND scope_id='all'");
                if(number(b,"started_calls")+number(b,"reserved_calls")>=env.getProperty("wao.ai.discoveries.member-daily-calls",Integer.class,8)||number(g,"started_calls")+number(g,"reserved_calls")>=env.getProperty("wao.ai.discoveries.global-daily-calls",Integer.class,60)){db.update("UPDATE note_discovery_ai_attempt SET status='FAILED',error_code='BUDGET_DEFERRED' WHERE id=?",string(a,"id"));return false;}
                db.update("UPDATE note_discovery_daily_budget SET reserved_calls=reserved_calls+1 WHERE budget_day=DATE(UTC_TIMESTAMP()+INTERVAL 8 HOUR) AND ((scope='MEMBER' AND scope_id=?) OR (scope='GLOBAL' AND scope_id='all'))",run.getMemberId());
                db.update("UPDATE note_discovery_ai_attempt SET budget_day=DATE(UTC_TIMESTAMP()+INTERVAL 8 HOUR) WHERE id=?",string(a,"id"));
            }
            db.update("UPDATE note_discovery_daily_budget SET reserved_calls=reserved_calls-1,started_calls=started_calls+1,retry_calls=retry_calls+? WHERE budget_day=DATE(UTC_TIMESTAMP()+INTERVAL 8 HOUR) AND ((scope='MEMBER' AND scope_id=?) OR (scope='GLOBAL' AND scope_id='all'))",number(a,"attempt_no")>1?1:0,run.getMemberId());
            db.update("UPDATE note_discovery_ai_attempt SET status='RUNNING',updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",string(a,"id"));return true;
        }));
    }
    private void refund(Map<String,Object> a,String member){db.update("UPDATE note_discovery_daily_budget SET reserved_calls=GREATEST(0,reserved_calls-1) WHERE budget_day=? AND ((scope='MEMBER' AND scope_id=?) OR (scope='GLOBAL' AND scope_id='all'))",a.get("budget_day"),member);}
    /** 保存实际 CLI 用量和耗时；缺失用量保持 null。 */
    public void executionFinished(AiAssistantRunEntity run,LocalNoteCodexProcess.Result result,long duration,String error) {
        if(!Set.of(GENERATE,REVIEW).contains(run.getUsagePurpose()))return;
        tx.executeWithoutResult(status -> {
            var attempt=db.one("SELECT * FROM note_discovery_ai_attempt WHERE ai_run_id=? FOR UPDATE",run.getId());
            int changed=db.update("UPDATE note_discovery_ai_attempt SET status=?,duration_ms=?,input_tokens=?,cached_input_tokens=?,output_tokens=?,cli_version=?,error_code=?,updated_at=CURRENT_TIMESTAMP(6) WHERE ai_run_id=? AND status='RUNNING'",error==null?"SUCCEEDED":"FAILED",duration,result==null?null:result.inputTokens(),result==null?null:result.cachedInputTokens(),result==null?null:result.outputTokens(),result==null?null:result.cliVersion(),error,run.getId());
            if(changed==1&&result!=null&&result.inputTokens()!=null&&result.outputTokens()!=null)
                db.update("UPDATE note_discovery_daily_budget SET input_tokens=COALESCE(input_tokens,0)+?,output_tokens=COALESCE(output_tokens,0)+?,usage_known_calls=usage_known_calls+1 WHERE budget_day=? AND ((scope='MEMBER' AND scope_id=?) OR (scope='GLOBAL' AND scope_id='all'))",result.inputTokens(),result.outputTokens(),attempt.get("budget_day"),run.getMemberId());
        });
    }
    /** 清除新流程短期私人输入及过期非收藏产物；旧内容永不自动删除。 */
    public void cleanup() {
        if(!env.getProperty("wao.ai.discoveries.cleanup-enabled",Boolean.class,true))return;
        String allowed=env.getProperty("wao.ai.discoveries.member-ids","");
        for(var row:db.rows("SELECT member_id FROM note_discovery_schedule")) {
            String member=string(row,"member_id");if(!allowed.isBlank()&&!Arrays.asList(allowed.split(",")).contains(member))continue;
            tx.executeWithoutResult(status -> {
                db.update("UPDATE ai_assistant_run r JOIN note_discovery_ai_attempt a ON a.ai_run_id=r.id JOIN note_mining_job j ON j.id=a.job_id SET r.prompt_text=NULL,r.response_text=NULL,r.web_context=NULL WHERE j.member_id=? AND j.origin='AUTO' AND j.finished_at IS NOT NULL AND ((j.status='SUCCEEDED' AND j.finished_at<TIMESTAMPADD(HOUR,-24,CURRENT_TIMESTAMP(6))) OR j.finished_at<TIMESTAMPADD(DAY,-7,CURRENT_TIMESTAMP(6)))",member);
                db.update("UPDATE note_mining_job SET input_cipher=NULL,input_nonce=NULL,candidate_json=NULL WHERE member_id=? AND origin='AUTO' AND finished_at IS NOT NULL AND ((status='SUCCEEDED' AND finished_at<TIMESTAMPADD(HOUR,-24,CURRENT_TIMESTAMP(6))) OR finished_at<TIMESTAMPADD(DAY,-7,CURRENT_TIMESTAMP(6)))",member);
                db.update("DELETE a FROM note_mining_artifact a LEFT JOIN note_discovery_feedback f ON f.member_id=a.member_id AND f.item_id=a.id WHERE a.member_id=? AND a.origin='AUTO' AND a.created_at<TIMESTAMPADD(DAY,-90,CURRENT_TIMESTAMP(6)) AND f.saved_at IS NULL",member);
                db.update("DELETE j FROM note_mining_job j LEFT JOIN note_mining_artifact a ON a.job_id=j.id WHERE j.member_id=? AND j.origin='AUTO' AND j.finished_at<TIMESTAMPADD(DAY,-90,CURRENT_TIMESTAMP(6)) AND a.id IS NULL",member);
                db.update("DELETE FROM note_discovery_daily_budget WHERE scope='MEMBER' AND scope_id=? AND budget_day<DATE(UTC_TIMESTAMP()+INTERVAL 8 HOUR)-INTERVAL 180 DAY",member);
            });
        }
        if(allowed.isBlank())db.update("DELETE FROM note_discovery_daily_budget WHERE scope='GLOBAL' AND budget_day<DATE(UTC_TIMESTAMP()+INTERVAL 8 HOUR)-INTERVAL 180 DAY");
    }
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException("发现状态无法序列化",ex);}}
    private JsonNode read(String value){try{return json.readTree(value);}catch(Exception ex){throw new IllegalArgumentException("PROTOCOL_ERROR");}}
    private List<String> readStrings(String value){try{return json.readValue(value,new TypeReference<List<String>>(){});}catch(Exception ex){throw new IllegalArgumentException("PROTOCOL_ERROR");}}
}
