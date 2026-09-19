package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import cn.wayuwa.wao.domain.DocPresenceEntity;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.DocPresenceDto;
import cn.wayuwa.wao.dto.DocPresenceRequest;
import cn.wayuwa.wao.mapper.DocPresenceMapper;
import cn.wayuwa.wao.mapper.MemberMapper;
import org.junit.jupiter.api.Test;

class DocPresenceServiceTest {

    private final DocPresenceMapper presenceMapper = mock(DocPresenceMapper.class);
    private final MemberMapper memberMapper = mock(MemberMapper.class);
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final DocPresenceService service = new DocPresenceService(presenceMapper, memberMapper, knowledge);

    private final LocalDateTime now = LocalDateTime.of(2026, 7, 25, 10, 0);

    private DocDto docWithRev(int rev) {
        return new DocDto("d1", "文档", "m1", null, null, false, List.of(), List.of(), List.of(), "k1", "f1", rev);
    }

    private DocPresenceEntity presence(String memberId, int line, LocalDateTime seenAt) {
        DocPresenceEntity e = new DocPresenceEntity();
        e.setId(DocPresenceEntity.keyOf("d1", memberId));
        e.setDocId("d1");
        e.setMemberId(memberId);
        e.setLine(line);
        e.setEditing(true);
        e.setSeenAt(seenAt);
        return e;
    }

    private MemberEntity member(String id, String name) {
        MemberEntity m = new MemberEntity();
        m.setId(id);
        m.setName(name);
        m.setInitial(name.substring(0, 1));
        m.setColor("#4358D6");
        return m;
    }

    @Test
    void heartbeatReportsOtherPeopleOnTheSameDocWithTheLineTheyAreOn() {
        when(knowledge.getDocRevision("d1")).thenReturn(4);
        when(presenceMapper.selectById(any())).thenReturn(null);
        when(presenceMapper.selectList(any())).thenReturn(List.of(presence("m2", 3, now), presence("m1", 1, now)));
        when(memberMapper.selectBatchIds(any())).thenReturn(List.of(member("m2", "娃弟")));

        DocPresenceDto dto = service.heartbeat("d1", "m1", new DocPresenceRequest(1, true, 4), now);

        assertThat(dto.peers()).hasSize(1);
        assertThat(dto.peers().get(0).id()).isEqualTo("m2");
        assertThat(dto.peers().get(0).name()).isEqualTo("娃弟");
        assertThat(dto.peers().get(0).line()).isEqualTo(3);
    }

    @Test
    void heartbeatSkipsTheBodyWhenTheClientIsAlreadyOnTheServerRevision() {
        when(knowledge.getDocRevision("d1")).thenReturn(7);
        when(presenceMapper.selectById(any())).thenReturn(null);
        when(presenceMapper.selectList(any())).thenReturn(List.of());

        DocPresenceDto same = service.heartbeat("d1", "m1", new DocPresenceRequest(0, false, 7), now);
        assertThat(same.doc()).isNull();
        assertThat(same.rev()).isEqualTo(7);

        verify(knowledge, never()).getDoc(any());
        when(knowledge.getDoc("d1")).thenReturn(docWithRev(8));
        DocPresenceDto stale = service.heartbeat("d1", "m1", new DocPresenceRequest(0, false, 6), now);
        assertThat(stale.doc()).isNotNull();
        assertThat(stale.rev()).isEqualTo(8);
        verify(knowledge).getDoc("d1");
    }

    @Test
    void heartbeatUpsertsInsteadOfPilingUpRowsForTheSameReader() {
        when(knowledge.getDocRevision("d1")).thenReturn(1);
        when(presenceMapper.selectById("d1:m1")).thenReturn(presence("m1", 0, now.minusSeconds(5)));
        when(presenceMapper.selectList(any())).thenReturn(List.of());

        service.heartbeat("d1", "m1", new DocPresenceRequest(9, true, 1), now);

        verify(presenceMapper).updateById(any(DocPresenceEntity.class));
    }

    @Test
    void leaveRemovesOnlyTheCallersOwnPresence() {
        service.leave("d1", "m1");
        verify(presenceMapper).deleteById("d1:m1");
    }
}
