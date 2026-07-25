package cn.wayuwa.wao.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.domain.KbEntity;
import cn.wayuwa.wao.domain.KbFolderEntity;
import cn.wayuwa.wao.domain.KbGroupEntity;
import cn.wayuwa.wao.dto.BacklinkDto;
import cn.wayuwa.wao.dto.CreateDocRequest;
import cn.wayuwa.wao.dto.CreateFolderRequest;
import cn.wayuwa.wao.dto.CreateKbGroupRequest;
import cn.wayuwa.wao.dto.CreateKbRequest;
import cn.wayuwa.wao.dto.DocBlockDto;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.DocLinkDto;
import cn.wayuwa.wao.dto.FolderDto;
import cn.wayuwa.wao.dto.KbDto;
import cn.wayuwa.wao.dto.KbGroupDto;
import cn.wayuwa.wao.dto.PatchDocRequest;
import cn.wayuwa.wao.dto.PatchFolderRequest;
import cn.wayuwa.wao.dto.PatchKbGroupRequest;
import cn.wayuwa.wao.dto.PatchKbRequest;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.mapper.KbFolderMapper;
import cn.wayuwa.wao.mapper.KbGroupMapper;
import cn.wayuwa.wao.mapper.KbMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库域服务：分组 / 知识库 / 文件夹 / 文档的增删改查。
 *
 * <p>反向链接（backlinks）不落库，取全部文档的出链（links）动态反查得到；当前只覆盖「文档 → 文档」
 * 的反链，项目 / 任务指向文档的反链留待这两个域也支持文档关联时再补充。
 */
@Service
public class KnowledgeService {

    private static final TypeReference<List<DocBlockDto>> BLOCKS_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<DocLinkDto>> LINKS_TYPE = new TypeReference<>() { };

    private final KbGroupMapper groupMapper;
    private final KbMapper kbMapper;
    private final KbFolderMapper folderMapper;
    private final DocMapper docMapper;
    private final ObjectMapper json;

    public KnowledgeService(KbGroupMapper groupMapper, KbMapper kbMapper, KbFolderMapper folderMapper,
            DocMapper docMapper, ObjectMapper json) {
        this.groupMapper = groupMapper;
        this.kbMapper = kbMapper;
        this.folderMapper = folderMapper;
        this.docMapper = docMapper;
        this.json = json;
    }

    // ---------------- 分组 ----------------

    /** 列出全部自建分组。 */
    public List<KbGroupDto> listGroups() {
        return groupMapper.selectList(new LambdaQueryWrapper<KbGroupEntity>().orderByAsc(KbGroupEntity::getOrd))
                .stream().map(g -> new KbGroupDto(g.getId(), g.getName())).toList();
    }

    /** 新建自建分组。 */
    public KbGroupDto createGroup(CreateKbGroupRequest req) {
        KbGroupEntity e = new KbGroupEntity();
        e.setId("g" + System.currentTimeMillis());
        e.setName(blankOr(req.name(), "新建分组"));
        e.setOrd(nextOrd(groupMapper.selectCount(null)));
        groupMapper.insert(e);
        return new KbGroupDto(e.getId(), e.getName());
    }

    /** 重命名分组。 */
    public KbGroupDto renameGroup(String id, PatchKbGroupRequest req) {
        KbGroupEntity e = groupMapper.selectById(id);
        if (e == null) {
            throw new NotFoundException("分组不存在：" + id);
        }
        if (req.name() != null && !req.name().isBlank()) {
            e.setName(req.name().trim());
            groupMapper.updateById(e);
        }
        return new KbGroupDto(e.getId(), e.getName());
    }

    /** 删除分组；分组下的知识库回落为默认分组（group=null，前端按 g1 兜底展示）。 */
    @Transactional
    public void deleteGroup(String id) {
        if (groupMapper.selectById(id) == null) {
            throw new NotFoundException("分组不存在：" + id);
        }
        List<KbEntity> owned = kbMapper.selectList(new LambdaQueryWrapper<KbEntity>().eq(KbEntity::getGroupId, id));
        for (KbEntity kb : owned) {
            kb.setGroupId(null);
            kbMapper.updateById(kb);
        }
        groupMapper.deleteById(id);
    }

    // ---------------- 知识库 ----------------

    /** 列出全部知识库（含文件夹与其下文档 id 顺序列表）。 */
    public List<KbDto> listKbs() {
        List<KbEntity> kbs = kbMapper.selectList(new LambdaQueryWrapper<KbEntity>().orderByAsc(KbEntity::getOrd));
        List<KbFolderEntity> folders = folderMapper.selectList(
                new LambdaQueryWrapper<KbFolderEntity>().orderByAsc(KbFolderEntity::getOrd));
        List<DocEntity> docs = docMapper.selectList(new LambdaQueryWrapper<DocEntity>().orderByAsc(DocEntity::getOrd));

        Map<String, List<String>> docIdsByFolder = docs.stream()
                .collect(Collectors.groupingBy(DocEntity::getFolderId,
                        Collectors.mapping(DocEntity::getId, Collectors.toList())));
        Map<String, List<KbFolderEntity>> foldersByKb = folders.stream()
                .collect(Collectors.groupingBy(KbFolderEntity::getKbId));

        return kbs.stream().map(kb -> toDto(kb, foldersByKb.getOrDefault(kb.getId(), List.of()), docIdsByFolder))
                .toList();
    }

    /** 按 id 获取单个知识库。 */
    public KbDto getKb(String id) {
        KbEntity kb = kbMapper.selectById(id);
        if (kb == null) {
            throw new NotFoundException("知识库不存在：" + id);
        }
        List<KbFolderEntity> folders = folderMapper.selectList(
                new LambdaQueryWrapper<KbFolderEntity>().eq(KbFolderEntity::getKbId, id).orderByAsc(KbFolderEntity::getOrd));
        List<DocEntity> docs = docMapper.selectList(new LambdaQueryWrapper<DocEntity>().eq(DocEntity::getKbId, id));
        Map<String, List<String>> docIdsByFolder = docs.stream()
                .collect(Collectors.groupingBy(DocEntity::getFolderId,
                        Collectors.mapping(DocEntity::getId, Collectors.toList())));
        return toDto(kb, folders, docIdsByFolder);
    }

    /** 新建知识库，默认自带一个「文档」文件夹。 */
    @Transactional
    public KbDto createKb(CreateKbRequest req) {
        KbEntity e = new KbEntity();
        e.setId("kb" + System.currentTimeMillis());
        e.setName(req.name().trim());
        e.setIcon(blankOr(req.icon(), "ri-book-2-line"));
        e.setColor(blankOr(req.color(), "#4358D6"));
        e.setProjectId(req.project());
        e.setAuto(false);
        e.setDescription(req.desc());
        e.setGroupId(req.group());
        e.setPerm(blankOr(req.perm(), "仅自己"));
        e.setOrd(nextOrd(kbMapper.selectCount(null)));
        kbMapper.insert(e);

        KbFolderEntity folder = new KbFolderEntity();
        folder.setId("f" + System.currentTimeMillis());
        folder.setKbId(e.getId());
        folder.setName("文档");
        folder.setOrd(1);
        folderMapper.insert(folder);

        return toDto(e, List.of(folder), Map.of());
    }

    /** 局部更新知识库（项目 / 自动化知识库的名称与简介不可改）。 */
    public KbDto updateKb(String id, PatchKbRequest req) {
        KbEntity e = kbMapper.selectById(id);
        if (e == null) {
            throw new NotFoundException("知识库不存在：" + id);
        }
        boolean locked = e.getProjectId() != null || Boolean.TRUE.equals(e.getAuto());
        if (!locked && req.name() != null && !req.name().isBlank()) {
            e.setName(req.name().trim());
        }
        if (!locked && req.desc() != null) {
            e.setDescription(req.desc());
        }
        if (req.icon() != null && !req.icon().isBlank()) {
            e.setIcon(req.icon());
        }
        if (req.color() != null && !req.color().isBlank()) {
            e.setColor(req.color());
        }
        if (!locked && req.group() != null) {
            e.setGroupId(req.group().isBlank() ? null : req.group());
        }
        if (req.perm() != null && !req.perm().isBlank()) {
            e.setPerm(req.perm());
        }
        kbMapper.updateById(e);
        return getKb(id);
    }

    /** 删除知识库，级联删除其下文件夹与文档。 */
    @Transactional
    public void deleteKb(String id) {
        if (kbMapper.selectById(id) == null) {
            throw new NotFoundException("知识库不存在：" + id);
        }
        docMapper.delete(new LambdaQueryWrapper<DocEntity>().eq(DocEntity::getKbId, id));
        folderMapper.delete(new LambdaQueryWrapper<KbFolderEntity>().eq(KbFolderEntity::getKbId, id));
        kbMapper.deleteById(id);
    }

    // ---------------- 文件夹 ----------------

    /** 在指定知识库下新建文件夹。 */
    public FolderDto createFolder(String kbId, CreateFolderRequest req) {
        if (kbMapper.selectById(kbId) == null) {
            throw new NotFoundException("知识库不存在：" + kbId);
        }
        Long count = folderMapper.selectCount(new LambdaQueryWrapper<KbFolderEntity>().eq(KbFolderEntity::getKbId, kbId));
        KbFolderEntity e = new KbFolderEntity();
        e.setId("f" + System.currentTimeMillis());
        e.setKbId(kbId);
        e.setName(blankOr(req.name(), "新建文件夹"));
        e.setOrd(nextOrd(count));
        folderMapper.insert(e);
        return new FolderDto(e.getId(), e.getName(), List.of());
    }

    /** 重命名文件夹。 */
    public FolderDto renameFolder(String kbId, String folderId, PatchFolderRequest req) {
        KbFolderEntity e = requireFolder(kbId, folderId);
        if (req.name() != null && !req.name().isBlank()) {
            e.setName(req.name().trim());
            folderMapper.updateById(e);
        }
        List<String> docIds = docMapper.selectList(new LambdaQueryWrapper<DocEntity>()
                        .eq(DocEntity::getFolderId, folderId).orderByAsc(DocEntity::getOrd))
                .stream().map(DocEntity::getId).toList();
        return new FolderDto(e.getId(), e.getName(), docIds);
    }

    /** 删除文件夹，级联删除其下文档。 */
    @Transactional
    public void deleteFolder(String kbId, String folderId) {
        requireFolder(kbId, folderId);
        docMapper.delete(new LambdaQueryWrapper<DocEntity>().eq(DocEntity::getFolderId, folderId));
        folderMapper.deleteById(folderId);
    }

    private KbFolderEntity requireFolder(String kbId, String folderId) {
        KbFolderEntity e = folderMapper.selectById(folderId);
        if (e == null || !e.getKbId().equals(kbId)) {
            throw new NotFoundException("文件夹不存在：" + folderId);
        }
        return e;
    }

    // ---------------- 文档 ----------------

    /** 列出全部文档（含正文与反向链接）。 */
    public List<DocDto> listDocs() {
        List<DocEntity> docs = docMapper.selectList(null);
        return docs.stream().map(d -> toDto(d, docs)).toList();
    }

    /** 按 id 获取单篇文档。 */
    public DocDto getDoc(String id) {
        DocEntity e = docMapper.selectById(id);
        if (e == null) {
            throw new NotFoundException("文档不存在：" + id);
        }
        return toDto(e, docMapper.selectList(null));
    }

    /** 新建文档：必须指定所属知识库与文件夹，实现「新建文档即关联到知识库」。 */
    public DocDto createDoc(CreateDocRequest req) {
        if (kbMapper.selectById(req.kbId()) == null) {
            throw new NotFoundException("知识库不存在：" + req.kbId());
        }
        KbFolderEntity folder = requireFolder(req.kbId(), req.folderId());
        Long count = docMapper.selectCount(new LambdaQueryWrapper<DocEntity>().eq(DocEntity::getFolderId, folder.getId()));

        DocEntity e = new DocEntity();
        e.setId("d" + System.currentTimeMillis());
        e.setKbId(req.kbId());
        e.setFolderId(req.folderId());
        e.setTitle(blankOr(req.title(), ""));
        e.setAuthorId(blankOr(req.author(), "wg"));
        e.setAuto(false);
        e.setCreatedLabel("刚刚");
        e.setUpdatedLabel("刚刚");
        e.setBlocksJson(write(List.of()));
        e.setLinksJson(write(List.of()));
        e.setOrd(nextOrd(count));
        docMapper.insert(e);
        return toDto(e, docMapper.selectList(null));
    }

    /** 局部更新文档：标题 / 正文块 / 出链 / 所在文件夹。 */
    public DocDto updateDoc(String id, PatchDocRequest req) {
        DocEntity e = docMapper.selectById(id);
        if (e == null) {
            throw new NotFoundException("文档不存在：" + id);
        }
        if (req.title() != null) {
            e.setTitle(req.title());
        }
        if (req.blocks() != null) {
            e.setBlocksJson(write(req.blocks()));
        }
        if (req.links() != null) {
            e.setLinksJson(write(req.links()));
        }
        if (req.folderId() != null && !req.folderId().isBlank()) {
            requireFolder(e.getKbId(), req.folderId());
            e.setFolderId(req.folderId());
        }
        e.setUpdatedLabel("刚刚");
        docMapper.updateById(e);
        return toDto(e, docMapper.selectList(null));
    }

    /** 删除文档。 */
    public void deleteDoc(String id) {
        if (docMapper.selectById(id) == null) {
            throw new NotFoundException("文档不存在：" + id);
        }
        docMapper.deleteById(id);
    }

    // ---------------- 组装 ----------------

    private KbDto toDto(KbEntity kb, List<KbFolderEntity> folders, Map<String, List<String>> docIdsByFolder) {
        List<FolderDto> folderDtos = folders.stream()
                .map(f -> new FolderDto(f.getId(), f.getName(), docIdsByFolder.getOrDefault(f.getId(), List.of())))
                .toList();
        return new KbDto(kb.getId(), kb.getName(), kb.getIcon(), kb.getColor(), kb.getProjectId(),
                kb.getAuto(), kb.getDescription(), folderDtos, kb.getGroupId(), kb.getPerm());
    }

    private DocDto toDto(DocEntity e, List<DocEntity> allDocs) {
        List<DocBlockDto> blocks = readList(e.getBlocksJson(), BLOCKS_TYPE);
        List<DocLinkDto> links = readList(e.getLinksJson(), LINKS_TYPE);
        List<BacklinkDto> backlinks = allDocs.stream()
                .filter(other -> !other.getId().equals(e.getId()))
                .filter(other -> readList(other.getLinksJson(), LINKS_TYPE).stream()
                        .anyMatch(l -> "doc".equals(l.type()) && e.getId().equals(l.id())))
                .map(other -> new BacklinkDto("doc", other.getId(), snippetOf(other)))
                .toList();
        return new DocDto(e.getId(), e.getTitle(), e.getAuthorId(), e.getCreatedLabel(), e.getUpdatedLabel(),
                e.getAuto(), links, blocks, backlinks);
    }

    private String snippetOf(DocEntity e) {
        for (DocBlockDto b : readList(e.getBlocksJson(), BLOCKS_TYPE)) {
            String text = b.p() != null ? b.p() : b.h() != null ? b.h() : b.call();
            if (text != null && !text.isBlank()) {
                return text.length() > 60 ? text.substring(0, 60) + "…" : text;
            }
        }
        return e.getTitle();
    }

    private <T> List<T> readList(String value, TypeReference<List<T>> type) {
        if (value == null || value.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException ex) {
            return new ArrayList<>();
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int nextOrd(Long existingCount) {
        return (existingCount == null ? 0 : existingCount.intValue()) + 1;
    }
}
