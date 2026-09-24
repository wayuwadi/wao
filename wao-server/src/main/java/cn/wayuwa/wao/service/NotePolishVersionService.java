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
    private final KnowledgeService knowledge;
    private final NotePolishVersionMapper versions;

    public NotePolishVersionService(KnowledgeService knowledge, NotePolishVersionMapper versions) {
        this.knowledge = knowledge;
        this.versions = versions;
    }

    /** 只列出当前成员的润色对照，不返回其他人的 AI 结果。 */
    public List<NotePolishVersionDto> list(String memberId) {
        return versions.listOwned(memberId).stream()
                .map(version -> new NotePolishVersionDto(version.docId(), version.sourceMarkdown(),
                        version.polishedMarkdown())).toList();
    }

    /** 只有源正文仍与已保存文档一致时才接受 AI 结果，重复提交相同结果可安全覆盖。 */
    @Transactional
    public NotePolishVersionDto save(String memberId, String docId, SaveNotePolishVersionRequest request) {
        versions.lockDoc(docId);
        DocDto doc = knowledge.getDoc(docId);
        if (!KnowledgeService.NOTES_KB_ID.equals(doc.kbId())) throw new BadRequestException("只有小记支持自动润色对照");
        if (doc.content() == null || !Objects.equals(request.sourceMarkdown(), doc.content().markdown())) {
            throw new ConflictException("小记正文已更新，旧润色结果未覆盖新内容；请重新润色");
        }
        String polished = request.polishedMarkdown().trim();
        if (polished.isBlank()) throw new BadRequestException("润色结果为空，请重试");
        versions.upsert(new NotePolishVersionMapper.Version(docId, memberId,
                request.sourceMarkdown(), polished));
        return new NotePolishVersionDto(docId, request.sourceMarkdown(), polished);
    }
}
