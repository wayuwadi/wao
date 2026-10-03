package cn.wayuwa.wao.service;

import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.mapper.NoteDiscoveryMapper;
import static cn.wayuwa.wao.mapper.NoteDiscoveryMapper.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;

/** 正文保存事务中的可靠合并信号；不调用 AI，不依赖 Controller 或浏览器事件。 */
@Service
public class NoteDiscoveryChangeService {
    private final NoteDiscoveryMapper db;
    private final NoteDiscoveryIndexService index;
    private final Environment env;
    public NoteDiscoveryChangeService(NoteDiscoveryMapper db,NoteDiscoveryIndexService index,Environment env) { this.db=db;this.index=index;this.env=env; }

    /** 与文档写入共用事务，用户事实更新与 AI 派生更新独立建模。 */
    public void saved(DocEntity doc,String origin) {
        if (!"kb-notes".equals(doc.getKbId()) || doc.getAuthorId()==null || Boolean.TRUE.equals(doc.getAuto())) return;
        String allowed=env.getProperty("wao.ai.discoveries.document-ids","");
        if(!allowed.isBlank()&&!Arrays.asList(allowed.split(",")).contains(doc.getId()))return;
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("发现信号必须与正文同事务");
        String member=doc.getAuthorId(),text=index.text(doc),hash=NoteDiscoveryIndexService.hash(text);
        var schedule=db.lock(member);
        var before=db.one("SELECT * FROM note_discovery_note_state WHERE doc_id=?",doc.getId());
        boolean eligible=db.eligible(member,doc.getId());
        boolean changed=before==null||!hash.equals(string(before,"body_fingerprint"));
        boolean metadata= !changed;
        // 已验证润色保存成为当前内容时不再次当成用户的新事实。
        if("USER".equals(origin)) {
            var derived=db.one("SELECT * FROM note_polish_version WHERE doc_id=? AND member_id=?",doc.getId(),member);
            if(derived!=null) {
                String polished=NoteDiscoveryIndexService.normalize(Objects.toString(string(derived,"polished_markdown"),"").replaceFirst("^!\\[文章总览图]\\([^\\n]+\\)\\s*",""));
                if(polished.equals(text))origin=hash.equals(string(derived,"verified_output_fingerprint"))&&derived.get("verified_ai_job_id")!=null?"AI_DERIVED":"LEGACY_UNKNOWN";
            }
        }
        long seq=number(schedule,"change_seq");
        boolean fact=changed&&"USER".equals(origin)&&eligible&&!text.isBlank();
        if(fact) {
            db.update("UPDATE note_discovery_schedule SET change_seq=change_seq+1,next_run_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)) WHERE member_id=?",
                    env.getProperty("wao.ai.discoveries.stable-seconds",Integer.class,120),member);
            seq++;
        }
        String provenance=metadata&&before!=null?string(before,"provenance"):before==null&&!"USER".equals(origin)?"LEGACY_UNKNOWN":origin;
        long keepSeq=before==null?seq:number(before,"change_seq");
        db.update("""
            INSERT INTO note_discovery_note_state(doc_id,member_id,doc_rev,body_fingerprint,user_body_fingerprint,user_basis_rev,
            change_origin,provenance,change_seq,stable_after,eligible,execution_scope)
            VALUES(?,?,?,?,?,?,?,?,?,TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),?,?)
            ON DUPLICATE KEY UPDATE doc_rev=VALUES(doc_rev),body_fingerprint=VALUES(body_fingerprint),
            user_body_fingerprint=COALESCE(VALUES(user_body_fingerprint),user_body_fingerprint),
            user_basis_rev=COALESCE(VALUES(user_basis_rev),user_basis_rev),change_origin=VALUES(change_origin),
            provenance=VALUES(provenance),change_seq=VALUES(change_seq),stable_after=VALUES(stable_after),eligible=VALUES(eligible),indexed_rev=NULL
            """,doc.getId(),member,doc.getRev()==null?1:doc.getRev(),hash,fact?hash:null,fact?doc.getRev():null,
            metadata?"METADATA":origin,provenance,fact?seq:keepSeq,
            env.getProperty("wao.ai.discoveries.stable-seconds",Integer.class,120),eligible,env.getProperty("wao.ai.discoveries.execution-scope",""));
        if(changed||!eligible) {
            db.update("UPDATE note_mining_artifact a JOIN note_mining_artifact_source s ON s.artifact_id=a.id SET a.validity='INVALID',a.invalid_reason=? WHERE s.doc_id=? AND a.origin='AUTO' AND a.validity='VALID'",eligible?"SOURCE_CHANGED":"SOURCE_UNAVAILABLE",doc.getId());
            db.update("DELETE FROM note_discovery_index_term WHERE doc_id=?",doc.getId());
            if(!fact&&changed)db.update("UPDATE note_discovery_schedule SET next_run_at=LEAST(next_run_at,TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6))) WHERE member_id=?",120,member);
        } else {
            db.update("UPDATE note_mining_artifact_source SET doc_rev=? WHERE doc_id=? AND body_fingerprint=?",doc.getRev(),doc.getId(),hash);
        }
    }
}
