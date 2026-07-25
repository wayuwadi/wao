package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.domain.KbEntity;
import cn.wayuwa.wao.domain.KbFolderEntity;
import cn.wayuwa.wao.dto.CreateDocRequest;
import cn.wayuwa.wao.dto.CreateKbRequest;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.KbDto;
import cn.wayuwa.wao.dto.PatchKbRequest;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.mapper.KbFolderMapper;
import cn.wayuwa.wao.mapper.KbGroupMapper;
import cn.wayuwa.wao.mapper.KbMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class KnowledgeServiceTest {

    private final KbGroupMapper groupMapper = mock(KbGroupMapper.class);
    private final KbMapper kbMapper = mock(KbMapper.class);
    private final KbFolderMapper folderMapper = mock(KbFolderMapper.class);
    private final DocMapper docMapper = mock(DocMapper.class);
    private final KnowledgeService service =
            new KnowledgeService(groupMapper, kbMapper, folderMapper, docMapper, new ObjectMapper());

    @Test
    void createDocAssociatesWithGivenKbAndFolder() {
        KbEntity kb = new KbEntity();
        kb.setId("k1");
        when(kbMapper.selectById("k1")).thenReturn(kb);
        KbFolderEntity folder = new KbFolderEntity();
        folder.setId("f1");
        folder.setKbId("k1");
        when(folderMapper.selectById("f1")).thenReturn(folder);
        when(docMapper.selectCount(any())).thenReturn(0L);
        when(docMapper.selectList(any())).thenReturn(List.of());

        DocDto dto = service.createDoc(new CreateDocRequest("k1", "f1", "新文档", "wg"));

        assertThat(dto.id()).startsWith("d");
        assertThat(dto.title()).isEqualTo("新文档");
        assertThat(dto.author()).isEqualTo("wg");
        assertThat(dto.blocks()).isEmpty();
    }

    @Test
    void createDocThrowsWhenKbMissing() {
        when(kbMapper.selectById("missing")).thenReturn(null);

        assertThatThrownBy(() -> service.createDoc(new CreateDocRequest("missing", "f1", "标题", "wg")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void createDocThrowsWhenFolderBelongsToAnotherKb() {
        KbEntity kb = new KbEntity();
        kb.setId("k1");
        when(kbMapper.selectById("k1")).thenReturn(kb);
        KbFolderEntity folder = new KbFolderEntity();
        folder.setId("f1");
        folder.setKbId("other-kb");
        when(folderMapper.selectById("f1")).thenReturn(folder);

        assertThatThrownBy(() -> service.createDoc(new CreateDocRequest("k1", "f1", "标题", "wg")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getDocComputesBacklinksFromOtherDocsLinks() {
        DocEntity target = new DocEntity();
        target.setId("d1");
        target.setTitle("目标文档");
        target.setBlocksJson("[]");
        target.setLinksJson("[]");
        DocEntity source = new DocEntity();
        source.setId("d2");
        source.setTitle("来源文档");
        source.setBlocksJson("[{\"p\":\"引用了目标文档\"}]");
        source.setLinksJson("[{\"type\":\"doc\",\"id\":\"d1\"}]");
        when(docMapper.selectById("d1")).thenReturn(target);
        when(docMapper.selectList(null)).thenReturn(List.of(target, source));

        DocDto dto = service.getDoc("d1");

        assertThat(dto.backlinks()).hasSize(1);
        assertThat(dto.backlinks().get(0).id()).isEqualTo("d2");
        assertThat(dto.backlinks().get(0).snippet()).contains("引用了目标文档");
    }

    @Test
    void createKbSeedsDefaultFolder() {
        when(kbMapper.selectCount(null)).thenReturn(0L);

        KbDto dto = service.createKb(new CreateKbRequest("新知识库", "简介", null, null, null, null, null));

        assertThat(dto.id()).startsWith("kb");
        assertThat(dto.folders()).hasSize(1);
        assertThat(dto.folders().get(0).name()).isEqualTo("文档");
    }

    @Test
    void updateKbCannotRenameWhenLockedToProject() {
        KbEntity kb = new KbEntity();
        kb.setId("k1");
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
}
