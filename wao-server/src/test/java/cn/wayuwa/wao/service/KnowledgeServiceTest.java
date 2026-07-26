package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.domain.KbEntity;
import cn.wayuwa.wao.domain.KbFolderEntity;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.dto.CreateDocRequest;
import cn.wayuwa.wao.dto.CreateKbRequest;
import cn.wayuwa.wao.dto.CreateNoteRequest;
import cn.wayuwa.wao.dto.DocBlockDto;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.KbDto;
import cn.wayuwa.wao.dto.PatchDocRequest;
import cn.wayuwa.wao.dto.PatchKbRequest;
import cn.wayuwa.wao.dto.TrashEntryDto;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.mapper.KbFolderMapper;
import cn.wayuwa.wao.mapper.KbGroupMapper;
import cn.wayuwa.wao.mapper.KbMapper;
import cn.wayuwa.wao.mapper.MemberMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class KnowledgeServiceTest {

    private final KbGroupMapper groupMapper = mock(KbGroupMapper.class);
    private final KbMapper kbMapper = mock(KbMapper.class);
    private final KbFolderMapper folderMapper = mock(KbFolderMapper.class);
    private final DocMapper docMapper = mock(DocMapper.class);
    private final MemberMapper memberMapper = mock(MemberMapper.class);
    private final KnowledgeService service =
            new KnowledgeService(groupMapper, kbMapper, folderMapper, docMapper, memberMapper, new ObjectMapper());

    private MemberEntity member(String id, String name) {
        MemberEntity m = new MemberEntity();
        m.setId(id);
        m.setName(name);
        when(memberMapper.selectById(id)).thenReturn(m);
        return m;
    }

    private KbEntity kb(String id) {
        KbEntity kb = new KbEntity();
        kb.setId(id);
        kb.setName("知识库 " + id);
        return kb;
    }

    private KbFolderEntity folder(String id, String kbId) {
        KbFolderEntity f = new KbFolderEntity();
        f.setId(id);
        f.setKbId(kbId);
        f.setName("文件夹 " + id);
        return f;
    }

    private DocEntity doc(String id, String kbId, String folderId) {
        DocEntity d = new DocEntity();
        d.setId(id);
        d.setKbId(kbId);
        d.setFolderId(folderId);
        d.setTitle("文档 " + id);
        d.setBlocksJson("[]");
        d.setLinksJson("[]");
        d.setRev(1);
        return d;
    }

    @Test
    void createDocAssociatesWithGivenKbAndFolder() {
        when(kbMapper.selectById("k1")).thenReturn(kb("k1"));
        when(folderMapper.selectById("f1")).thenReturn(folder("f1", "k1"));
        when(docMapper.selectCount(any())).thenReturn(0L);
        when(docMapper.selectList(any())).thenReturn(List.of());

        DocDto dto = service.createDoc(new CreateDocRequest("k1", "f1", "新文档"), "m-wadi");

        assertThat(dto.id()).startsWith("d");
        assertThat(dto.title()).isEqualTo("新文档");
        assertThat(dto.blocks()).isEmpty();
        assertThat(dto.kbId()).isEqualTo("k1");
        assertThat(dto.rev()).isEqualTo(1);
    }

    @Test
    void createDocSignsTheCurrentMemberNotAHardCodedOne() {
        when(kbMapper.selectById("k1")).thenReturn(kb("k1"));
        when(folderMapper.selectById("f1")).thenReturn(folder("f1", "k1"));
        when(docMapper.selectCount(any())).thenReturn(0L);
        when(docMapper.selectList(any())).thenReturn(List.of());

        DocDto dto = service.createDoc(new CreateDocRequest("k1", "f1", "新文档"), "m-someone-else");

        assertThat(dto.author()).isEqualTo("m-someone-else");
    }

    @Test
    void createDocStampsRealTimestampsInsteadOfLabels() {
        when(kbMapper.selectById("k1")).thenReturn(kb("k1"));
        when(folderMapper.selectById("f1")).thenReturn(folder("f1", "k1"));
        when(docMapper.selectCount(any())).thenReturn(0L);
        when(docMapper.selectList(any())).thenReturn(List.of());

        DocDto dto = service.createDoc(new CreateDocRequest("k1", "f1", "新文档"), "m1");

        assertThat(dto.created()).matches("\\d{4}-\\d{2}-\\d{2}T.*");
        assertThat(dto.updated()).matches("\\d{4}-\\d{2}-\\d{2}T.*");
    }

    @Test
    void createDocThrowsWhenKbMissing() {
        when(kbMapper.selectById("missing")).thenReturn(null);

        assertThatThrownBy(() -> service.createDoc(new CreateDocRequest("missing", "f1", "标题"), "m1"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void createDocThrowsWhenKbIsInTrash() {
        KbEntity trashed = kb("k1");
        trashed.setDeletedAt(LocalDateTime.now());
        when(kbMapper.selectById("k1")).thenReturn(trashed);

        assertThatThrownBy(() -> service.createDoc(new CreateDocRequest("k1", "f1", "标题"), "m1"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void createDocThrowsWhenFolderBelongsToAnotherKb() {
        when(kbMapper.selectById("k1")).thenReturn(kb("k1"));
        when(folderMapper.selectById("f1")).thenReturn(folder("f1", "other-kb"));

        assertThatThrownBy(() -> service.createDoc(new CreateDocRequest("k1", "f1", "标题"), "m1"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getDocComputesBacklinksFromOtherDocsLinks() {
        DocEntity target = doc("d1", "k1", "f1");
        DocEntity source = doc("d2", "k1", "f1");
        source.setBlocksJson("[{\"p\":\"引用了目标文档\"}]");
        source.setLinksJson("[{\"type\":\"doc\",\"id\":\"d1\"}]");
        when(docMapper.selectById("d1")).thenReturn(target);
        when(docMapper.selectList(any())).thenReturn(List.of(target, source));

        DocDto dto = service.getDoc("d1");

        assertThat(dto.backlinks()).hasSize(1);
        assertThat(dto.backlinks().get(0).id()).isEqualTo("d2");
        assertThat(dto.backlinks().get(0).snippet()).contains("引用了目标文档");
    }

    @Test
    void getDocThrowsWhenAlreadyInTrash() {
        DocEntity trashed = doc("d1", "k1", "f1");
        trashed.setDeletedAt(LocalDateTime.now());
        when(docMapper.selectById("d1")).thenReturn(trashed);

        assertThatThrownBy(() -> service.getDoc("d1")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateDocBumpsRevisionOnlyWhenContentChanges() {
        DocEntity d = doc("d1", "k1", "f1");
        when(docMapper.selectById("d1")).thenReturn(d);
        when(docMapper.selectList(any())).thenReturn(List.of(d));

        DocDto untouched = service.updateDoc("d1", new PatchDocRequest(null, null, List.of(), null));
        assertThat(untouched.rev()).isEqualTo(1);

        DocDto edited = service.updateDoc("d1", new PatchDocRequest(null, List.of(new DocBlockDto(null, null, "正文", null, null)), null, null));
        assertThat(edited.rev()).isEqualTo(2);
        assertThat(edited.updated()).matches("\\d{4}-\\d{2}-\\d{2}T.*");
    }

    @Test
    void createKbSeedsDefaultFolder() {
        when(kbMapper.selectCount(null)).thenReturn(0L);

        KbDto dto = service.createKb(new CreateKbRequest("新知识库", "简介", null, null, null, null, null));

        assertThat(dto.id()).startsWith("kb");
        assertThat(dto.folders()).hasSize(1);
        assertThat(dto.folders().get(0).name()).isEqualTo("文档");
        assertThat(dto.notes()).isFalse();
    }

    @Test
    void updateKbCannotRenameWhenLockedToProject() {
        KbEntity kb = kb("k1");
        kb.setName("项目知识库");
        kb.setProjectId("p1");
        kb.setAuto(false);
        kb.setIcon("ri-book-2-line");
        when(kbMapper.selectById("k1")).thenReturn(kb);
        when(folderMapper.selectList(any())).thenReturn(List.of());
        when(docMapper.selectList(any())).thenReturn(List.of());

        KbDto dto = service.updateKb("k1", new PatchKbRequest("改名", "改简介", "ri-star-line", null, null, null));

        assertThat(dto.name()).isEqualTo("项目知识库");
        assertThat(dto.icon()).isEqualTo("ri-star-line");
    }

    @Test
    void getKbThrowsWhenMissing() {
        when(kbMapper.selectById("nope")).thenReturn(null);
        assertThatThrownBy(() -> service.getKb("nope")).isInstanceOf(NotFoundException.class);
    }

    // ---------------- 回收站 ----------------

    @Test
    void deleteDocMovesItToTrashInsteadOfDroppingTheRow() {
        DocEntity d = doc("d1", "k1", "f1");
        when(docMapper.selectById("d1")).thenReturn(d);

        service.deleteDoc("d1", "m1");

        assertThat(d.getDeletedAt()).isNotNull();
        assertThat(d.getDeletedBy()).isEqualTo("m1");
        assertThat(d.getCascadeDel()).isFalse();
        verify(docMapper).updateById(d);
        verify(docMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void deleteKbCascadesToFoldersAndDocsWithoutDroppingRows() {
        when(kbMapper.selectById("k1")).thenReturn(kb("k1"));
        KbFolderEntity f = folder("f1", "k1");
        DocEntity d = doc("d1", "k1", "f1");
        when(folderMapper.selectList(any())).thenReturn(List.of(f));
        when(docMapper.selectList(any())).thenReturn(List.of(d));

        service.deleteKb("k1", "m1");

        assertThat(f.getDeletedAt()).isNotNull();
        assertThat(f.getCascadeDel()).isTrue();
        assertThat(d.getDeletedAt()).isNotNull();
        assertThat(d.getCascadeDel()).isTrue();
        verify(kbMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void deleteKbRefusesTheNotesKb() {
        KbEntity notes = kb("kb-notes");
        notes.setNotes(true);
        when(kbMapper.selectById("kb-notes")).thenReturn(notes);

        assertThatThrownBy(() -> service.deleteKb("kb-notes", "m1")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void trashListHidesEntriesDeletedAlongWithTheirParent() {
        KbEntity trashedKb = kb("k1");
        trashedKb.setDeletedAt(LocalDateTime.now());
        KbFolderEntity cascaded = folder("f1", "k1");
        cascaded.setDeletedAt(LocalDateTime.now());
        cascaded.setCascadeDel(true);
        DocEntity ownDelete = doc("d1", "k2", "f2");
        ownDelete.setDeletedAt(LocalDateTime.now());
        ownDelete.setCascadeDel(false);

        when(kbMapper.selectList(any())).thenReturn(List.of(trashedKb));
        // cascade 条目由 ne(cascadeDel, true) 过滤掉，这里模拟已过滤的结果
        when(folderMapper.selectList(any())).thenReturn(List.of());
        when(docMapper.selectList(any())).thenReturn(List.of(ownDelete));

        List<TrashEntryDto> entries = service.listTrash();

        assertThat(entries).extracting(TrashEntryDto::type).containsExactlyInAnyOrder("kb", "doc");
        assertThat(entries).extracting(TrashEntryDto::id).doesNotContain("f1");
        assertThat(entries).allSatisfy(e -> assertThat(e.expiresAt()).isNotNull());
    }

    @Test
    void restoringADocAlsoRevivesItsTrashedKbSoItHasSomewhereToLive() {
        DocEntity d = doc("d1", "k1", "f1");
        d.setDeletedAt(LocalDateTime.now());
        KbFolderEntity f = folder("f1", "k1");
        f.setDeletedAt(LocalDateTime.now());
        f.setCascadeDel(true);
        KbEntity k = kb("k1");
        k.setDeletedAt(LocalDateTime.now());
        when(docMapper.selectById("d1")).thenReturn(d);
        when(folderMapper.selectById("f1")).thenReturn(f);
        when(kbMapper.selectById("k1")).thenReturn(k);

        service.restore("doc", "d1");

        assertThat(d.getDeletedAt()).isNull();
        assertThat(f.getDeletedAt()).isNull();
        assertThat(k.getDeletedAt()).isNull();
    }

    @Test
    void restoreThrowsWhenEntryIsNotInTrash() {
        when(docMapper.selectById("d1")).thenReturn(doc("d1", "k1", "f1"));
        assertThatThrownBy(() -> service.restore("doc", "d1")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.restore("banana", "x")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void purgeExpiredDropsOnlyEntriesPastTheRetentionWindow() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 25, 10, 0);
        DocEntity expired = doc("d-old", "k1", "f1");
        expired.setDeletedAt(now.minusDays(KnowledgeService.TRASH_DAYS).minusMinutes(1));
        when(kbMapper.selectList(any())).thenReturn(List.of());
        when(folderMapper.selectList(any())).thenReturn(List.of());
        when(docMapper.selectList(any())).thenReturn(List.of(expired));

        int removed = service.purgeExpired(now);

        assertThat(removed).isEqualTo(1);
        verify(docMapper).deleteById("d-old");
    }

    // ---------------- 小记 ----------------

    @Test
    void createNoteLandsInTheNotesKbAndTakesItsTitleFromTheFirstLine() {
        KbEntity notes = kb("kb-notes");
        notes.setNotes(true);
        KbFolderEntity folder = folder("kb-notes-f", "kb-notes");
        when(kbMapper.selectList(any())).thenReturn(List.of(notes));
        when(folderMapper.selectList(any())).thenReturn(List.of(folder));
        when(docMapper.selectCount(any())).thenReturn(3L);
        when(docMapper.selectList(any())).thenReturn(List.of());

        DocDto dto = service.createNote(
                new CreateNoteRequest(null, List.of(new DocBlockDto(null, null, "记一下：周四要交社保材料", null, null))),
                "m1");

        assertThat(dto.kbId()).isEqualTo("kb-notes");
        assertThat(dto.folderId()).isEqualTo("kb-notes-f");
        assertThat(dto.title()).isEqualTo("记一下：周四要交社保材料");
        assertThat(dto.author()).isEqualTo("m1");
    }

    @Test
    void createNoteReusesTheFolderNamedAfterTheAuthor() {
        KbEntity notes = kb("kb-notes");
        notes.setNotes(true);
        member("wd", "娃弟");
        KbFolderEntity mine = folder("kb-notes-wd", "kb-notes");
        mine.setName("娃弟");
        when(kbMapper.selectList(any())).thenReturn(List.of(notes));
        when(folderMapper.selectList(any())).thenReturn(List.of(mine));
        when(docMapper.selectCount(any())).thenReturn(0L);
        when(docMapper.selectList(any())).thenReturn(List.of());

        DocDto dto = service.createNote(new CreateNoteRequest("随手一记", List.of()), "wd");

        assertThat(dto.folderId()).isEqualTo("kb-notes-wd");
        verify(folderMapper, never()).insert(any(KbFolderEntity.class));
    }

    @Test
    void createNoteCreatesTheAuthorFolderOnFirstNote() {
        KbEntity notes = kb("kb-notes");
        notes.setNotes(true);
        member("wg", "娃哥");
        when(kbMapper.selectList(any())).thenReturn(List.of(notes));
        when(folderMapper.selectList(any())).thenReturn(List.of());
        when(folderMapper.selectById("kb-notes-wg")).thenReturn(null);
        when(folderMapper.selectCount(any())).thenReturn(1L);
        when(docMapper.selectCount(any())).thenReturn(0L);
        when(docMapper.selectList(any())).thenReturn(List.of());

        DocDto dto = service.createNote(new CreateNoteRequest("随手一记", List.of()), "wg");

        var captor = org.mockito.ArgumentCaptor.forClass(KbFolderEntity.class);
        verify(folderMapper).insert(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("娃哥");
        assertThat(dto.folderId()).isEqualTo("kb-notes-wg");
    }

    @Test
    void createNoteFallsBackToTheSharedFolderWhenTheAuthorIsUnknown() {
        KbEntity notes = kb("kb-notes");
        notes.setNotes(true);
        KbFolderEntity shared = folder("kb-notes-f", "kb-notes");
        when(kbMapper.selectList(any())).thenReturn(List.of(notes));
        when(folderMapper.selectList(any())).thenReturn(List.of(shared));
        when(docMapper.selectCount(any())).thenReturn(0L);
        when(docMapper.selectList(any())).thenReturn(List.of());

        DocDto dto = service.createNote(new CreateNoteRequest("随手一记", List.of()), null);

        assertThat(dto.folderId()).isEqualTo("kb-notes-f");
    }

    @Test
    void createNoteRebuildsTheNotesKbWhenItIsMissing() {
        when(kbMapper.selectList(any())).thenReturn(List.of());
        when(kbMapper.selectById("kb-notes")).thenReturn(null);
        when(folderMapper.selectList(any())).thenReturn(List.of());
        when(folderMapper.selectById("kb-notes-f")).thenReturn(null);
        when(docMapper.selectCount(any())).thenReturn(0L);
        when(docMapper.selectList(any())).thenReturn(List.of());

        DocDto dto = service.createNote(new CreateNoteRequest(null, List.of()), "m1");

        verify(kbMapper, times(1)).insert(any(KbEntity.class));
        verify(folderMapper, times(1)).insert(any(KbFolderEntity.class));
        assertThat(dto.title()).isEqualTo("未命名小记");
    }
}
