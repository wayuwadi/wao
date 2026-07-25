package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import cn.wayuwa.wao.domain.DocPresenceEntity;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.DocPeerDto;
import cn.wayuwa.wao.dto.DocPresenceDto;
import cn.wayuwa.wao.dto.DocPresenceRequest;
import cn.wayuwa.wao.mapper.DocPresenceMapper;
import cn.wayuwa.wao.mapper.MemberMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档协同在场服务（见 ADR-017）。
 *
 * <p>「谁正打开这篇文档、光标停在第几行」由客户端定期心跳上报，落在共享的
 * {@code doc_presence} 表里，因此两台节点上的用户能互相看见。超过
 * {@link #STALE_SECONDS} 秒没有心跳即视为已离开 —— 关标签页、断网、进程被杀都能自愈，
 * 不依赖客户端一定发出「离开」请求。
 *
 * <p>心跳同时承担「有没有别人改过这篇文档」的问询：客户端带上手里的 rev，服务端只在
 * 版本落后时把整篇正文捎回去，避免每次心跳都搬一遍正文。
 */
@Service
public class DocPresenceService {

    /** 在场窗口：最后一次心跳超过这个秒数就算离开（客户端心跳间隔的数倍，容忍抖动）。 */
    public static final int STALE_SECONDS = 20;

    private final DocPresenceMapper presenceMapper;
    private final MemberMapper memberMapper;
    private final KnowledgeService knowledge;

    public DocPresenceService(DocPresenceMapper presenceMapper, MemberMapper memberMapper, KnowledgeService knowledge) {
        this.presenceMapper = presenceMapper;
        this.memberMapper = memberMapper;
        this.knowledge = knowledge;
    }

    /**
     * 上报一次在场心跳并取回同刻的协作者。
     *
     * @param docId    文档 id
     * @param memberId 当前成员 id
     * @param req      心跳内容（光标行、是否在输入、客户端 rev）
     * @param now      当前时间
     * @return 协作者列表 + 服务端版本号（版本落后时附带最新文档）
     */
    @Transactional
    public DocPresenceDto heartbeat(String docId, String memberId, DocPresenceRequest req, LocalDateTime now) {
        DocDto doc = knowledge.getDoc(docId);
        if (memberId != null) {
            touch(docId, memberId, req, now);
        }
        List<DocPeerDto> peers = peers(docId, memberId, now);
        boolean stale = req.rev() == null || !req.rev().equals(doc.rev());
        return new DocPresenceDto(peers, doc.rev(), stale ? doc : null);
    }

    /**
     * 主动离开一篇文档（关闭页面 / 切走）。
     *
     * <p>只是让协作者的头像立刻消失，不发这一下也会在在场窗口过期后自动消失。
     *
     * @param docId    文档 id
     * @param memberId 当前成员 id
     */
    public void leave(String docId, String memberId) {
        if (memberId != null) {
            presenceMapper.deleteById(DocPresenceEntity.keyOf(docId, memberId));
        }
    }

    private void touch(String docId, String memberId, DocPresenceRequest req, LocalDateTime now) {
        String key = DocPresenceEntity.keyOf(docId, memberId);
        DocPresenceEntity e = presenceMapper.selectById(key);
        boolean isNew = e == null;
        if (isNew) {
            e = new DocPresenceEntity();
            e.setId(key);
            e.setDocId(docId);
            e.setMemberId(memberId);
        }
        e.setLine(req.line() == null ? -1 : req.line());
        e.setEditing(Boolean.TRUE.equals(req.editing()));
        e.setSeenAt(now);
        if (isNew) {
            presenceMapper.insert(e);
        } else {
            presenceMapper.updateById(e);
        }
    }

    private List<DocPeerDto> peers(String docId, String selfId, LocalDateTime now) {
        LocalDateTime cutoff = now.minusSeconds(STALE_SECONDS);
        List<DocPresenceEntity> alive = presenceMapper.selectList(new LambdaQueryWrapper<DocPresenceEntity>()
                        .eq(DocPresenceEntity::getDocId, docId)
                        .ge(DocPresenceEntity::getSeenAt, cutoff))
                .stream().filter(p -> !p.getMemberId().equals(selfId)).toList();
        if (alive.isEmpty()) {
            return List.of();
        }
        Map<String, MemberEntity> members = memberMapper.selectBatchIds(alive.stream()
                        .map(DocPresenceEntity::getMemberId).distinct().toList())
                .stream().collect(Collectors.toMap(MemberEntity::getId, Function.identity(), (a, b) -> a));
        return alive.stream().map(p -> {
            MemberEntity m = members.get(p.getMemberId());
            String name = m == null ? p.getMemberId() : m.getName();
            String initial = m == null || m.getInitial() == null ? name.substring(0, 1) : m.getInitial();
            String color = m == null || m.getColor() == null ? "#9A9DA9" : m.getColor();
            return new DocPeerDto(p.getMemberId(), name, initial, color, p.getLine(), p.getEditing());
        }).toList();
    }

    /**
     * 清掉过期的在场记录，避免表随时间无限长大。
     *
     * @param now 当前时间
     * @return 清掉的记录数
     */
    public int sweep(LocalDateTime now) {
        return presenceMapper.delete(new LambdaQueryWrapper<DocPresenceEntity>()
                .lt(DocPresenceEntity::getSeenAt, now.minusSeconds(STALE_SECONDS * 10L)));
    }
}
