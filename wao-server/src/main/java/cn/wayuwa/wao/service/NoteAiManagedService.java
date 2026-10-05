package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.dto.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 业务保存信号、阶段幂等与来源保护；模型执行复用已有队列。 */
@Service
public class NoteAiManagedService {
    private final JdbcTemplate db; private final ObjectMapper json; private final NoteAiSettingsService settings;
    private final ObjectProvider<KnowledgeService> knowledge; private final TextPolishJobService text; private final ArticleImageService image;
    private final NotePolishVersionService versions;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private NoteOriginalService originals;
    @org.springframework.beans.factory.annotation.Autowired
    private NoteTagService noteTags;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ObjectProvider<NoteAiCompanionService> companion;
    private static final ThreadLocal<Boolean> DERIVED=ThreadLocal.withInitial(()->false);
    private static final ThreadLocal<String> ACTOR=new ThreadLocal<>();
    /** 返回明确的内部调用身份；不回落到默认成员。 */
    public static String actor(){return ACTOR.get();}
    /** 统一锁顺序时识别派生写回。 */
    public static boolean derived(){return DERIVED.get();}
    private static final ThreadLocal<Boolean> NO_AUTO=ThreadLocal.withInitial(()->false);
    public NoteAiManagedService(JdbcTemplate db,ObjectMapper json,NoteAiSettingsService settings,ObjectProvider<KnowledgeService> knowledge,
            TextPolishJobService text,ArticleImageService image,NotePolishVersionService versions){
        this.db=db;this.json=json;this.settings=settings;this.knowledge=knowledge;this.text=text;this.image=image;this.versions=versions;
    }
    /** 通用 MCP 文档授权不隐式授权新的 AI 调用。 */
    public static <T> T withoutAuto(String member,java.util.function.Supplier<T> call){String previous=ACTOR.get();ACTOR.set(member);boolean old=NO_AUTO.get();NO_AUTO.set(true);try{return call.get();}finally{NO_AUTO.set(old);if(previous==null)ACTOR.remove();else ACTOR.set(previous);}}
    /** 明确的 AI 派生写回，避免成为新的人工来源与自动触发。 */
    public static <T> T derivedWrite(java.util.function.Supplier<T> call){boolean old=DERIVED.get();DERIVED.set(true);try{return call.get();}finally{DERIVED.set(old);}}
    /** 业务保存统一采用成员、文档的锁顺序。 */
    public void lockActor(String member){settings.lockMember(member);}
    private Map<String,Object> one(String sql,Object... args){var rows=db.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}
    private String str(Map<String,Object> row,String key){Object value=row.get(key);return value==null?"":value.toString();}
    private long num(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private String id(){return UUID.randomUUID().toString().replace("-","");}
    private String raw(DocDto doc){
        if(doc.content()!=null)return Objects.toString(doc.content().markdown(),"");
        if(doc.blocks()==null)return "";
        List<String> parts=new ArrayList<>();
        for(var b:doc.blocks()){if(b.h()!=null)parts.add("# "+b.h());if(b.p()!=null)parts.add(b.p());if(b.call()!=null)parts.add(b.call());if(b.ul()!=null)parts.addAll(b.ul());if(b.q()!=null)parts.addAll(b.q());}
        return String.join("\n\n",parts);
    }
    /** 只用比较副本判定触发，保存的原始内容不被格式化。 */
    public static String fingerprint(String markdown){return NoteAiSettingsService.hash(markdown.replaceAll("(?m)^#{1,6} +","").replaceAll("\\s+"," ").strip());}
    private String userBody(DocDto doc,String cover){String value=raw(doc);String prefix="![文章总览图]("+cover+" \"wao-image-size:50%xauto\")\n\n";return !cover.isBlank()&&value.startsWith(prefix)?value.substring(prefix.length()):value;}
    /** 正文与信号必须同事务；设置异常仅记录未确认策略，不能误报正文失败。 */
    public void saved(DocDto doc,String actor,String suppliedTitle,CreateNoteRequest selection){
        if(DERIVED.get()||!"kb-notes".equals(doc.kbId())||actor==null)return;
        if(!actor.equals(doc.author())){
            // 协作者编辑使原作者旧结果失效，但不能替原作者消费自动 AI。
            db.update("UPDATE note_ai_save_signal SET cycle_id=?,user_generation=user_generation+1,title_origin=IF(? IS NULL,title_origin,\'MANUAL\'),last_title=?,policy_json=\'{}\',source_rev=? WHERE doc_id=?",id(),suppliedTitle,doc.title(),doc.rev(),doc.id());
            return;
        }
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("小记信号必须与正文同事务");
        settings.lockMember(actor);
        Map<String,Object> previous=one("SELECT * FROM note_ai_save_signal WHERE doc_id=? FOR UPDATE",doc.id());
        String cover=previous==null?"":str(previous,"cover_url");
        var adopted=originals==null?null:originals.adopted(actor,doc);
        if(adopted!=null){
            if(adopted.cover()!=null)cover=adopted.cover();
            if(adopted.title())suppliedTitle=null;
        }
        String hash=fingerprint(userBody(doc,cover));
        if(previous!=null&&hash.equals(str(previous,"body_hash"))){
            if(suppliedTitle!=null){
                if(!Objects.equals(str(previous,"last_title"),doc.title()))db.update("UPDATE note_ai_save_signal SET title_origin='MANUAL',last_title=?,source_rev=? WHERE doc_id=?",doc.title(),doc.rev(),doc.id());}
            return;
        }
        String origin=previous==null?(suppliedTitle!=null&&!suppliedTitle.isBlank()?"MANUAL":"AUTO"):str(previous,"title_origin");
        if(previous!=null&&suppliedTitle!=null&&!Objects.equals(str(previous,"last_title"),doc.title()))origin="MANUAL";
        String policy;
        try{policy=settings.write(settings.get(actor).settings());}catch(RuntimeException e){policy="{}";}
        if(NO_AUTO.get())policy="{}";
        if(adopted!=null&&adopted.polish()&&!policy.equals("{}")){
            ObjectNode node=(ObjectNode)decode(policy,JsonNode.class);node.put("polish",false);policy=settings.write(node);
        }
        if(selection!=null&&!policy.equals("{}")){
            ObjectNode node=(ObjectNode)decode(policy,JsonNode.class);
            if(Boolean.FALSE.equals(selection.autoTitle()))node.put("title",false);
            if(Boolean.FALSE.equals(selection.autoImage()))node.put("image",false);
            if(Boolean.FALSE.equals(selection.autoPolish()))node.put("polish",false);
            policy=settings.write(node);
        }
        String cycle=id();long generation=previous==null?1:num(previous,"user_generation")+1;
        db.update("""
            INSERT INTO note_ai_save_signal(doc_id,member_id,user_generation,cycle_id,source_rev,title_origin,last_title,body_hash,policy_json,source_json,cover_url)
            VALUES(?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE user_generation=VALUES(user_generation),cycle_id=VALUES(cycle_id),
            source_rev=VALUES(source_rev),title_origin=VALUES(title_origin),last_title=VALUES(last_title),body_hash=VALUES(body_hash),policy_json=VALUES(policy_json),source_json=VALUES(source_json),cover_url=VALUES(cover_url),updated_at=CURRENT_TIMESTAMP(6)
            """,doc.id(),actor,generation,cycle,doc.rev(),origin,doc.title(),hash,policy,settings.write(doc),cover.isEmpty()?null:cover);
        if(policy.equals("{}")){
            project(doc,"SKIPPED","SKIPPED","SKIPPED",NO_AUTO.get()?"来源未授权自动 AI；原内容已保存":"正文已保存，自动整理设置暂不可确认；请读取设置后重试");return;
        }
        NoteAiSettingsService.Settings s=decode(policy,NoteAiSettingsService.Settings.class);
        for(String stage:List.of("title","image","polish","tags"))create(doc,cycle,stage,s,false,1,origin,cover);
        if(adopted!=null&&adopted.polish())db.update("UPDATE note_ai_stage_attempt SET reason_code='MANUAL_APPLIED',message='本次已采用手动润色，不重复自动润色' WHERE doc_id=? AND cycle_id=? AND stage='polish'",doc.id(),cycle);
        projectCurrent(doc);
    }
    private void create(DocDto doc,String cycle,String stage,NoteAiSettingsService.Settings s,boolean manual,int attempt,String origin,String cover){create(doc,doc.author(),cycle,stage,s,manual,attempt,origin,cover);}
    private void create(DocDto doc,String member,String cycle,String stage,NoteAiSettingsService.Settings s,boolean manual,int attempt,String origin,String cover){
        String body=userBody(doc,cover),code=null,message=null;
        if ("polish".equals(stage)) body=BookExcerptProtection.thoughts(body);
        String effective=body.strip();
        String letters=effective.replaceAll("!\\[[^]]*]\\([^)]*\\)","").replaceAll("\\[[^]]*]\\([^)]*\\)"," ").strip();
        if(!manual&&!s.enabled(stage)){code="DISABLED";message="已关闭保存后"+label(stage);}
        else if("title".equals(stage)&&!manual&&"MANUAL".equals(origin)){code="MANUAL_TITLE";message="保留手动标题";}
        else if("image".equals(stage)&&!manual&&!cover.isBlank()){code="EXISTING_IMAGE";message="已有自动配图，本次不重复生成";}
        else if(letters.isBlank()){code="NOT_APPLICABLE";message="暂无可整理文字";}
        else if("tags".equals(stage)&&noteTags.list().isEmpty()){code="NO_EXISTING_TAGS";message="暂无已有标签，不创建新标签";}
        else if("tags".equals(stage)&&effective.length()>12000){code="TAGS_TOO_LONG";message="标签推荐最多 12000 个实现字符，本次未标注";}
        else if("image".equals(stage)&&!manual&&effective.length()<80){code="SHORT_IMAGE_INPUT";message="自动配图暂需至少 80 个实现字符，可手动配图";}
        else if("polish".equals(stage)&&effective.length()>12000){code="BODY_TOO_LONG";message="整篇润色最多 12000 个实现字符；请编辑并选择不超过 4000 字符的片段润色";}
        else if("image".equals(stage)&&effective.length()>60000){code="IMAGE_TOO_LONG";message="配图最多 60000 个实现字符，请明确选择较短材料";}
        String input="title".equals(stage)&&body.length()>12000?body.substring(0,12000):body;
        if(code==null&&"title".equals(stage)&&body.length()>12000)message="标题将仅依据前 12000 个实现字符生成";
        db.update("INSERT INTO note_ai_stage_attempt(id,doc_id,member_id,cycle_id,stage,attempt_no,manual_stage,cancel_epoch,state,reason_code,message,source_json,input_text,policy_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id(),doc.id(),member,cycle,stage,attempt,manual,s.epoch(stage),code==null?"PENDING":"SKIPPED",code,message,settings.write(doc),input,settings.write(s));
        if(companion!=null)db.update("UPDATE note_ai_stage_attempt SET result_mode=? WHERE doc_id=? AND cycle_id=? AND stage=? AND attempt_no=?",companion.getObject().preference(member).get("mode"),doc.id(),cycle,stage,attempt);
    }
    private String label(String stage){return switch(stage){case "title"->"润色标题";case "image"->"自动配图";case "tags"->"自动标签";default->"润色正文";};}
    private List<Map<String,Object>> attempts(String doc,String cycle){return db.queryForList("SELECT a.* FROM note_ai_stage_attempt a WHERE doc_id=? AND cycle_id=? AND attempt_no=(SELECT MAX(b.attempt_no) FROM note_ai_stage_attempt b WHERE b.cycle_id=a.cycle_id AND b.stage=a.stage) ORDER BY CASE stage WHEN 'title' THEN 0 WHEN 'image' THEN 1 ELSE 2 END",doc,cycle);}
    private void project(DocDto doc,String title,String image,String polish,String message){db.update("""
        INSERT INTO note_ai_workflow(doc_id,member_id,base_rev,original_title,source_markdown_hash,title_status,image_status,polish_status,last_error)
        VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE base_rev=VALUES(base_rev),original_title=VALUES(original_title),source_markdown_hash=VALUES(source_markdown_hash),
        title_status=VALUES(title_status),image_status=VALUES(image_status),polish_status=VALUES(polish_status),last_error=VALUES(last_error),updated_at=CURRENT_TIMESTAMP(6)
        """,doc.id(),doc.author(),doc.rev(),doc.title(),NoteAiSettingsService.hash(raw(doc)),"SKIPPED","SKIPPED","SKIPPED",message);}
    private void projectCurrent(DocDto doc){Map<String,Object> signal=one("SELECT * FROM note_ai_save_signal WHERE doc_id=?",doc.id());if(signal==null)return;
        Map<String,String> state=new HashMap<>();List<String> messages=new ArrayList<>();
        for(var a:attempts(doc.id(),str(signal,"cycle_id"))){state.put(str(a,"stage"),str(a,"state"));if(!str(a,"message").isBlank())messages.add(label(str(a,"stage"))+"："+str(a,"message"));}
        project(doc,state.getOrDefault("title","SKIPPED"),state.getOrDefault("image","SKIPPED"),state.getOrDefault("polish","SKIPPED"),String.join("；",messages));
    }
    /** 服务端按当前代次接续；浏览器无需保持打开。 */
    @Transactional
    public boolean advance(String docId){
        var signal=one("SELECT * FROM note_ai_save_signal WHERE doc_id=?",docId);
        if(signal==null){
            // 未记录可靠来源的旧任务不重新提交或盲目应用，保留记录并给明确恢复入口。
            db.update("UPDATE note_ai_workflow SET title_status=IF(title_status IN ('PENDING','RUNNING'),'FAILED',title_status),image_status=IF(image_status IN ('PENDING','RUNNING'),'FAILED',image_status),polish_status=IF(polish_status IN ('PENDING','RUNNING'),'FAILED',polish_status),last_error='旧任务缺少可靠来源关联；正文保留，请重新保存或明确重试' WHERE doc_id=?",docId);
            return true;
        }
        String member=str(signal,"member_id");settings.lockMember(member);
        signal=one("SELECT * FROM note_ai_save_signal WHERE doc_id=? FOR UPDATE",docId);
        var row=one("SELECT id FROM doc WHERE id=? AND author_id=? AND kb_id='kb-notes' AND deleted_at IS NULL AND archived_at IS NULL",docId,member);
        if(row==null){db.update("UPDATE note_ai_stage_attempt SET state='SKIPPED',reason_code='SOURCE_UNAVAILABLE',message='小记已删除、归档或不可访问' WHERE doc_id=? AND state IN ('PENDING','RUNNING')",docId);return true;}
        DocDto doc=knowledge.getObject().getDoc(docId);NoteAiSettingsService.Settings current;
        try{current=settings.get(member).settings();}catch(RuntimeException e){return true;}
        // 旧轮运行占用仍查询终态；取消写回不假装已停止进程。
        for(var old:db.queryForList("SELECT * FROM note_ai_stage_attempt WHERE member_id=? AND doc_id=? AND state='RUNNING' AND (cycle_id<>? OR attempt_no<(SELECT MAX(b.attempt_no) FROM note_ai_stage_attempt b WHERE b.cycle_id=note_ai_stage_attempt.cycle_id AND b.stage=note_ai_stage_attempt.stage))",member,docId,str(signal,"cycle_id"))){
            if(terminal(old)){retain(old);if(companion!=null)companion.getObject().invalidate(str(old,"id"),"STALE");db.update("UPDATE note_ai_stage_attempt SET state='SKIPPED',reason_code='SOURCE_CHANGED',message='来源已更新，旧结果未采用' WHERE id=?",str(old,"id"));}}
        for(var a:attempts(docId,str(signal,"cycle_id"))){
            String stage=str(a,"stage"),state=str(a,"state");if(!List.of("PENDING","RUNNING").contains(state))continue;
            if(cancelled(a)){
                if(state.equals("PENDING")||terminal(a)){retain(a);if(companion!=null)companion.getObject().invalidate(str(a,"id"),"CANCELLED");db.update("UPDATE note_ai_stage_attempt SET state='SKIPPED',reason_code='CANCELLED',message='已停止，结果未采用' WHERE id=?",str(a,"id"));}
                continue;
            }
            boolean manual=Boolean.TRUE.equals(a.get("manual_stage"))||"1".equals(str(a,"manual_stage"));
            if(!manual&&(!current.enabled(stage)||current.epoch(stage)!=num(a,"cancel_epoch"))){
                if(state.equals("PENDING")||terminal(a)){retain(a);if(companion!=null)companion.getObject().invalidate(str(a,"id"),"CANCELLED");db.update("UPDATE note_ai_stage_attempt SET state='SKIPPED',reason_code='DISABLED',message='设置已关闭，结果不会采用' WHERE id=?",str(a,"id"));}
                else db.update("UPDATE note_ai_stage_attempt SET reason_code='CANCEL_PENDING',message='设置已关闭，等待调用收尾；结果不会采用' WHERE id=?",str(a,"id"));
                continue;
            }
            if(stage.equals("title")&&!manual&&str(signal,"title_origin").equals("MANUAL")){
                if(state.equals("PENDING")||terminal(a))db.update("UPDATE note_ai_stage_attempt SET state='SKIPPED',reason_code='MANUAL_TITLE',message='保留手动标题' WHERE id=?",str(a,"id"));continue;}
            if(state.equals("PENDING")){
                String stageMember=str(a,"member_id");
                settings.lockMember(stageMember);
                var executingSettings=manual?settings.get(stageMember).settings():current;
                Integer running=db.queryForObject("SELECT COUNT(*) FROM note_ai_stage_attempt WHERE member_id=? AND state='RUNNING'",Integer.class,stageMember);
                if(running>=executingSettings.capacity()){db.update("UPDATE note_ai_stage_attempt SET reason_code='MEMBER_CAPACITY',message='等待成员执行名额' WHERE id=?",str(a,"id"));continue;}
                submit(a);continue;
            }
            doc=knowledge.getObject().getDoc(docId);signal=one("SELECT * FROM note_ai_save_signal WHERE doc_id=?",docId);complete(a,doc,signal);
        }
        projectCurrent(knowledge.getObject().getDoc(docId));return true;
    }
    private boolean terminal(Map<String,Object> a){if(str(a,"job_id").isEmpty())return true;
        try{return "image".equals(str(a,"stage"))?!List.of("PENDING","QUEUED","RUNNING").contains(image.get(str(a,"member_id"),str(a,"job_id")).status()):!List.of("QUEUED","RUNNING").contains(text.get(str(a,"member_id"),str(a,"job_id")).status());}catch(NotFoundException e){return true;}}
    private void submit(Map<String,Object> a){
        if(originals!=null&&!"tags".equals(str(a,"stage")))originals.freezeAttempt(str(a,"id"));
        String stage=str(a,"stage"),job;
        NoteAiSettingsService.Settings s=decode(str(a,"policy_json"),NoteAiSettingsService.Settings.class);
        if(stage.equals("image"))job=image.startNoteWorkflow(str(a,"member_id"),new ArticleImageRequest(str(a,"input_text"),s.imagePrompt().isBlank()?"为这篇小记生成简洁的 16:9 总览图；只表达已有主题，不编造事实。":s.imagePrompt(),s.model().isBlank()?null:s.model(),str(a,"id"))).id();
        else if(stage.equals("tags")){
            List<String> allowed=noteTags.list();
            if(allowed.isEmpty()){db.update("UPDATE note_ai_stage_attempt SET state='SKIPPED',reason_code='NO_EXISTING_TAGS',message='暂无已有标签，不创建新标签' WHERE id=?",str(a,"id"));return;}
            job=text.submit(str(a,"member_id"),str(a,"id"),"NOTE_TAGS",str(a,"input_text"),s.model().isBlank()?null:s.model(),null,null,allowed).id();
        }
        else job=text.submit(str(a,"member_id"),str(a,"id"),stage.equals("title")?"NOTE_TITLE":"NOTE_BODY",str(a,"input_text"),s.model().isBlank()?null:s.model(),s.titlePrompt(),s.polishPrompt()).id();
            db.update("UPDATE note_ai_stage_attempt SET state='RUNNING',job_id=?,reason_code='QUEUED',message='已入队，关闭页面后仍会继续' WHERE id=?",job,str(a,"id"));
    }
    private void fail(Map<String,Object> a,String message){db.update("UPDATE note_ai_stage_attempt SET state='FAILED',reason_code='EXECUTION_FAILED',message=? WHERE id=?",message==null?"执行失败，请重试":message.substring(0,Math.min(500,message.length())),str(a,"id"));}
    private boolean cancelled(Map<String,Object> a){return Boolean.TRUE.equals(a.get("cancel_requested"))||"1".equals(str(a,"cancel_requested"));}
    private void retain(Map<String,Object> a){if(companion==null||str(a,"job_id").isBlank())return;try{
        if("image".equals(str(a,"stage"))){var r=image.get(str(a,"member_id"),str(a,"job_id"));if("SUCCEEDED".equals(r.status()))companion.getObject().capture(a,r.imageUrl());}
        else{var r=text.get(str(a,"member_id"),str(a,"job_id"));if("SUCCEEDED".equals(r.status()))companion.getObject().capture(a,Objects.toString(r.polished(),""));}
    }catch(NotFoundException ignored){}}
    private void complete(Map<String,Object> a,DocDto doc,Map<String,Object> signal){
        String stage=str(a,"stage"),status,output,message;
        if(stage.equals("image")){var result=image.get(str(a,"member_id"),str(a,"job_id"));status=result.status();output=result.imageUrl();message=result.message();}
        else{var result=text.get(str(a,"member_id"),str(a,"job_id"));status=result.status();output=result.polished();message=result.message();}
        if(status.equals("FAILED")){fail(a,message);return;}
        if(!status.equals("SUCCEEDED")){db.update("UPDATE note_ai_stage_attempt SET reason_code=?,message=? WHERE id=?",status,status.equals("RUNNING")?"正在执行":"正在模型队列等待",str(a,"id"));return;}
        if(output==null||output.isBlank()&&!stage.equals("tags")){fail(a,"AI 未返回可用结果");return;}
        if(companion!=null)companion.getObject().capture(a,Objects.toString(output,""));
        if(companion!=null&&companion.getObject().notNeeded(str(a,"id"))){db.update("UPDATE note_ai_stage_attempt SET state='DONE',reason_code='NOT_NEEDED',message='已分析，本次无需修改' WHERE id=?",str(a,"id"));return;}
        if(stage.equals("title")&&!Objects.equals(doc.title(),decode(str(a,"source_json"),DocDto.class).title())){
            db.update("UPDATE note_ai_stage_attempt SET state='SKIPPED',reason_code='MANUAL_TITLE',message='标题在等待期间已更新，旧结果未采用' WHERE id=?",str(a,"id"));return;
        }
        if(!fingerprint(userBody(doc,str(signal,"cover_url"))).equals(str(signal,"body_hash"))){fail(a,"来源已更新，旧结果未采用");return;}
        if(companion!=null&&"REVIEW".equals(str(a,"result_mode"))){db.update("UPDATE note_ai_stage_attempt SET state='DONE',reason_code='READY',message='建议已准备，等待你采用' WHERE id=?",str(a,"id"));return;}
        PatchDocRequest patch;
        if(stage.equals("tags")){
            var allowed=noteTags.lockExisting();
            var selected=Objects.toString(output,"").lines().map(String::strip).filter(allowed::contains).distinct().limit(3).toList();
            if(selected.isEmpty()){db.update("UPDATE note_ai_stage_attempt SET state='SKIPPED',reason_code='NO_MATCHING_TAGS',message='没有合适的已有标签，本次未标注' WHERE id=?",str(a,"id"));return;}
            var merged=new LinkedHashSet<>(doc.tags()==null?List.<String>of():doc.tags());
            for(String tag:selected)if(merged.size()<20)merged.add(tag);
            patch=new PatchDocRequest(null,null,null,null,null,doc.rev(),List.copyOf(merged));
        }
        else if(stage.equals("title"))patch=new PatchDocRequest(output,null,null,null,null,doc.rev());
        else{
            RichDocContentDto content;
            if(stage.equals("image"))content=withCover(doc.content(),output,str(signal,"cover_url"));
            else{
                String source=str(a,"input_text");
                // 原图片、链接与附件引用必须仍存在，不接受丢失资源的结果。
                java.util.regex.Matcher links=java.util.regex.Pattern.compile("!?\\[[^]]*]\\(([^)]+)\\)").matcher(source);
                while(links.find())if(!output.contains(links.group(1))){fail(a,"润色结果未完整保留图片、链接或附件；当前正文未改变");return;}
                output=BookExcerptProtection.reassemble(userBody(doc,str(signal,"cover_url")),output);
                String cover=str(signal,"cover_url");String body=cover.isBlank()?output:"![文章总览图]("+cover+" \"wao-image-size:50%xauto\")\n\n"+output;
                if(companion!=null){try{content=companion.getObject().polishedContent(doc,body);}catch(ConflictException unsafe){db.update("UPDATE note_ai_stage_attempt SET state='DONE',reason_code='REVIEW_REQUIRED',message=? WHERE id=?",unsafe.getMessage(),str(a,"id"));return;}}
                else content=new RichDocContentDto(1,null,body);
                if(doc.author().equals(str(a,"member_id")))versions.save(str(a,"member_id"),doc.id(),new SaveNotePolishVersionRequest(raw(doc),body,str(a,"job_id")));
            }
            patch=new PatchDocRequest(null,null,content,null,null,doc.rev());
        }
        boolean old=DERIVED.get();DERIVED.set(true);
        DocDto saved;
        try{saved=knowledge.getObject().updateDerivedNote(doc.id(),patch);}finally{DERIVED.set(old);}
        if(companion!=null)companion.getObject().applied(str(a,"id"),saved);
        if(originals!=null&&!stage.equals("tags"))originals.result(str(a,"member_id"),str(a,"id"),str(a,"job_id"),originals.content(saved),saved.rev(),"APPLIED");
        if(stage.equals("title"))db.update("UPDATE note_ai_save_signal SET last_title=? WHERE doc_id=?",output,doc.id());
        if(stage.equals("image"))db.update("UPDATE note_ai_save_signal SET cover_url=? WHERE doc_id=?",output,doc.id());
        if(stage.equals("polish"))db.update("UPDATE note_ai_save_signal SET body_hash=? WHERE doc_id=?",fingerprint(output),doc.id());
        db.update("UPDATE note_ai_stage_attempt SET state='DONE',reason_code='APPLIED',message='已完成' WHERE id=?",str(a,"id"));
    }
    private RichDocContentDto withCover(RichDocContentDto original,String url,String previous){
        String markdown=original==null?"":Objects.toString(original.markdown(),"");
        String prefix="![文章总览图]("+previous+" \"wao-image-size:50%xauto\")\n\n";
        if(!previous.isBlank()&&markdown.startsWith(prefix))markdown=markdown.substring(prefix.length());
        JsonNode document=original==null||original.document()==null?null:original.document().deepCopy();
        if(document instanceof ObjectNode root){
            ArrayNode nodes=root.withArray("content");
            if(!previous.isBlank()&&nodes.size()>0&&"image".equals(nodes.get(0).path("type").asText())&&previous.equals(nodes.get(0).path("attrs").path("src").asText()))nodes.remove(0);
            ObjectNode node=json.createObjectNode();node.put("type","image");node.putObject("attrs").put("src",url).put("alt","文章总览图").put("width","50%").put("align","center");root.withArray("content").insert(0,node);}
        return new RichDocContentDto(1,document,"![文章总览图]("+url+" \"wao-image-size:50%xauto\")\n\n"+markdown);
    }
    /** 单项手动重做独立于自动开关，失败重试只处理指定阶段。 */
    @Transactional
    public NoteAiWorkflowService.Status manual(String member,String docId,String stage,Integer expectedRev,String requestId){
        if(!List.of("title","image","polish","tags").contains(stage)||requestId==null||!requestId.matches("[a-f0-9]{32}"))throw new BadRequestException("阶段或请求标识无效");
        settings.lockMember(member);DocDto doc=knowledge.getObject().getDoc(docId);
        if(!"kb-notes".equals(doc.kbId()))throw new NotFoundException("小记不存在");
        var existing=one("SELECT * FROM note_ai_stage_attempt WHERE id=?",requestId);
        if(existing!=null){if(!member.equals(str(existing,"member_id"))||!docId.equals(str(existing,"doc_id"))||!stage.equals(str(existing,"stage")))throw new ConflictException("请求标识已用于另一操作");return status(docId,member);}
        if(expectedRev==null||!expectedRev.equals(doc.rev()))throw new ConflictException("小记已更新，请读取最新内容后重试");
        var signal=one("SELECT * FROM note_ai_save_signal WHERE doc_id=?",docId);
        if(signal==null){withoutAuto(doc.author(),()->{saved(doc,doc.author(),doc.title(),null);return null;});signal=one("SELECT * FROM note_ai_save_signal WHERE doc_id=?",docId);}
        String cycle=str(signal,"cycle_id");int attempt=Optional.ofNullable(db.queryForObject("SELECT MAX(attempt_no) FROM note_ai_stage_attempt WHERE cycle_id=? AND stage=?",Integer.class,cycle,stage)).orElse(0)+1;
        create(doc,member,cycle,stage,settings.get(member).settings(),true,attempt,str(signal,"title_origin"),str(signal,"cover_url"));
        db.update("UPDATE note_ai_stage_attempt SET id=? WHERE cycle_id=? AND stage=? AND attempt_no=?",requestId,cycle,stage,attempt);
        projectCurrent(doc);return status(docId,member);
    }
    /** 自动失败重试仍遵守当前开关；无可靠策略的保存需显式接续。 */
    @Transactional
    public NoteAiWorkflowService.Status retry(String member,String docId){
        settings.lockMember(member);DocDto doc=knowledge.getObject().getDoc(docId);if(!member.equals(doc.author()))throw new NotFoundException("小记不存在");
        var signal=one("SELECT * FROM note_ai_save_signal WHERE doc_id=?",docId);
        if(signal==null||str(signal,"policy_json").equals("{}")){db.update("DELETE FROM note_ai_save_signal WHERE doc_id=?",docId);saved(doc,member,doc.title(),null);}
        else for(var a:attempts(docId,str(signal,"cycle_id")))if(str(a,"state").equals("FAILED"))create(doc,str(signal,"cycle_id"),str(a,"stage"),settings.get(member).settings(),false,(int)num(a,"attempt_no")+1,str(signal,"title_origin"),str(signal,"cover_url"));
        projectCurrent(doc);return status(docId,member);
    }
    /** 按篇返回阶段原因，不携带私人正文。 */
    public NoteAiWorkflowService.Status status(String docId,String member){
        if(member==null)throw new UnauthorizedException("请先登录");
        var signal=one("SELECT s.* FROM note_ai_save_signal s JOIN doc d ON d.id=s.doc_id WHERE s.doc_id=? AND d.deleted_at IS NULL",docId);
        if(signal==null)return null;
        if(!member.equals(str(signal,"member_id"))&&one("SELECT id FROM note_ai_stage_attempt WHERE doc_id=? AND member_id=? LIMIT 1",docId,member)==null)return null;
        Map<String,NoteAiWorkflowService.StageDetail> details=new LinkedHashMap<>();Map<String,String> states=new HashMap<>();List<String> messages=new ArrayList<>();
        for(var a:attempts(docId,str(signal,"cycle_id"))){String stage=str(a,"stage"),message=str(a,"message"),code=str(a,"reason_code"),state=str(a,"state");
            states.put(stage,state);details.put(stage,new NoteAiWorkflowService.StageDetail(code,message,state.equals("FAILED"),code.equals("BODY_TOO_LONG")?"SELECT_TEXT":"RETRY",str(a,"job_id"),(int)num(signal,"source_rev")));if(!message.isBlank())messages.add(label(stage)+"："+message);}
        if(details.isEmpty())messages.add("正文已保存，自动整理设置暂不可确认；请读取设置后重试");
        return new NoteAiWorkflowService.Status(docId,states.getOrDefault("title","SKIPPED"),states.getOrDefault("image","SKIPPED"),states.getOrDefault("polish","SKIPPED"),String.join("；",messages),details,states.getOrDefault("tags","SKIPPED"));
    }
    /** 返回成员自己发起的协作小记阶段；不包含其他人的私人输入。 */
    public List<NoteAiWorkflowService.Status> sharedStatuses(String member){
        return db.queryForList("SELECT DISTINCT a.doc_id FROM note_ai_stage_attempt a JOIN doc d ON d.id=a.doc_id WHERE a.member_id=? AND d.author_id<>? AND d.deleted_at IS NULL LIMIT 200",member,member).stream().map(a->status(str(a,"doc_id"),member)).filter(Objects::nonNull).toList();
    }
    /** 已排队但未启动的自动任务在关闭或来源更新后不能消费模型。 */
    @Transactional
    public boolean mayStart(String runId) {
        var a=one("SELECT a.* FROM note_ai_stage_attempt a LEFT JOIN text_polish_job t ON t.id=a.job_id WHERE a.job_id=? OR t.ai_run_id=?",runId,runId);
        if(a==null){
            return one("SELECT doc_id FROM note_ai_workflow WHERE image_run_id=? OR title_job_id IN (SELECT id FROM text_polish_job WHERE ai_run_id=?) OR polish_job_id IN (SELECT id FROM text_polish_job WHERE ai_run_id=?)",runId,runId,runId)==null;
        }
        String member=str(a,"member_id");settings.lockMember(member);
        var signal=one("SELECT cycle_id,title_origin FROM note_ai_save_signal WHERE doc_id=?",str(a,"doc_id"));
        if(one("SELECT id FROM doc WHERE id=? AND deleted_at IS NULL AND archived_at IS NULL",str(a,"doc_id"))==null)return false;
        Integer latest=db.queryForObject("SELECT MAX(attempt_no) FROM note_ai_stage_attempt WHERE cycle_id=? AND stage=?",Integer.class,str(a,"cycle_id"),str(a,"stage"));
        if(latest==null||latest!=num(a,"attempt_no"))return false;
        boolean manual=Boolean.TRUE.equals(a.get("manual_stage"))||"1".equals(str(a,"manual_stage"));
        var s=settings.get(member).settings();
        return !cancelled(a)&&signal!=null&&(manual||!"title".equals(str(a,"stage"))||!"MANUAL".equals(str(signal,"title_origin")))&&str(signal,"cycle_id").equals(str(a,"cycle_id"))&&(manual||s.enabled(str(a,"stage"))&&s.epoch(str(a,"stage"))==num(a,"cancel_epoch"));
    }
    /** 仅查询当前运行归属的持久取消信号，不涉及共用 worker 或其他任务。 */
    public boolean cancellationRequested(String runId){var a=one("SELECT a.* FROM note_ai_stage_attempt a LEFT JOIN text_polish_job t ON t.id=a.job_id WHERE a.job_id=? OR t.ai_run_id=?",runId,runId);return a!=null&&cancelled(a);}
    /** 推进事务回滚后独立保存真实错误；不提交半个入队事务。 */
    @Transactional
    public void failed(String docId,RuntimeException error){
        var signal=one("SELECT * FROM note_ai_save_signal WHERE doc_id=?",docId);if(signal==null)return;
        settings.lockMember(str(signal,"member_id"));
        String message=error instanceof BadRequestException||error instanceof NotFoundException||error instanceof ConflictException?error.getMessage():"后台整理暂不可用（"+error.getClass().getSimpleName()+"），正文已保存；请重试";
        for(var a:attempts(docId,str(signal,"cycle_id")))if(List.of("PENDING","RUNNING").contains(str(a,"state"))){
            boolean active=false;
            if("RUNNING".equals(str(a,"state")))try{active=!terminal(a);}catch(RuntimeException unavailable){active=true;}
            if(active)db.update("UPDATE note_ai_stage_attempt SET reason_code='STATUS_UNAVAILABLE',message=? WHERE id=?",message,str(a,"id"));
            else fail(a,message);
            break;
        }
        projectCurrent(knowledge.getObject().getDoc(docId));
    }
    /** 是否由统一保存工作流管理。 */
    public boolean managed(String docId){return one("SELECT doc_id FROM note_ai_save_signal WHERE doc_id=?",docId)!=null;}
    private <T>T decode(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException("小记持久状态格式异常",e);}}
}
