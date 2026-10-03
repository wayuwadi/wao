package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.NotePolishVersionDto;
import cn.wayuwa.wao.dto.SaveNotePolishVersionRequest;
import cn.wayuwa.wao.mapper.NotePolishVersionMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 保存小记的原文和润色版；AI 结果不会直接覆盖用户可编辑的正文。 */
@Service
public class NotePolishVersionService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private NoteOriginalService originals;
    private final KnowledgeService knowledge;
    private final NotePolishVersionMapper versions;
    @org.springframework.beans.factory.annotation.Autowired
    private TextPolishJobService jobs;
    @org.springframework.beans.factory.annotation.Autowired
    private cn.wayuwa.wao.mapper.TextPolishJobMapper jobMapper;

    public NotePolishVersionService(KnowledgeService knowledge, NotePolishVersionMapper versions) {
        this.knowledge = knowledge;
        this.versions = versions;
    }

    /** 只列出当前成员的润色对照，不返回其他人的 AI 结果。 */
    public List<NotePolishVersionDto> list(String memberId) {
        if(memberId==null)throw new cn.wayuwa.wao.common.UnauthorizedException("请先登录");
        return versions.listOwned(memberId).stream()
                .map(version -> new NotePolishVersionDto(version.docId(), version.sourceMarkdown(),
                        version.polishedMarkdown())).toList();
    }

    public record ReadingVersion(String docId,Integer sourceRev,String polishedMarkdown){}
    /** 只返回仍有效的阅读结果与当前版本，不全量传输私人来源正文。 */
    public List<ReadingVersion> reading(String member){
        if(member==null)throw new cn.wayuwa.wao.common.UnauthorizedException("请先登录");
        return versions.listOwned(member).stream().map(v->{DocDto doc=knowledge.getDoc(v.docId());return doc.content()!=null&&Objects.equals(v.sourceMarkdown(),doc.content().markdown())?new ReadingVersion(v.docId(),doc.rev(),v.polishedMarkdown()):null;}).filter(Objects::nonNull).toList();
    }
    /** 只有源正文仍与已保存文档一致时才接受 AI 结果，重复提交相同结果可安全覆盖。 */
    @Transactional
    public NotePolishVersionDto save(String memberId, String docId, SaveNotePolishVersionRequest request) {
        if(memberId==null)throw new cn.wayuwa.wao.common.UnauthorizedException("请先登录");
        if(originals!=null)originals.archiveLegacy(memberId,docId);
        versions.lockDoc(docId);
        DocDto doc = knowledge.getDoc(docId);
        if(!Objects.equals(doc.author(),memberId))throw new cn.wayuwa.wao.common.NotFoundException("小记不存在");
        if (!KnowledgeService.NOTES_KB_ID.equals(doc.kbId())) throw new BadRequestException("只有小记支持自动润色对照");
        if (doc.content() == null || !Objects.equals(request.sourceMarkdown(), doc.content().markdown())) {
            throw new ConflictException("小记正文已更新，旧润色结果未覆盖新内容；请重新润色");
        }
        String polished = request.polishedMarkdown().trim();
        if (polished.isBlank()) throw new BadRequestException("润色结果为空，请重试");
        String jobId=request.aiJobId();
        if(jobId!=null) {
            var job=jobMapper.findOwned(jobId,memberId);
            if(job==null||!"NOTE_BODY".equals(job.kind())||!body(job.originalText()).equals(body(request.sourceMarkdown())))
                throw new BadRequestException("润色来源任务与本小记不一致");
            var result=jobs.get(memberId,jobId);
            if(!"SUCCEEDED".equals(result.status())||!body(polished).equals(body(result.polished())))
                throw new BadRequestException("润色结果与已完成任务不一致");
        }
        versions.upsert(new NotePolishVersionMapper.Version(docId, memberId,
                request.sourceMarkdown(), polished));
        versions.markVerified(docId,memberId,jobId,jobId==null?null:NoteDiscoveryIndexService.hash(body(request.sourceMarkdown())),
                jobId==null?null:NoteDiscoveryIndexService.hash(body(polished)),jobId==null?null:doc.rev());
        return new NotePolishVersionDto(docId, request.sourceMarkdown(), polished);
    }
    private String body(String text) { return NoteDiscoveryIndexService.normalize(text==null?"":text.replaceFirst("^!\\[文章总览图]\\([^\\n]+\\)\\s*","")); }
}
