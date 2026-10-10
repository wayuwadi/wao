package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.dto.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 私人不可变来源；首次记录和每轮实际输入分别关联，不用最新正文反推原稿。 */
@Service
public class NoteOriginalService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final NoteAiSettingsService settings;
    private final ObjectProvider<KnowledgeService> knowledge;
    private final TextPolishJobService text;
    private final ArticleImageService images;
    public NoteOriginalService(JdbcTemplate db,ObjectMapper json,NoteAiSettingsService settings,ObjectProvider<KnowledgeService> knowledge,TextPolishJobService text,ArticleImageService images){this.db=db;this.json=json;this.settings=settings;this.knowledge=knowledge;this.text=text;this.images=images;}
    public record Content(String title,String markdown,JsonNode document,List<DocBlockDto> blocks,List<DocLinkDto> links){}
    public record Input(String requestId,String stableId,String docId,Integer sourceRev,String stage,Content content,String inputText,JsonNode selection){}
    public record Snapshot(String id,String title,String markdown,JsonNode document,List<DocBlockDto> blocks,List<DocLinkDto> links,String sourceType,Integer sourceRev,String createdAt){}
    public record Originals(Snapshot before,Snapshot initial,String explanation,Integer appliedRev){}
    public record DraftResult(String taskId,Content content){}
    private Map<String,Object> one(String sql,Object...args){var rows=db.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}
    private String str(Map<String,Object> row,String key){Object v=row.get(key);return v==null?null:v.toString();}
    private <T>T read(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException("来源快照读取失败",e);}}
    private void member(String member){if(member==null||member.isBlank())throw new UnauthorizedException("请先登录");}
    private DocDto accessible(String member,String id){member(member);if(one("SELECT id FROM doc WHERE id=? AND kb_id='kb-notes' AND deleted_at IS NULL",id)==null)throw new NotFoundException("小记不存在");DocDto doc=knowledge.getObject().getDoc(id);if(!"kb-notes".equals(doc.kbId()))throw new NotFoundException("小记不存在");return doc;}
    /** 提取无损字段；旧 blocks 单独留存，不冒充富文本。 */
    public Content content(DocDto doc){return new Content(doc.title(),doc.content()==null?"":Objects.toString(doc.content().markdown(),""),doc.content()==null?null:doc.content().document(),doc.blocks(),doc.links());}
    private String snapshot(String member,String stableId,String docId,String key,String type,Integer rev,Long generation,Content content){
        String encoded=settings.write(content),hash=NoteAiSettingsService.hash(encoded);
        var old=one("SELECT * FROM note_source_snapshot WHERE member_id=? AND stable_id=? AND source_key=?",member,stableId,key);
        if(old!=null){if(!hash.equals(str(old,"content_hash")))throw new ConflictException("来源标识对应的内容已改变，请创建新请求");return str(old,"id");}
        String id=UUID.randomUUID().toString().replace("-","");
        db.update("INSERT INTO note_source_snapshot(id,doc_id,stable_id,member_id,source_key,source_type,source_rev,user_generation,title,markdown,content_json,content_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",id,docId,stableId,member,key,type,rev,generation,content.title(),Objects.toString(content.markdown(),""),encoded,hash);
        var refs=java.util.regex.Pattern.compile("/api/(images|files)/([A-Za-z0-9_-]{1,32})/").matcher(encoded);
        while(refs.find()){
            String kind=refs.group(1),asset=refs.group(2),table=kind.equals("images")?"image_blob":"file_blob";
            // 与显式删除使用同一资产行锁，防止保存来源与删除发生竞态。
            if(one("SELECT id FROM "+table+" WHERE id=? FOR UPDATE",asset)!=null)
                db.update("INSERT IGNORE INTO note_source_asset_ref VALUES(?,?,?)",id,kind,asset);
        }
        return id;
    }
    /** 原稿引用的现存资产不能被删除；不改变既有资产读取权限。 */
    public void requireAssetUnused(String kind,String id){
        String table=switch(kind){case "images"->"image_blob";case "files"->"file_blob";default->throw new BadRequestException("资产类型无效");};
        one("SELECT id FROM "+table+" WHERE id=? FOR UPDATE",id);
        if(one("SELECT snapshot_id FROM note_source_asset_ref WHERE asset_type=? AND asset_id=? LIMIT 1",kind,id)!=null)
            throw new ConflictException("该资产仍被私人小记原稿引用，不能删除");
    }
    /** 保存第一份可靠记录；绑定保存前的输入时不把 AI 文案认成用户原稿。 */
    @Transactional
    public void saved(DocDto doc,String member,boolean firstSave,String rawTitle){
        if(member==null||!"kb-notes".equals(doc.kbId()))return;
        settings.lockMember(member);
        db.update("UPDATE note_source_snapshot SET doc_id=? WHERE stable_id=? AND member_id=? AND doc_id IS NULL",doc.id(),doc.id(),member);
        db.update("UPDATE note_ai_transformation SET doc_id=? WHERE stable_id=? AND member_id=? AND doc_id IS NULL",doc.id(),doc.id(),member);
        var existing=one("SELECT snapshot_id FROM note_original_record WHERE doc_id=? AND member_id=?",doc.id(),member);
        if(existing!=null)return;
        var draft=one("SELECT s.id FROM note_source_snapshot s JOIN note_ai_transformation t ON t.source_snapshot_id=s.id WHERE s.doc_id=? AND s.member_id=? AND s.source_type='DRAFT_BEFORE_AI' ORDER BY s.created_at,s.id LIMIT 1",doc.id(),member);
        if(draft!=null){db.update("INSERT IGNORE INTO note_original_record VALUES(?,?,?,?)",doc.id(),member,str(draft,"id"),"INITIAL");return;}
        // 旧文只在实际加工时留存，不能把现在的 AI 正文标称为最初记录。
        if(firstSave){Content value=content(doc);value=new Content(rawTitle==null?"":rawTitle,value.markdown(),value.document(),value.blocks(),value.links());
            String id=snapshot(member,doc.id(),doc.id(),"FIRST_SAVE","USER_INITIAL",doc.rev(),1L,value);
            db.update("INSERT IGNORE INTO note_original_record VALUES(?,?,?,?)",doc.id(),member,id,"INITIAL");}
    }
    /** 实际保存时确认用户明确采用的草稿结果；不把结果准备完成当成已写入文档。 */
    @Transactional
    public void appliedInputs(String member,DocDto doc,List<String> requests){
        if(requests==null||requests.isEmpty())return;member(member);settings.lockMember(member);
        if(requests.size()>30)throw new BadRequestException("单次来源关联过多");
        for(String request:requests){var t=one("SELECT * FROM note_ai_transformation WHERE id=? AND member_id=? AND stable_id=? FOR UPDATE",request,member,doc.id());
            if(t==null||str(t,"output_json")==null)throw new ConflictException("采用结果缺少可靠来源，输入保留");
            if(List.of("APPLIED","MIXED_APPLIED").contains(str(t,"application")))continue;
            Content output=read(str(t,"output_json"),Content.class);
            String application=Objects.equals(output.markdown(),content(doc).markdown())?"APPLIED":"MIXED_APPLIED";
            db.update("UPDATE note_ai_transformation SET doc_id=?,application=?,applied_rev=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",doc.id(),application,doc.rev(),request);
        }
    }
    public record Adopted(boolean title,boolean polish,String cover){}
    /** 识别本次真实采用的阶段；AI 派生内容不再触发同一阶段的自动处理。 */
    public Adopted adopted(String member,DocDto doc){
        boolean title=false,polish=false;String cover=null;
        for(var t:db.queryForList("SELECT * FROM note_ai_transformation WHERE doc_id=? AND member_id=? AND applied_rev=? AND application IN ('APPLIED','MIXED_APPLIED') ORDER BY updated_at,id",doc.id(),member,doc.rev())){
            Content output=read(str(t,"output_json"),Content.class);
            if("title".equals(str(t,"stage"))&&Objects.equals(output.title(),doc.title()))title=true;
            if("polish".equals(str(t,"stage"))&&Objects.equals(output.markdown(),content(doc).markdown()))polish=true;
            if("image".equals(str(t,"stage"))){String url=images.get(member,str(t,"task_id")).imageUrl();if(url!=null&&content(doc).markdown().contains(url))cover=url;}
        }
        return new Adopted(title,polish,cover);
    }
    /** 在提交模型之前冻结完整来源和精确模型输入；同请求重试只返回原关联。 */
    @Transactional
    public String freeze(String member,Input input){
        member(member);settings.lockMember(member);
        if(input==null||input.requestId()==null||!input.requestId().matches("[a-f0-9]{32}")||input.stableId()==null||!input.stableId().matches("[A-Za-z0-9_-]{1,32}")||!List.of("title","image","polish").contains(input.stage()))throw new BadRequestException("来源请求无效");
        if(input.content()==null||input.content().markdown()==null||input.content().markdown().length()>1_000_000||input.inputText()==null||input.inputText().length()>60_000)throw new BadRequestException("来源内容无效或过长");
        if(settings.write(input.content()).length()>4_000_000)throw new BadRequestException("来源结构过大");
        knowledge.getObject().validateNoteSource(new RichDocContentDto(1,input.content().document(),input.content().markdown()));
        String type="DRAFT_BEFORE_AI";
        if(input.docId()==null&&one("SELECT id FROM doc WHERE id=?",input.stableId())!=null)throw new BadRequestException("已保存小记必须提供来源版本");
        DocDto current=input.docId()==null?null:accessible(member,input.docId());
        if(current!=null&&!input.stableId().equals(input.docId()))throw new BadRequestException("来源不属于该小记");
        String actualInput=input.inputText().trim();
        var existing=one("SELECT * FROM note_ai_transformation WHERE id=?",input.requestId());
        if(existing!=null){if(!member.equals(str(existing,"member_id"))||!input.stableId().equals(str(existing,"stable_id"))||!input.stage().equals(str(existing,"stage"))||!NoteAiSettingsService.hash(actualInput).equals(str(existing,"input_hash"))||!Objects.equals(input.selection()==null?null:settings.write(input.selection()),str(existing,"selection_json")))throw new ConflictException("请求标识已用于另一输入");
            var source=one("SELECT content_hash FROM note_source_snapshot WHERE id=?",str(existing,"source_snapshot_id"));
            if(!NoteAiSettingsService.hash(settings.write(input.content())).equals(str(source,"content_hash")))throw new ConflictException("完整来源已改变，请创建新请求");
            return str(existing,"source_snapshot_id");}
        if(current!=null){if(!Objects.equals(current.rev(),input.sourceRev()))throw new ConflictException("小记已更新，来源未覆盖当前内容");type="BEFORE_AI";}
        if(input.docId()!=null)archiveLegacy(member,input.docId());
        String id=snapshot(member,input.stableId(),input.docId(),"AI:"+input.requestId(),type,input.sourceRev(),null,input.content());
        db.update("INSERT INTO note_ai_transformation(id,member_id,stable_id,doc_id,stage,source_snapshot_id,input_text,input_hash,selection_json) VALUES(?,?,?,?,?,?,?,?,?)",input.requestId(),member,input.stableId(),input.docId(),input.stage(),id,actualInput,NoteAiSettingsService.hash(actualInput),input.selection()==null?null:settings.write(input.selection()));
        if(input.docId()!=null)db.update("INSERT IGNORE INTO note_original_record VALUES(?,?,?,?)",input.docId(),member,id,"FIRST_RETAINED");
        return id;
    }
    /** 服务端阶段冻结，来源来自真实持久 attempt，而非完成时的文档。 */
    @Transactional
    public void freezeAttempt(String attempt){
        var a=one("SELECT * FROM note_ai_stage_attempt WHERE id=?",attempt);if(a==null)throw new NotFoundException("阶段不存在");
        DocDto source=read(str(a,"source_json"),DocDto.class);
        String member=str(a,"member_id");settings.lockMember(member);
        var existing=one("SELECT id FROM note_ai_transformation WHERE id=? AND member_id=?",attempt,member);if(existing!=null)return;
        var signal=one("SELECT user_generation FROM note_ai_save_signal WHERE doc_id=?",source.id());
        Long generation=signal==null?null:((Number)signal.get("user_generation")).longValue();
        archiveLegacy(member,source.id());
        String sourceKey="ROUND:"+NoteAiSettingsService.hash(str(a,"cycle_id")+settings.write(content(source)));
        String snapshot=snapshot(member,source.id(),source.id(),sourceKey,"BEFORE_AI",source.rev(),generation,content(source));
        String actualInput=str(a,"input_text").trim();
        db.update("INSERT INTO note_ai_transformation(id,member_id,stable_id,doc_id,stage,source_snapshot_id,input_text,input_hash) VALUES(?,?,?,?,?,?,?,?)",attempt,member,source.id(),source.id(),str(a,"stage"),snapshot,actualInput,NoteAiSettingsService.hash(actualInput));
        db.update("INSERT IGNORE INTO note_original_record VALUES(?,?,?,?)",source.id(),member,snapshot,"FIRST_RETAINED");
    }
    /** 写回同事务保存精确结果；快照或关联缺失必须拒绝覆盖正文。 */
    @Transactional
    public void result(String member,String request,String task,Content output,Integer rev,String application){
        var t=one("SELECT * FROM note_ai_transformation WHERE id=? AND member_id=? FOR UPDATE",request,member);if(t==null)throw new ConflictException("没有可靠的润色来源，结果不能应用");
        String encoded=settings.write(output);
        if(str(t,"output_json")!=null&&!encoded.equals(str(t,"output_json")))throw new ConflictException("同一任务结果不一致");
        if("DRAFT_RESULT".equals(application)&&List.of("APPLIED","MIXED_APPLIED").contains(str(t,"application")))return;
        db.update("UPDATE note_ai_transformation SET task_id=?,output_json=?,application=?,applied_rev=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",task,encoded,application,rev,request);
    }
    /** 草稿应用前保留结果，不更新正文；任务必须归属于当前成员。 */
    @Transactional
    public void draftResult(String member,String request,DraftResult result){
        member(member);settings.lockMember(member);
        var t=one("SELECT * FROM note_ai_transformation WHERE id=? AND member_id=?",request,member);if(t==null)throw new NotFoundException("来源不存在");
        if(str(t,"doc_id")!=null)accessible(member,str(t,"doc_id"));
        if(result==null||result.content()==null||result.taskId()==null)throw new BadRequestException("结果无效");
        knowledge.getObject().validateNoteSource(new RichDocContentDto(1,result.content().document(),result.content().markdown()));
        // 只接受真实已完成的本人成果，不接受客户端伪造任务关联。
        String stage=str(t,"stage"),output;
        if("image".equals(stage)){var job=images.get(member,result.taskId());if(!"SUCCEEDED".equals(job.status())||!request.equals(result.taskId()))throw new ConflictException("配图任务未完成或不属于输入");output=job.imageUrl();}
        else{var job=text.get(member,result.taskId());if(!("pj"+request).equals(result.taskId())||!"SUCCEEDED".equals(job.status()))throw new ConflictException("润色任务未完成或不属于输入");output=job.polished();
            var submitted=one("SELECT original_text FROM text_polish_job WHERE id=? AND member_id=?",result.taskId(),member);
            String expected="title".equals(stage)?"sha256:"+NoteAiSettingsService.hash(str(t,"input_text")):str(t,"input_text");
            if(submitted==null||!expected.equals(str(submitted,"original_text")))throw new ConflictException("模型输入与快照不一致");}
        if(output==null||!("title".equals(stage)?output.strip().equals(result.content().title()):retainsOutput(result.content().markdown(),output,"image".equals(stage))))throw new ConflictException("结果没有保留对应 AI 输出");
        result(member,request,result.taskId(),result.content(),null,"DRAFT_RESULT");
    }
    // Tiptap 会重排软换行、段间空白并转义标点；只接受完整输出的展示等价，保留所有文字、数字与链接。
    private static boolean retainsOutput(String markdown,String output,boolean image){
        if(markdown==null||output.isBlank())return false;
        if(image)return markdown.contains(output);
        return presentationText(markdown).contains(presentationText(output));
    }
    private static String presentationText(String markdown){
        return markdown.replaceAll("\\\\([!\"#$%&'()*+,./:;<=>?@\\[\\]\\\\^_`{|}~-])","$1")
                .replaceAll("(?U)\\s+"," ").strip();
    }
    /** 兼容写入前幂等归档尚存的旧对照，历史 migration 和当前正文不变。 */
    @Transactional
    public void archiveLegacy(String member,String doc){
        accessible(member,doc);settings.lockMember(member);
        var old=one("SELECT * FROM note_polish_version WHERE doc_id=? AND member_id=?",doc,member);if(old==null)return;
        Content source=new Content(null,str(old,"source_markdown"),null,List.of(),List.of());
        String key="LEGACY:"+NoteAiSettingsService.hash(str(old,"updated_at")+str(old,"source_markdown")+str(old,"polished_markdown"));
        String retained=snapshot(member,doc,doc,key,"LEGACY_BEFORE_AI",null,null,source);
        db.update("INSERT IGNORE INTO note_original_record VALUES(?,?,?,?)",doc,member,retained,"FIRST_RETAINED");
    }
    /** 仅返回仍属于当前正文的可信生成图片标识，不依据用户可编辑 alt。 */
    public String cover(String member,String doc){
        if(doc==null)return null;DocDto current=accessible(member,doc);
        var signal=one("SELECT cover_url FROM note_ai_save_signal WHERE doc_id=?",doc);String url=signal==null?null:str(signal,"cover_url");
        return url!=null&&current.content()!=null&&current.content().markdown().startsWith("![文章总览图]("+url+" \"wao-image-size:50%xauto\")")?url:null;
    }
    private Snapshot view(Map<String,Object> row){if(row==null)return null;Content c=read(str(row,"content_json"),Content.class);Object rev=row.get("source_rev");return new Snapshot(str(row,"id"),c.title(),c.markdown(),c.document(),c.blocks(),c.links(),str(row,"source_type"),rev==null?null:((Number)rev).intValue(),str(row,"created_at"));}
    /** 按篇按需查询；共享当前正文不自动共享私人历史。 */
    @Transactional(readOnly=true)
    public Originals originals(String member,String doc){
        accessible(member,doc);
        var t=one("SELECT * FROM note_ai_transformation WHERE doc_id=? AND member_id=? AND stage='polish' AND application IN ('APPLIED','MIXED_APPLIED') ORDER BY updated_at DESC,id DESC LIMIT 1",doc,member);
        Snapshot before=t==null?null:view(one("SELECT * FROM note_source_snapshot WHERE id=? AND member_id=?",str(t,"source_snapshot_id"),member));
        Snapshot initial=view(one("SELECT s.*,r.authenticity FROM note_original_record r JOIN note_source_snapshot s ON s.id=r.snapshot_id WHERE r.doc_id=? AND r.member_id=?",doc,member));
        String explanation=initial==null?"没有留存更早的原稿，无法恢复":initial.sourceType().equals("USER_INITIAL")||initial.sourceType().equals("DRAFT_BEFORE_AI")?"最初记录在首次 AI 加工前留存":"首次留存内容可能已包含之前的 AI 文案";
        if(before==null){var old=one("SELECT source_markdown,updated_at FROM note_polish_version WHERE doc_id=? AND member_id=?",doc,member);
            if(old!=null)before=new Snapshot("legacy",null,str(old,"source_markdown"),null,List.of(),List.of(),"LEGACY_BEFORE_AI",null,str(old,"updated_at"));}
        Integer rev=t==null||t.get("applied_rev")==null?null:((Number)t.get("applied_rev")).intValue();
        return new Originals(before,initial,explanation,rev);
    }
    /** 指定快照也重新校验文档、成员和来源关联。 */
    @Transactional(readOnly=true)
    public Snapshot get(String member,String doc,String snapshot){accessible(member,doc);var row=one("SELECT * FROM note_source_snapshot WHERE id=? AND doc_id=? AND member_id=?",snapshot,doc,member);if(row==null)throw new NotFoundException("原文不存在");return view(row);}
}
