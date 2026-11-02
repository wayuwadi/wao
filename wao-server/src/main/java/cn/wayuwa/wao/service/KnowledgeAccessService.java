package cn.wayuwa.wao.service;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.KbEntity;
import cn.wayuwa.wao.mapper.KbMapper;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** One authorization policy for HTTP, workspace tokens and member-bound background reads. */
@Service
public class KnowledgeAccessService {
    private final KbMapper kbs;
    private final CurrentMember members;
    private static final ThreadLocal<String> ACTOR = new ThreadLocal<>();

    public KnowledgeAccessService(KbMapper kbs, CurrentMember members) {
        this.kbs = kbs;
        this.members = members;
    }

    public static <T> T asMember(String member, Supplier<T> action) {
        String previous = ACTOR.get();
        ACTOR.set(member == null ? "" : member);
        try { return action.get(); }
        finally { if (previous == null) ACTOR.remove(); else ACTOR.set(previous); }
    }

    public String actor() {
        if (ACTOR.get() != null) return ACTOR.get();
        if (NoteAiManagedService.actor() != null) return NoteAiManagedService.actor();
        var attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes request ? members.id(request.getRequest()) : null;
    }

    /** Only trusted in-process jobs without a request or delegated member are system calls. */
    public boolean systemCall() {
        return ACTOR.get() == null && NoteAiManagedService.actor() == null
                && RequestContextHolder.getRequestAttributes() == null;
    }

    public boolean visible(KbEntity kb, String member) {
        if (kb == null || member == null || member.isBlank()) return false;
        // Notes have their own author-level behavior; they are not a user-created knowledge base.
        if (Boolean.TRUE.equals(kb.getNotes())) return true;
        return Objects.equals(member, kb.getOwnerId()) || "成员可编辑".equals(kb.getPerm());
    }

    public boolean visible(String kbId, String member) { return kbId != null && visible(kbs.selectById(kbId), member); }
    public boolean visible(KbEntity kb) { return systemCall() || visible(kb, actor()); }
    public boolean visible(String kbId) { return systemCall() || visible(kbId, actor()); }

    public void require(String kbId) {
        if (!visible(kbId)) throw new NotFoundException("知识库不存在或无权访问");
    }

    public void require(String kbId, String member) {
        if (!visible(kbId, member)) throw new NotFoundException("知识库不存在或无权访问");
    }

    public void requireOwner(KbEntity kb) {
        if (!systemCall() && (kb == null || actor() == null || !actor().equals(kb.getOwnerId())))
            throw new NotFoundException("知识库不存在或无权管理");
    }
}
