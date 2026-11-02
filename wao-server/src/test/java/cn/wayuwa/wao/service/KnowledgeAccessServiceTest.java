package cn.wayuwa.wao.service;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.KbEntity;
import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.dto.*;
import cn.wayuwa.wao.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class KnowledgeAccessServiceTest {
    final KbMapper kbs = mock(KbMapper.class);
    final DocMapper docs = mock(DocMapper.class);
    final KbFolderMapper folders = mock(KbFolderMapper.class);
    final KnowledgeAccessService access = new KnowledgeAccessService(kbs, mock(CurrentMember.class));
    final KnowledgeService knowledge = new KnowledgeService(mock(KbGroupMapper.class), kbs, folders,
            docs, mock(DocTemplateMapper.class), mock(MemberMapper.class), new ObjectMapper(),
            mock(FeishuTaskNotifier.class), mock(FeishuChatArchiveVersionService.class));

    KnowledgeAccessServiceTest() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "test"), DocEntity.class);
        ReflectionTestUtils.setField(knowledge, "access", access);
    }
    KbEntity library() {
        KbEntity kb = new KbEntity(); kb.setId("private-library"); kb.setOwnerId("owner");
        kb.setPerm("仅自己"); kb.setName("Private library");
        when(kbs.selectById(kb.getId())).thenReturn(kb);
        return kb;
    }

    @Test void privateUnknownAndAnonymousAreDeniedWhileOwnerCanRead() {
        KbEntity kb = library();
        assertThat(access.visible(kb, "owner")).isTrue();
        assertThat(access.visible(kb, "other")).isFalse();
        assertThat(access.visible(kb, null)).isFalse();
        kb.setOwnerId(null);
        assertThat(access.visible(kb, "owner")).isFalse();
        assertThatThrownBy(() -> KnowledgeAccessService.asMember(null, () -> knowledge.getKb(kb.getId())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test void sharingAndRevocationApplyOnEveryReadAndOnlyOwnerManages() {
        KbEntity kb = library(); kb.setPerm("成员可编辑");
        when(folders.selectList(any())).thenReturn(List.of()); when(docs.selectList(any())).thenReturn(List.of());
        assertThat(KnowledgeAccessService.asMember("other", () -> knowledge.getKb(kb.getId())).id()).isEqualTo(kb.getId());
        assertThatThrownBy(() -> KnowledgeAccessService.asMember("other", () -> knowledge.updateKb(kb.getId(),
                new PatchKbRequest(null, null, null, null, null, "仅自己")))).isInstanceOf(NotFoundException.class);
        KnowledgeAccessService.asMember("owner", () -> knowledge.updateKb(kb.getId(),
                new PatchKbRequest(null, null, null, null, null, "仅自己")));
        assertThatThrownBy(() -> KnowledgeAccessService.asMember("other", () -> knowledge.getKb(kb.getId())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test void listsDirectDocumentPresenceAndTrashCannotBypassLibrary() {
        KbEntity kb = library();
        DocEntity doc = new DocEntity(); doc.setId("secret"); doc.setKbId(kb.getId()); doc.setFolderId("folder");
        when(kbs.selectList(any())).thenReturn(List.of(kb));
        when(folders.selectList(any())).thenReturn(List.of());
        when(docs.selectList(any())).thenReturn(List.of(doc)); when(docs.selectById("secret")).thenReturn(doc);
        when(docs.selectAliveKbId("secret")).thenReturn(kb.getId());
        KnowledgeAccessService.asMember("other", () -> {
            assertThat(knowledge.listKbs()).isEmpty(); assertThat(knowledge.listDocs()).isEmpty();
            assertThat(knowledge.listDocCatalog()).isEmpty(); assertThat(knowledge.listTrash()).isEmpty();
            assertThatThrownBy(() -> knowledge.getDoc("secret")).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> knowledge.getDocRevision("secret")).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> knowledge.restore("doc", "secret")).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> knowledge.purge("doc", "secret")).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> knowledge.createFolder(kb.getId(), new CreateFolderRequest("injected"))).isInstanceOf(NotFoundException.class);
            return null;
        });
        verify(docs, never()).deleteById("secret");
    }

    @Test void newLibraryIgnoresClientSharedDefaultAndUsesDelegatedActor() {
        when(kbs.selectCount(any())).thenReturn(0L);
        KbDto result = KnowledgeAccessService.asMember("creator", () -> knowledge.createKb(
                new CreateKbRequest("New library", null, null, null, null, null, "成员可编辑")));
        assertThat(result.ownerId()).isEqualTo("creator"); assertThat(result.perm()).isEqualTo("仅自己");
    }

    @Test void anonymousHttpNeverFallsBackToInternalJobAndContextIsRestored() {
        library();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            assertThat(access.systemCall()).isFalse();
            assertThatThrownBy(() -> access.require("private-library")).isInstanceOf(NotFoundException.class);
            KnowledgeAccessService.asMember("owner", () -> {
                access.require("private-library");
                assertThatThrownBy(() -> KnowledgeAccessService.asMember("other", () -> { access.require("private-library"); return null; }))
                        .isInstanceOf(NotFoundException.class);
                assertThat(access.actor()).isEqualTo("owner"); return null;
            });
            assertThat(access.actor()).isNull();
        } finally { RequestContextHolder.resetRequestAttributes(); }
    }
}
