package cn.wayuwa.wao.service;

import cn.wayuwa.wao.dto.DocDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.regex.Pattern;

/** 文档与小记素材的持久私有标记，移除引用不会重新公开。 */
@Service
public class NoteAssetPrivacyService {
    /** 第二张图入库前识别持久阶段归属，避免产生短暂可匿名访问的图片。 */
    public boolean thinkingImageRun(String runId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM note_ai_stage_attempt WHERE stage='thinking' AND job_id=?",Integer.class,runId)>0;
    }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private static final Pattern ASSET = Pattern.compile("/api/(images|files)/([A-Za-z0-9_-]{1,64})/");

    public NoteAssetPrivacyService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }

    /** 保存小记时将正文中的图片及附件标为私有。 */
    public void saved(DocDto doc) {
        var matcher=ASSET.matcher(json.valueToTree(doc).toString());
        while(matcher.find()) mark(matcher.group(1),matcher.group(2));
    }

    /** 判断素材是否源自小记；数据库异常直接失败，不回落到匿名公开。 */
    public boolean isPrivate(String type,String id) {
        if (!type.equals("images") && !type.equals("files")) return false;
        if (jdbc.queryForObject("SELECT COUNT(*) FROM private_note_asset WHERE asset_type=? AND asset_id=?",Integer.class,type,id)>0) return true;
        // 未保存的图文草稿以及旧原文也属于私有内容。
        boolean found = type.equals("images") && jdbc.queryForObject("SELECT COUNT(*) FROM image_blob WHERE id=? AND (source LIKE '小记%' OR source LIKE '文档%')",Integer.class,id)>0;
        if(!found && type.equals("images")) found=jdbc.queryForObject("SELECT COUNT(*) FROM image_blob i WHERE i.id=? AND (EXISTS(SELECT 1 FROM note_ai_workflow w WHERE i.source=CONCAT('文章总览图 · ',w.image_run_id)) OR EXISTS(SELECT 1 FROM note_ai_transformation t WHERE i.source=CONCAT('文章总览图 · ',t.task_id)))",Integer.class,id)>0;
        if(!found) found=jdbc.queryForObject("SELECT COUNT(*) FROM note_source_asset_ref WHERE asset_type IN (?,?) AND asset_id=?",Integer.class,type,type.equals("images")?"image":"file",id)>0;
        if(!found) found=jdbc.queryForObject("SELECT COUNT(*) FROM doc WHERE CONCAT_WS('',content_markdown,content_json,blocks_json) LIKE ?",Integer.class,"%/api/"+type+"/"+id+"/%")>0;
        if(found) mark(type,id);
        return found;
    }

    private void mark(String type,String id) { jdbc.update("INSERT IGNORE INTO private_note_asset(asset_type,asset_id) VALUES (?,?)",type,id); }
    /** 上传私人小记附件时立即标记，保存正文之前也不允许匿名读取。 */
    public void privateUpload(String type,String id) { if(!type.equals("images")&&!type.equals("files"))throw new IllegalArgumentException("素材类型无效");mark(type,id); }
    /** 移入回收站时撤销所有分享；恢复小记不会恢复旧的公开权限。 */
    public void deleted(String docId) { jdbc.update("UPDATE note_public_share SET revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(6)) WHERE doc_id=?",docId); }
}
