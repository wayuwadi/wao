package cn.wayuwa.wao.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.domain.DocTemplateEntity;
import cn.wayuwa.wao.domain.KbEntity;
import cn.wayuwa.wao.domain.KbFolderEntity;
import cn.wayuwa.wao.domain.KbGroupEntity;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.dto.BacklinkDto;
import cn.wayuwa.wao.dto.CreateDocRequest;
import cn.wayuwa.wao.dto.CreateDocTemplateRequest;
import cn.wayuwa.wao.dto.CreateFolderRequest;
import cn.wayuwa.wao.dto.CreateKbGroupRequest;
import cn.wayuwa.wao.dto.CreateKbRequest;
import cn.wayuwa.wao.dto.CreateNoteRequest;
import cn.wayuwa.wao.dto.DocBlockDto;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.DocLinkDto;
import cn.wayuwa.wao.dto.IdeaDto;
import cn.wayuwa.wao.dto.ProjectDto;
import cn.wayuwa.wao.dto.DocTemplateDto;
import cn.wayuwa.wao.dto.FolderDto;
import cn.wayuwa.wao.dto.KbDto;
import cn.wayuwa.wao.dto.KbGroupDto;
import cn.wayuwa.wao.dto.PatchDocRequest;
import cn.wayuwa.wao.dto.PatchDocTemplateRequest;
import cn.wayuwa.wao.dto.PatchFolderRequest;
import cn.wayuwa.wao.dto.PatchKbGroupRequest;
import cn.wayuwa.wao.dto.PatchKbRequest;
import cn.wayuwa.wao.dto.ReorderDocsRequest;
import cn.wayuwa.wao.dto.RichDocContentDto;
import cn.wayuwa.wao.dto.TrashEntryDto;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.mapper.DocTemplateMapper;
import cn.wayuwa.wao.mapper.KbFolderMapper;
import cn.wayuwa.wao.mapper.KbGroupMapper;
import cn.wayuwa.wao.mapper.KbMapper;
import cn.wayuwa.wao.mapper.MemberMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 知识库域服务：分组 / 知识库 / 文件夹 / 文档的增删改查，外加小记与回收站。
 *
 * <p>反向链接（backlinks）不落库，取全部文档的出链（links）动态反查得到；当前只覆盖「文档 → 文档」
 * 的反链，项目 / 任务指向文档的反链留待这两个域也支持文档关联时再补充。
 *
 * <p>删除一律是软删除：知识库 / 文件夹 / 文档都只写 {@code deleted_at}，进回收站保留
 * {@link #TRASH_DAYS} 天，到期由 {@link TrashPurgeScheduler} 物理删除（见 ADR-018）。
 * 因此所有读路径都必须带上「未删除」条件。
 */
@Service
public class KnowledgeService {

    /** 回收站保留天数：删除后 7 天内可恢复，之后自动彻底删除。 */
    public static final int TRASH_DAYS = 7;

    /** 小记知识库固定 id（migration 里种下，各节点判等用）。 */
    public static final String NOTES_KB_ID = "kb-notes";
    /** 点子库自动沉淀调研、方案与验证资产的团队共享知识库。 */
    public static final String IDEA_ASSET_KB_ID = "kb-idea-assets";
    /** 聊天复盘成功报告的共享知识库。 */
    public static final String CHAT_REVIEW_KB_ID = "kb-chat-reviews";
    private static final String CHAT_REVIEW_FOLDER_ID = "kb-chat-reviews-f";
    private static final String NOTES_FOLDER_ID = "kb-notes-f";
    private static final Pattern CLIENT_DOC_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final TypeReference<List<DocBlockDto>> BLOCKS_TYPE = new TypeReference<>() { };
    private static final int MAX_TEMPLATE_NAME_LENGTH = 128;
    private static final int MAX_TEMPLATE_DESCRIPTION_LENGTH = 512;
    private static final int MAX_TEMPLATE_BLOCK_COUNT = 5_000;
    /** 防止嵌套块绕过集合长度校验，限制最终准备写入 JSON 列的字符数。 */
    private static final int MAX_TEMPLATE_BLOCKS_JSON_LENGTH = 1_000_000;
    private static final int RICH_CONTENT_VERSION = 1;
    private static final int MAX_RICH_NODE_COUNT = 5_000;
    private static final int MAX_RICH_CONTENT_LENGTH = 1_000_000;
    private static final Set<String> RICH_NODE_TYPES = Set.of(
            "doc", "text", "paragraph", "heading", "bulletList", "orderedList", "listItem",
            "taskList", "taskItem", "blockquote", "codeBlock", "hardBreak", "horizontalRule",
            "image", "table", "tableRow", "tableHeader", "tableCell", "inlineMath", "blockMath",
            "callout", "details", "detailsSummary", "detailsContent", "columns", "column",
            "fileCard", "mediaCard", "linkCard", "drawingCard", "mermaid");
    private static final Set<String> RICH_MARK_TYPES = Set.of(
            "bold", "italic", "underline", "strike", "code", "link", "textStyle", "highlight");
    private static final Set<String> RICH_COLORS = Set.of(
            "#1f2329", "#646a73", "#d83931", "#de7802", "#2ea121", "#245bdb", "#6425d0",
            "#f5f6f7", "#fde2e2", "#feead2", "#d9f5d6", "#dbeafe", "#ede2fe");
    private static final Set<String> RICH_ATTRS = Set.of(
            "uid", "level", "textAlign", "start", "checked", "colspan", "rowspan", "colwidth",
            "backgroundColor", "color", "language", "latex", "src", "alt", "title", "width", "height", "align",
            "kind", "id", "name", "size", "mime", "url", "snapshot", "source", "href", "target", "rel", "class");
    private static final TypeReference<List<DocLinkDto>> LINKS_TYPE = new TypeReference<>() { };

    private final KbGroupMapper groupMapper;
    private final KbMapper kbMapper;
    private final KbFolderMapper folderMapper;
    private final DocMapper docMapper;
    private final DocTemplateMapper templateMapper;
    private final MemberMapper memberMapper;
    private final ObjectMapper json;
    private final FeishuTaskNotifier taskNotifier;
    private final FeishuChatArchiveVersionService archiveVersions;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(KnowledgeService.class);

    public KnowledgeService(KbGroupMapper groupMapper, KbMapper kbMapper, KbFolderMapper folderMapper,
            DocMapper docMapper, DocTemplateMapper templateMapper, MemberMapper memberMapper, ObjectMapper json,
            FeishuTaskNotifier taskNotifier, FeishuChatArchiveVersionService archiveVersions) {
        this.groupMapper = groupMapper;
        this.kbMapper = kbMapper;
        this.folderMapper = folderMapper;
        this.docMapper = docMapper;
        this.templateMapper = templateMapper;
        this.memberMapper = memberMapper;
        this.json = json;
        this.taskNotifier = taskNotifier;
        this.archiveVersions = archiveVersions;
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

    /** 列出全部知识库（含文件夹与其下文档 id 顺序列表），不含回收站里的。 */
    public List<KbDto> listKbs() {
        List<KbEntity> kbs = kbMapper.selectList(aliveKbs().orderByAsc(KbEntity::getOrd));
        List<KbFolderEntity> folders = folderMapper.selectList(aliveFolders().orderByAsc(KbFolderEntity::getOrd));
        List<DocEntity> docs = docMapper.selectList(new QueryWrapper<DocEntity>()
                .select("id", "folder_id")
                .isNull("deleted_at")
                .isNull("archived_at")
                .orderByAsc("ord"));

        Map<String, List<String>> docIdsByFolder = docIdsByFolder(docs);
        Map<String, List<KbFolderEntity>> foldersByKb = folders.stream()
                .collect(Collectors.groupingBy(KbFolderEntity::getKbId));

        return kbs.stream().map(kb -> toDto(kb, foldersByKb.getOrDefault(kb.getId(), List.of()), docIdsByFolder))
                .toList();
    }

    /** 按 id 获取单个知识库。 */
    public KbDto getKb(String id) {
        KbEntity kb = requireKb(id);
        List<KbFolderEntity> folders = folderMapper.selectList(
                aliveFolders().eq(KbFolderEntity::getKbId, id).orderByAsc(KbFolderEntity::getOrd));
        List<DocEntity> docs = docMapper.selectList(activeDocs().select(DocEntity::getId, DocEntity::getFolderId)
                .eq(DocEntity::getKbId, id).orderByAsc(DocEntity::getOrd));
        return toDto(kb, folders, docIdsByFolder(docs));
    }

    /** 返回数据库中真实的项目关联，供跨成员入口校验历史知识库可见性。 */
    public String projectIdForKb(String id) {
        return requireKb(id).getProjectId();
    }

    /** 新建知识库，默认自带一个「文档」文件夹。 */
    @Transactional
    public KbDto createKb(CreateKbRequest req) {
        KbEntity e = new KbEntity();
        e.setId("kb" + System.currentTimeMillis());
        e.setName(req.name().trim());
        e.setIcon(blankOr(req.icon(), "ri-book-2-line"));
        e.setColor(blankOr(req.color(), "#4358D6"));
        // 历史请求字段仅为兼容保留，新知识库不再绑定项目。
        e.setProjectId(null);
        e.setAuto(false);
        e.setNotes(false);
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
        folder.setCascadeDel(false);
        folderMapper.insert(folder);

        return toDto(e, List.of(folder), Map.of());
    }

    /** 局部更新知识库（自动化知识库的名称与简介不可改）。 */
    public KbDto updateKb(String id, PatchKbRequest req) {
        KbEntity e = requireKb(id);
        boolean locked = Boolean.TRUE.equals(e.getAuto());
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
        if (req.group() != null) {
            e.setGroupId(req.group().isBlank() ? null : req.group());
        }
        if (req.perm() != null && !req.perm().isBlank()) {
            e.setPerm(req.perm());
        }
        kbMapper.updateById(e);
        return getKb(id);
    }

    /**
     * 把知识库移入回收站，其下文件夹与文档一并标记（恢复知识库时会一起回来）。
     *
     * @param id       知识库 id
     * @param memberId 操作人 member id
     */
    @Transactional
    public void deleteKb(String id, String memberId) {
        KbEntity kb = requireKb(id);
        if (Boolean.TRUE.equals(kb.getNotes())) {
            throw new BadRequestException("小记知识库是「新建小记」的默认落点，不能删除");
        }
        LocalDateTime now = LocalDateTime.now();
        kb.setDeletedAt(now);
        kb.setDeletedBy(memberId);
        kbMapper.updateById(kb);
        for (KbFolderEntity f : folderMapper.selectList(aliveFolders().eq(KbFolderEntity::getKbId, id))) {
            f.setDeletedAt(now);
            f.setCascadeDel(true);
            folderMapper.updateById(f);
        }
        for (DocEntity d : docMapper.selectList(aliveDocs().eq(DocEntity::getKbId, id))) {
            markDocDeleted(d, now, memberId, true);
        }
    }

    // ---------------- 文件夹 ----------------

    /** 在指定知识库下新建文件夹。 */
    public FolderDto createFolder(String kbId, CreateFolderRequest req) {
        requireKb(kbId);
        Long count = folderMapper.selectCount(aliveFolders().eq(KbFolderEntity::getKbId, kbId));
        KbFolderEntity e = new KbFolderEntity();
        e.setId("f" + System.currentTimeMillis());
        e.setKbId(kbId);
        e.setName(blankOr(req.name(), "新建文件夹"));
        e.setOrd(nextOrd(count));
        e.setCascadeDel(false);
        folderMapper.insert(e);
        return new FolderDto(e.getId(), e.getName(), List.of());
    }

    /**
     * 取得自动归档所需的文件夹；不存在时在指定知识库中创建一个。
     *
     * @param kbId 知识库 id
     * @param name 文件夹名称
     * @return 已存在或刚创建的文件夹
     */
    @Transactional
    public KbFolderEntity ensureAutoFolder(String kbId, String name) {
        lockAliveKb(kbId);
        KbFolderEntity existing = folderMapper.selectOne(aliveFolders()
                .eq(KbFolderEntity::getKbId, kbId).eq(KbFolderEntity::getName, name));
        if (existing != null) {
            return existing;
        }
        Long count = folderMapper.selectCount(aliveFolders().eq(KbFolderEntity::getKbId, kbId));
        KbFolderEntity folder = new KbFolderEntity();
        folder.setId("f" + System.currentTimeMillis());
        folder.setKbId(kbId);
        folder.setName(name);
        folder.setOrd(nextOrd(count));
        folder.setCascadeDel(false);
        folderMapper.insert(folder);
        return folder;
    }

    /** 重命名文件夹。 */
    public FolderDto renameFolder(String kbId, String folderId, PatchFolderRequest req) {
        KbFolderEntity e = requireFolder(kbId, folderId);
        if (req.name() != null && !req.name().isBlank()) {
            e.setName(req.name().trim());
            folderMapper.updateById(e);
        }
        List<String> docIds = docMapper.selectList(aliveDocs().select(DocEntity::getId)
                        .eq(DocEntity::getFolderId, folderId).orderByAsc(DocEntity::getOrd))
                .stream().map(DocEntity::getId).toList();
        return new FolderDto(e.getId(), e.getName(), docIds);
    }

    /**
     * 把文件夹移入回收站，其下文档一并标记。
     *
     * @param kbId     所属知识库 id
     * @param folderId 文件夹 id
     * @param memberId 操作人 member id
     */
    @Transactional
    public void deleteFolder(String kbId, String folderId, String memberId) {
        kbMapper.selectByIdForUpdate(kbId);
        KbFolderEntity folder = requireFolder(kbId, folderId);
        List<KbFolderEntity> liveFolders = folderMapper.selectList(aliveFolders().eq(KbFolderEntity::getKbId, kbId));
        List<DocEntity> liveDocs = docMapper.selectList(aliveDocs().eq(DocEntity::getKbId, kbId));
        Map<String, String> links = directoryParents(requireKb(kbId), liveFolders, liveDocs);
        for (KbFolderEntity child : liveFolders) {
            String cursor = links.get("f:" + child.getId());
            Set<String> seen = new HashSet<>();
            while (cursor != null && !cursor.isEmpty() && seen.add(cursor)) {
                if (cursor.equals("f:" + folderId)) throw new BadRequestException("请先移出子分组，再删除当前分组");
                cursor = links.get(cursor);
            }
        }
        LocalDateTime now = LocalDateTime.now();
        folder.setDeletedAt(now);
        folder.setCascadeDel(false);
        folderMapper.updateById(folder);
        for (DocEntity d : docMapper.selectList(aliveDocs().eq(DocEntity::getFolderId, folderId))) {
            markDocDeleted(d, now, memberId, true);
        }
    }

    private KbFolderEntity requireFolder(String kbId, String folderId) {
        KbFolderEntity e = folderMapper.selectById(folderId);
        if (e == null || !e.getKbId().equals(kbId) || e.getDeletedAt() != null) {
            throw new NotFoundException("文件夹不存在：" + folderId);
        }
        return e;
    }

    private KbEntity requireKb(String id) {
        KbEntity kb = kbMapper.selectById(id);
        if (kb == null || kb.getDeletedAt() != null) {
            throw new NotFoundException("知识库不存在：" + id);
        }
        return kb;
    }

    // ---------------- 文档 ----------------

    /**
     * 读取轻量文档目录。普通文档明确标记正文尚未加载，必须通过详情接口读取后才能编辑。
     * 小记列表需要正文展示与全文筛选，因此单独批量读取小记，避免预取全库聊天归档正文。
     */
    public List<DocDto> listDocCatalog() {
        List<DocEntity> entries = docMapper.selectList(activeDocs().select(
                DocEntity::getId, DocEntity::getKbId, DocEntity::getFolderId, DocEntity::getTitle,
                DocEntity::getAuthorId, DocEntity::getAuto, DocEntity::getCreatedLabel,
                DocEntity::getUpdatedLabel, DocEntity::getCreatedAt, DocEntity::getUpdatedAt,
                DocEntity::getRev, DocEntity::getTagsJson, DocEntity::getLinksJson));
        List<String> noteKbIds = kbMapper.selectList(aliveKbs()).stream()
                .filter(kb -> Boolean.TRUE.equals(kb.getNotes())).map(KbEntity::getId).toList();
        Map<String, DocEntity> notes = noteKbIds.isEmpty() ? Map.of()
                : docMapper.selectList(activeDocs().in(DocEntity::getKbId, noteKbIds)).stream()
                    .collect(Collectors.toMap(DocEntity::getId, doc -> doc));
        return entries.stream().map(entry -> {
            DocEntity note = notes.get(entry.getId());
            if (note != null) return toDtoWithBacklinks(note, List.of());
            DocDto metadata = toDtoWithBacklinks(entry, List.of());
            return new DocDto(metadata.id(), metadata.title(), metadata.author(), metadata.created(),
                    metadata.updated(), metadata.auto(), metadata.links(), List.of(), null, List.of(),
                    metadata.kbId(), metadata.folderId(), metadata.rev(), metadata.tags(), false);
        }).toList();
    }

    /** 列出全部日常文档（含正文与反向链接），不含回收站与归档里的。 */
    public List<DocDto> listDocs() {
        List<DocEntity> docs = docMapper.selectList(activeDocs());
        Map<String, List<DocLinkDto>> linksById = docs.stream().collect(Collectors.toMap(
                DocEntity::getId, doc -> readList(doc.getLinksJson(), LINKS_TYPE)));
        Map<String, List<DocBlockDto>> blocksById = docs.stream().collect(Collectors.toMap(
                DocEntity::getId, doc -> readList(doc.getBlocksJson(), BLOCKS_TYPE)));
        Map<String, List<BacklinkDto>> backlinks = backlinkIndex(docs,
                doc -> linksById.get(doc.getId()), doc -> snippetOf(doc, blocksById.get(doc.getId())));
        return docs.stream().map(doc -> toDtoWithBacklinks(
                doc, backlinks.getOrDefault(doc.getId(), List.of()),
                linksById.get(doc.getId()), blocksById.get(doc.getId()))).toList();
    }

    /** 列出指定知识库的归档文档，归档内容长期保留且可继续打开。 */
    public List<DocDto> listArchivedDocs(String kbId) {
        requireKb(kbId);
        List<DocEntity> archived = docMapper.selectList(aliveDocs()
                .eq(DocEntity::getKbId, kbId)
                .isNotNull(DocEntity::getArchivedAt)
                .orderByDesc(DocEntity::getArchivedAt));
        List<DocEntity> retained = docMapper.selectList(aliveDocs());
        Map<String, List<BacklinkDto>> backlinks = backlinkIndex(retained);
        return archived.stream().map(doc -> toDtoWithBacklinks(doc,
                backlinks.getOrDefault(doc.getId(), List.of()))).toList();
    }

    /** 仅查询未删除文档的当前版本，供高频心跳判断是否需要读取正文。 */
    public int getDocRevision(String id) {
        Integer revision = docMapper.selectAliveRevision(id);
        if (revision == null) throw new NotFoundException("文档不存在：" + id);
        return revision;
    }

    /** 按 id 获取单篇文档。 */
    public DocDto getDoc(String id) {
        DocEntity e = requireDoc(id);
        return toDto(e);
    }

    /**
     * 新建文档：必须指定所属知识库与文件夹，实现「新建文档即关联到知识库」。
     *
     * @param req    新建请求
     * @param author 作者 member id，由服务端从当前会话推导（不接受请求体指定）
     */
    @Transactional
    public DocDto createDoc(CreateDocRequest req, String author) {
        KbEntity kb = requireKb(req.kbId());
        KbFolderEntity folder = requireFolder(req.kbId(), req.folderId());
        List<DocLinkDto> links = List.of();
        List<DocBlockDto> blocks = List.of();
        RichDocContentDto content = null;
        if (req.blocks() != null && !req.blocks().isEmpty()) {
            if (StringUtils.hasText(req.templateId())) {
                throw new BadRequestException("新建文档不能同时指定模板和正文");
            }
            writeTemplateBlocks(req.blocks());
            blocks = req.blocks();
        }
        if (req.content() != null) {
            if (StringUtils.hasText(req.templateId())) {
                throw new BadRequestException("新建文档不能同时指定模板和正文");
            }
            content = checkedRichContent(req.content());
        }
        if (req.templateId() != null && !req.templateId().isBlank()) {
            DocTemplateEntity template = requireTemplate(req.templateId(), author);
            blocks = templateBlocks(template);
            content = richContentOf(template);
        }
        return insertDoc(req.kbId(), folder.getId(), req.title(), author, blocks, content, links);
    }

    /**
     * 用稳定 id 幂等创建当天的「今日节奏」文档。
     *
     * <p>文档被移入回收站后仍返回原文档，上层展示恢复入口，不会生成第二篇同日文档。
     *
     * @param id 由成员与日期派生的稳定文档 id
     * @param kbId 目标知识库
     * @param folderId 目标文件夹
     * @param templateId 可选的文档模板
     * @param title 已替换日期占位符的标题
     * @param suppliedBlocks 页面上的最新草稿；空时复制模板
     * @param author 当前成员
     * @return 当天唯一文档
     */
    @Transactional
    public DocDto createDailyRhythmDoc(String id, String kbId, String folderId, String templateId,
            String title, List<DocBlockDto> suppliedBlocks, String author) {
        DocEntity existing = docMapper.selectById(id);
        if (existing != null) {
            if (!Objects.equals(existing.getAuthorId(), author)) {
                throw new BadRequestException("每日文档标识已被占用");
            }
            return toDto(existing);
        }
        requireKb(kbId);
        requireFolder(kbId, folderId);
        List<DocBlockDto> blocks = suppliedBlocks == null ? List.of() : suppliedBlocks;
        RichDocContentDto content = null;
        if (blocks.isEmpty() && StringUtils.hasText(templateId)) {
            DocTemplateEntity template = requireTemplate(templateId, author);
            blocks = templateBlocks(template);
            content = richContentOf(template);
        }
        return insertDoc(id, kbId, folderId, title, author, blocks, content, List.of());
    }

    /**
     * 保存每日文档设置前校验目标仍然存在，且模板属于当前成员。
     *
     * @param kbId 目标知识库
     * @param folderId 目标文件夹
     * @param templateId 可选模板
     * @param memberId 当前成员
     */
    public void validateDailyRhythmTarget(String kbId, String folderId, String templateId, String memberId) {
        requireKb(kbId);
        requireFolder(kbId, folderId);
        if (StringUtils.hasText(templateId)) {
            requireTemplate(templateId, memberId);
        }
    }

    // ---------------- 文档模板 ----------------

    /** 列出当前成员自己的模板，供新建文档和模板管理共同使用。 */
    public List<DocTemplateDto> listTemplates(String memberId) {
        return templateMapper.selectList(new LambdaQueryWrapper<DocTemplateEntity>()
                        .eq(DocTemplateEntity::getCreatedBy, memberId)
                        .orderByAsc(DocTemplateEntity::getOrd, DocTemplateEntity::getCreatedAt))
                .stream().map(this::toTemplateDto).toList();
    }

    /** 把现有文档的当前正文快照另存为当前成员的模板。 */
    @Transactional
    public DocTemplateDto createTemplate(CreateDocTemplateRequest req, String memberId) {
        String name = checkedTemplateName(req.name());
        String description = checkedTemplateDescription(req.description() == null ? "" : req.description());
        DocEntity source = requireDoc(req.sourceDocId());
        // V71 使用 DATETIME（秒精度）；返回值与落库值保持同一精度，才能作为后续 CAS 版本。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        DocTemplateEntity template = new DocTemplateEntity();
        template.setId("dt" + UUID.randomUUID().toString().replace("-", "").substring(0, 30));
        template.setName(name);
        template.setDescription(description);
        template.setBlocksJson(req.blocks() == null
                ? writeSourceTemplateBlocks(source.getBlocksJson())
                : writeTemplateBlocks(req.blocks()));
        if (req.content() != null) applyRichContent(template, req.content());
        else if (req.blocks() == null) {
            template.setContentJson(source.getContentJson());
            template.setContentMarkdown(source.getContentMarkdown());
            template.setContentVersion(source.getContentVersion());
        }
        template.setSourceDocId(source.getId());
        template.setCreatedBy(memberId);
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        template.setOrd(nextOrd(templateMapper.selectCount(new LambdaQueryWrapper<DocTemplateEntity>()
                .eq(DocTemplateEntity::getCreatedBy, memberId))));
        templateMapper.insert(template);
        return toTemplateDto(template);
    }

    /** 修改当前成员模板的名称、说明与正文快照。 */
    @Transactional
    public DocTemplateDto updateTemplate(String id, PatchDocTemplateRequest req, String memberId) {
        DocTemplateEntity template = requireTemplate(id, memberId);
        LocalDateTime expectedUpdatedAt = checkedTemplateVersion(req.expectedUpdatedAt());
        if (!expectedUpdatedAt.equals(template.getUpdatedAt())) {
            throw new ConflictException("模板已被其他窗口更新，请刷新后再决定是否覆盖");
        }
        String name = req.name() == null ? template.getName() : checkedTemplateName(req.name());
        String description = req.description() == null
                ? template.getDescription()
                : checkedTemplateDescription(req.description());
        String blocksJson = req.blocks() == null
                ? template.getBlocksJson()
                : writeTemplateBlocks(req.blocks());
        if (req.content() != null) applyRichContent(template, req.content());
        else if (req.blocks() != null) applyRichContent(template, null);
        LocalDateTime nextUpdatedAt = LocalDateTime.now().withNano(0);
        if (!nextUpdatedAt.isAfter(expectedUpdatedAt)) {
            // DATETIME 只有秒精度；快速连续保存也必须生成不同版本，否则 CAS 会失去作用。
            nextUpdatedAt = expectedUpdatedAt.plusSeconds(1);
        }
        template.setName(name);
        template.setDescription(description);
        template.setBlocksJson(blocksJson);
        template.setUpdatedAt(nextUpdatedAt);
        if (templateMapper.updateIfUnchanged(template, expectedUpdatedAt) != 1) {
            throw new ConflictException("模板已被其他窗口更新，请刷新后再决定是否覆盖");
        }
        return toTemplateDto(template);
    }

    /** 删除当前成员自己的模板；由模板创建的文档不受影响。 */
    public void deleteTemplate(String id, String memberId) {
        requireTemplate(id, memberId);
        templateMapper.deleteById(id);
    }

    private DocTemplateEntity requireTemplate(String id, String memberId) {
        DocTemplateEntity template = templateMapper.selectById(id);
        if (template == null || !Objects.equals(template.getCreatedBy(), memberId)) {
            throw new NotFoundException("文档模板不存在：" + id);
        }
        return template;
    }

    private List<DocBlockDto> templateBlocks(DocTemplateEntity template) {
        return readList(template.getBlocksJson(), BLOCKS_TYPE);
    }

    /** 校验模板正文的块数与序列化大小，避免无界 JSON 写入数据库。 */
    private String writeTemplateBlocks(List<DocBlockDto> blocks) {
        if (blocks.size() > MAX_TEMPLATE_BLOCK_COUNT) {
            throw new BadRequestException("模板正文不能超过 5000 个块");
        }
        String json = write(blocks);
        if (json.length() > MAX_TEMPLATE_BLOCKS_JSON_LENGTH) {
            throw new BadRequestException("模板正文不能超过 1000000 个字符");
        }
        return json;
    }

    /** 另存模板时也约束来源文档，先看原始长度再解析，避免超大 JSON 占用额外内存。 */
    private String writeSourceTemplateBlocks(String blocksJson) {
        if (blocksJson != null && blocksJson.length() > MAX_TEMPLATE_BLOCKS_JSON_LENGTH) {
            throw new BadRequestException("模板正文不能超过 1000000 个字符");
        }
        return writeTemplateBlocks(readList(blocksJson, BLOCKS_TYPE));
    }

    private String checkedTemplateName(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("模板名称不能为空");
        }
        String name = value.trim();
        if (name.length() > MAX_TEMPLATE_NAME_LENGTH) {
            throw new BadRequestException("模板名称不能超过 128 个字符");
        }
        return name;
    }

    private String checkedTemplateDescription(String value) {
        String description = value.trim();
        if (description.length() > MAX_TEMPLATE_DESCRIPTION_LENGTH) {
            throw new BadRequestException("模板说明不能超过 512 个字符");
        }
        return description;
    }

    private LocalDateTime checkedTemplateVersion(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("模板版本不能为空");
        }
        try {
            return java.time.OffsetDateTime.parse(value)
                    .atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai"))
                    .toLocalDateTime();
        } catch (RuntimeException offsetException) {
            try {
                // 兼容尚未带时区的早期客户端；服务端当前响应统一为 ISO_OFFSET_DATE_TIME。
                return LocalDateTime.parse(value);
            } catch (RuntimeException localException) {
                throw new BadRequestException("模板版本格式不合法");
            }
        }
    }

    private DocTemplateDto toTemplateDto(DocTemplateEntity template) {
        return new DocTemplateDto(template.getId(), template.getName(), template.getDescription(),
                templateBlocks(template), richContentOf(template), template.getSourceDocId(), iso(template.getCreatedAt()),
                iso(template.getUpdatedAt()));
    }

    /**
     * 原子保存知识库完整文档树顺序。
     *
     * <p>请求必须且只能包含知识库当前全部文档一次，避免并发或前端缺陷把遗漏文档静默丢出树。
     */
    @Transactional
    public void reorderDocs(String kbId, ReorderDocsRequest request) {
        KbEntity orderingKb = requireKb(kbId);
        if (!directoryOverrides(orderingKb).isEmpty()) throw new ConflictException("此知识库已使用多级目录，请刷新并通过目录节点移动");
        if (request == null || request.folders() == null) {
            throw new BadRequestException("文档顺序不能为空");
        }
        List<KbFolderEntity> folders = folderMapper.selectList(aliveFolders().eq(KbFolderEntity::getKbId, kbId));
        Set<String> validFolderIds = folders.stream().map(KbFolderEntity::getId).collect(Collectors.toSet());
        List<DocEntity> docs = docMapper.selectList(activeDocs().eq(DocEntity::getKbId, kbId));
        Map<String, DocEntity> docById = docs.stream().collect(Collectors.toMap(DocEntity::getId, d -> d));
        Set<String> seenFolders = new HashSet<>();
        Set<String> seenDocs = new HashSet<>();
        List<DocEntity> orderedDocs = new ArrayList<>(docs.size());

        for (ReorderDocsRequest.FolderOrder order : request.folders()) {
            if (order == null || !validFolderIds.contains(order.folderId()) || !seenFolders.add(order.folderId())) {
                throw new BadRequestException("文档顺序包含无效或重复的文件夹");
            }
            List<String> docIds = order.docIds() == null ? List.of() : order.docIds();
            for (int index = 0; index < docIds.size(); index++) {
                String docId = docIds.get(index);
                DocEntity doc = docById.get(docId);
                if (doc == null || !seenDocs.add(docId)) {
                    throw new BadRequestException("文档顺序包含无效或重复的文档");
                }
                doc.setFolderId(order.folderId());
                doc.setOrd(index + 1);
                orderedDocs.add(doc);
            }
        }
        if (!seenFolders.equals(validFolderIds) || !seenDocs.equals(docById.keySet())) {
            throw new BadRequestException("文档顺序必须包含当前知识库的全部文件夹和文档");
        }
        if (!orderedDocs.isEmpty() && docMapper.updateTreeOrders(kbId, orderedDocs) != orderedDocs.size()) {
            throw new ConflictException("文档树已在其他页面更新，请刷新后重试");
        }
    }

    /**
     * 新建一条小记：不需要选知识库，一律落到小记知识库中「按创建人命名」的文件夹。
     *
     * <p>过去所有人的小记都堆在同一个「小记」文件夹里，谁记的看不出来。现在按作者分文件夹
     * （娃哥的进「娃哥」、娃弟的进「娃弟」），文件夹没有就地建一个；作者查不到成员记录时
     * 仍然回落到默认的「小记」文件夹，保证入口不会因为账号异常而失败。
     *
     * <p>小记知识库若被误删或库里还没有（老库升级），这里会补建一个，保证入口永远可用。
     *
     * @param req    小记内容
     * @param author 作者 member id
     * @return 新建的小记文档
     */
    @Transactional
    public DocDto createNote(CreateNoteRequest req, String author) {
        return createNote("d" + System.currentTimeMillis(), req, author);
    }

    /**
     * 用客户端提供的稳定 id 幂等创建小记。
     *
     * <p>第一次请求已落库但响应丢失时，重试会命中同一篇文档。若用户在重试前继续修改了草稿，
     * 这里把同一作者的同一篇小记更新到最新正文；不会再次插入，也不会再次发送飞书创建通知。
     * 其他作者或其他知识库已经占用该 id 时拒绝写入，避免把碰撞伪装成成功。
     *
     * @param id     客户端草稿持有的文档 id
     * @param req    小记内容
     * @param author 作者 member id
     * @return 新建或更新后的同一篇小记
     */
    @Transactional
    public DocDto createNote(String id, CreateNoteRequest req, String author) {
        if (id == null || !CLIENT_DOC_ID.matcher(id).matches()) {
            throw new BadRequestException("小记文档 id 不合法");
        }
        List<String> tags = req.tags() == null ? null : NoteTagService.normalize(req.tags());
        String resolvedAuthor = blankOr(author, "wg");
        DocEntity existing = docMapper.selectById(id);
        if (existing != null) {
            if (existing.getDeletedAt() != null || !NOTES_KB_ID.equals(existing.getKbId())
                    || !Objects.equals(resolvedAuthor, existing.getAuthorId())) {
                throw new BadRequestException("小记文档 id 已被占用");
            }
            List<DocBlockDto> blocks = req.blocks() == null ? List.of() : req.blocks();
            RichDocContentDto content = req.content() == null ? null : checkedRichContent(req.content());
            String title = blankOr(req.title(), titleFrom(content, blocks));
            String blocksJson = write(blocks);
            String tagsJson = tags == null ? existing.getTagsJson() : write(tags);
            boolean changed = !Objects.equals(tagsJson, existing.getTagsJson())
                    || !Objects.equals(title, existing.getTitle())
                    || !Objects.equals(blocksJson, existing.getBlocksJson())
                    || !sameRichContent(existing, content);
            if (changed) {
                existing.setTagsJson(tagsJson);
                existing.setTitle(title);
                existing.setBlocksJson(blocksJson);
                applyRichContent(existing, content);
                existing.setRev(rev(existing) + 1);
                existing.setUpdatedAt(LocalDateTime.now());
                docMapper.updateContentSnapshot(existing);
            }
            return toDto(existing);
        }
        KbEntity kb = notesKb();
        KbFolderEntity folder = authorFolder(kb.getId(), author);
        List<DocBlockDto> blocks = req.blocks() == null ? List.of() : req.blocks();
        RichDocContentDto content = req.content() == null ? null : checkedRichContent(req.content());
        DocDto doc = insertDoc(id, kb.getId(), folder.getId(), blankOr(req.title(), titleFrom(content, blocks)),
                author, blocks, content, List.of(), tags);
        announceNote(kb.getId(), doc, author);
        return doc;
    }

    /** 新建小记后同步到飞书群；通知失败只记日志，不影响创建结果。 */
    private void announceNote(String kbId, DocDto doc, String author) {
        Runnable notification = () -> {
        try {
            MemberEntity member = author == null || author.isBlank() ? null : memberMapper.selectById(author);
            taskNotifier.notifyNoteCreated(kbId, doc.id(), doc.title(),
                    member == null ? author : member.getName());
        } catch (RuntimeException ex) {
            LOG.warn("新建小记的飞书通知发送失败：{}", ex.getMessage());
        }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { notification.run(); }
                    });
        } else notification.run();
    }

    /**
     * 小记知识库里属于某个作者的文件夹（以成员名命名），不存在就新建。
     *
     * <p>用成员名而不是 member id 命名，是因为这个文件夹名要直接展示给用户看。
     */
    private KbFolderEntity authorFolder(String kbId, String author) {
        MemberEntity member = author == null || author.isBlank() ? null : memberMapper.selectById(author);
        if (member == null || member.getName() == null || member.getName().isBlank()) {
            return notesFolder(kbId);
        }
        String name = member.getName().trim();
        KbFolderEntity existing = folderMapper.selectList(aliveFolders()
                .eq(KbFolderEntity::getKbId, kbId).eq(KbFolderEntity::getName, name))
                .stream().findFirst().orElse(null);
        if (existing != null) {
            return existing;
        }
        KbFolderEntity folder = new KbFolderEntity();
        folder.setId("kb-notes-" + member.getId());
        folder.setKbId(kbId);
        folder.setName(name);
        folder.setOrd(nextOrd(folderMapper.selectCount(aliveFolders().eq(KbFolderEntity::getKbId, kbId))));
        folder.setCascadeDel(false);
        folder.setDeletedAt(null);
        // 同名文件夹被删进回收站过，id 会撞上，这时候复用那条记录并恢复出来。
        if (folderMapper.selectById(folder.getId()) == null) {
            folderMapper.insert(folder);
        } else {
            folderMapper.updateById(folder);
        }
        return folder;
    }

    /** 返回小记知识库（不存在或已被删则重建）。 */
    @Transactional
    public KbEntity notesKb() {
        KbEntity kb = kbMapper.selectList(aliveKbs().eq(KbEntity::getNotes, true)).stream().findFirst().orElse(null);
        if (kb != null) {
            return kb;
        }
        KbEntity existing = kbMapper.selectById(NOTES_KB_ID);
        kb = existing != null ? existing : new KbEntity();
        kb.setId(NOTES_KB_ID);
        kb.setName("小记");
        kb.setIcon("ri-sticky-note-line");
        kb.setColor("#7A5CC6");
        kb.setAuto(false);
        kb.setNotes(true);
        kb.setDescription("随手记下的想法与片段，不用先想清楚该放进哪个知识库。");
        kb.setPerm(blankOr(kb.getPerm(), "仅自己"));
        kb.setOrd(0);
        kb.setDeletedAt(null);
        kb.setDeletedBy(null);
        if (existing != null) {
            kbMapper.updateById(kb);
        } else {
            kbMapper.insert(kb);
        }
        return kb;
    }

    /**
     * 返回系统管理的点子资产库。固定 id 是身份，不按名称猜测，因而不会误复用用户同名知识库。
     */
    @Transactional
    public KbEntity ideaAssetKb() {
        KbEntity existing = kbMapper.selectById(IDEA_ASSET_KB_ID);
        KbEntity kb = existing == null ? new KbEntity() : existing;
        kb.setId(IDEA_ASSET_KB_ID);
        kb.setName("点子资产库");
        kb.setIcon("ri-lightbulb-flash-line");
        kb.setColor("#D99A2B");
        kb.setAuto(true);
        kb.setNotes(false);
        kb.setDescription("由点子库沉淀的调研、方案、会议、验证与放弃报告；团队成员可继续编辑。");
        kb.setPerm("成员可编辑");
        kb.setOrd(1);
        kb.setDeletedAt(null);
        kb.setDeletedBy(null);
        if (existing == null) kbMapper.insert(kb); else kbMapper.updateById(kb);
        return kb;
    }

    /** 为一个点子建立稳定文件夹；标题变化不会打断关联。 */
    @Transactional
    public KbFolderEntity ideaFolder(String ideaId, String title) {
        ideaAssetKb();
        String id = "if-" + ideaId;
        KbFolderEntity existing = folderMapper.selectById(id);
        KbFolderEntity folder = existing == null ? new KbFolderEntity() : existing;
        folder.setId(id);
        folder.setKbId(IDEA_ASSET_KB_ID);
        folder.setName(blankOr(title, "未命名点子") + " · " + ideaId.substring(Math.max(0, ideaId.length() - 6)));
        folder.setOrd(nextOrd(folderMapper.selectCount(aliveFolders().eq(KbFolderEntity::getKbId, IDEA_ASSET_KB_ID))));
        folder.setCascadeDel(false);
        folder.setDeletedAt(null);
        if (existing == null) folderMapper.insert(folder); else folderMapper.updateById(folder);
        return folder;
    }

    /** 新建一篇可继续编辑的点子资产文档；每次调研都创建新文档，不覆盖人工修改。 */
    @Transactional
    public DocDto createIdeaDocument(String ideaId, String ideaTitle, String title, String content, String author) {
        KbFolderEntity folder = ideaFolder(ideaId, ideaTitle);
        List<DocBlockDto> blocks = ideaBlocks(ideaTitle, content);
        return insertDoc(IDEA_ASSET_KB_ID, folder.getId(), title, author, blocks);
    }

    /**
     * 幂等保存一份聊天复盘；固定知识库和运行派生文档 id 避免 worker 接管时重复沉淀。
     */
    @Transactional
    public DocDto saveChatReviewReport(String runId, String title,
            cn.wayuwa.wao.dto.ChatReviewReportDto report, String reportUrl, String author) {
        KbEntity existingKb = kbMapper.selectById(CHAT_REVIEW_KB_ID);
        KbEntity kb = existingKb == null ? new KbEntity() : existingKb;
        kb.setId(CHAT_REVIEW_KB_ID);
        kb.setName("聊天复盘");
        kb.setIcon("ri-chat-history-line");
        kb.setColor("#4D6BDE");
        kb.setAuto(true);
        kb.setNotes(false);
        kb.setDescription("由 WAO 共享聊天中的真实消息生成；每份报告保留讨论过程、方向、结论、建议与公开报告入口。");
        kb.setPerm("成员可编辑");
        kb.setOrd(2);
        kb.setDeletedAt(null);
        kb.setDeletedBy(null);
        if (existingKb == null) kbMapper.insert(kb); else kbMapper.updateById(kb);

        KbFolderEntity existingFolder = folderMapper.selectById(CHAT_REVIEW_FOLDER_ID);
        KbFolderEntity folder = existingFolder == null ? new KbFolderEntity() : existingFolder;
        folder.setId(CHAT_REVIEW_FOLDER_ID);
        folder.setKbId(CHAT_REVIEW_KB_ID);
        folder.setName("复盘报告");
        folder.setOrd(1);
        folder.setCascadeDel(false);
        folder.setDeletedAt(null);
        if (existingFolder == null) folderMapper.insert(folder); else folderMapper.updateById(folder);

        List<DocBlockDto> blocks = List.of(
                new DocBlockDto("AI 依据所选共享聊天消息生成；模型输出已经过字段、引用和外链校验。公开报告：" + reportUrl,
                        null, null, null, null),
                new DocBlockDto(null, "讨论过程", null, null, null, 2),
                new DocBlockDto(null, null, report.discussionProcess(), null, null),
                new DocBlockDto(null, "讨论方向", null, null, null, 2),
                new DocBlockDto(null, null, report.discussionDirections(), null, null),
                new DocBlockDto(null, "讨论结论", null, null, null, 2),
                new DocBlockDto(null, null, report.conclusions(), null, null),
                new DocBlockDto(null, "后续建议", null, null, null, 2),
                new DocBlockDto(null, null, report.recommendations(), null, null));
        String docId = "crd-" + runId;
        DocEntity existingDoc = docMapper.selectById(docId);
        if (existingDoc == null) {
            insertDoc(docId, CHAT_REVIEW_KB_ID, CHAT_REVIEW_FOLDER_ID, title, author, blocks);
            existingDoc = docMapper.selectById(docId);
        }
        existingDoc.setKbId(CHAT_REVIEW_KB_ID);
        existingDoc.setFolderId(CHAT_REVIEW_FOLDER_ID);
        existingDoc.setTitle(title);
        existingDoc.setAuthorId(blankOr(author, "auto"));
        existingDoc.setAuto(true);
        existingDoc.setBlocksJson(write(blocks));
        applyRichContent(existingDoc, null);
        existingDoc.setLinksJson("[]");
        existingDoc.setDeletedAt(null);
        existingDoc.setDeletedBy(null);
        existingDoc.setCascadeDel(false);
        existingDoc.setRev(rev(existingDoc) + 1);
        existingDoc.setUpdatedAt(LocalDateTime.now());
        docMapper.updateContentSnapshot(existingDoc);
        return toDto(existingDoc);
    }

    /** 兼容旧客户端入口；文档只通过具体任务关联，不再创建项目知识库。 */
    public DocDto ensureProjectBrief(ProjectDto project, String author) {
        throw new BadRequestException("项目不再直接关联文档，请在知识库创建文档后关联到具体任务");
    }

    /** 把旧点子的完整历史沉淀到独立的点子资产库；稳定 id 保证重复归并不产生副本。 */
    @Transactional
    public DocDto ensureIdeaArchive(ProjectDto project, IdeaDto idea, String author) {
        String id = stableDocId("idea-archive", idea.id());
        DocEntity existing = docMapper.selectById(id);
        if (existing != null) {
            existing.setDeletedAt(null);
            existing.setCascadeDel(false);
            docMapper.updateById(existing);
            return toDto(existing);
        }
        KbFolderEntity folder = ideaFolder(idea.id(), idea.title());
        StringBuilder content = new StringBuilder();
        content.append("## 原始想法\n\n").append(blankOr(idea.rawIdea(), "未填写"));
        content.append("\n\n## 要解决的问题\n\n").append(blankOr(idea.problem(), "未填写"));
        content.append("\n\n## 期望结果\n\n").append(blankOr(idea.targetOutcome(), "未填写"));
        if (!idea.iterations().isEmpty()) {
            content.append("\n\n## 历史迭代\n");
            for (IdeaDto.Iteration iteration : idea.iterations()) {
                content.append("\n- ").append(iteration.objective()).append(" · ").append(iteration.status());
                if (StringUtils.hasText(iteration.feedback())) content.append(" · 反馈：").append(iteration.feedback());
                if (StringUtils.hasText(iteration.reportUrl())) content.append(" · 报告：").append(iteration.reportUrl());
            }
        }
        if (idea.detail().activity() != null && !idea.detail().activity().isEmpty()) {
            content.append("\n\n## 历史活动\n");
            for (IdeaDto.Activity activity : idea.detail().activity()) {
                content.append("\n- ").append(activity.at()).append(" · ").append(activity.action());
            }
        }
        return insertDoc(id, folder.getKbId(), folder.getId(), "原始点子与迭代历史 · " + idea.title(), author,
                ideaBlocks(idea.title(), content.toString()), List.of());
    }

    private static String stableDocId(String namespace, String value) {
        return UUID.nameUUIDFromBytes((namespace + ":" + value).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
    }

    /** 把调研 Markdown 的常用结构转成 WAO 原生块，文档打开后仍可逐块编辑。 */
    private static List<DocBlockDto> ideaBlocks(String ideaTitle, String content) {
        List<DocBlockDto> blocks = new ArrayList<>();
        blocks.add(new DocBlockDto("点子资产 · " + ideaTitle, null, null, null, null));
        List<String> bullets = new ArrayList<>();
        for (String raw : (content == null ? "" : content.trim()).split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("- ") || line.startsWith("* ")) {
                bullets.add(line.substring(2).trim());
                continue;
            }
            if (!bullets.isEmpty()) {
                blocks.add(new DocBlockDto(null, null, null, List.copyOf(bullets), null));
                bullets.clear();
            }
            if (line.isBlank()) continue;
            int heading = 0;
            while (heading < line.length() && heading < 6 && line.charAt(heading) == '#') heading++;
            if (heading > 0 && heading < line.length() && Character.isWhitespace(line.charAt(heading))) {
                blocks.add(new DocBlockDto(null, line.substring(heading).trim(), null, null, null,
                        Math.max(1, Math.min(heading, 6))));
            } else if (line.startsWith("> ")) {
                blocks.add(new DocBlockDto(null, null, null, null, List.of(line.substring(2).trim())));
            } else {
                blocks.add(new DocBlockDto(null, null, line, null, null));
            }
        }
        if (!bullets.isEmpty()) blocks.add(new DocBlockDto(null, null, null, List.copyOf(bullets), null));
        if (blocks.size() == 1) blocks.add(new DocBlockDto(null, null, "", null, null));
        return blocks;
    }

    /**
     * 小记知识库的兜底文件夹（作者不明时用）。
     *
     * <p>必须按固定 id 定位，不能取「第一个文件夹」：库里现在还有按成员名建的文件夹，
     * 取第一个会把匿名小记塞进某个人的文件夹里。
     */
    private KbFolderEntity notesFolder(String kbId) {
        KbFolderEntity alive = folderMapper.selectList(aliveFolders().eq(KbFolderEntity::getKbId, kbId)
                .eq(KbFolderEntity::getId, NOTES_FOLDER_ID)).stream().findFirst().orElse(null);
        if (alive != null) {
            return alive;
        }
        KbFolderEntity folder;
        KbFolderEntity existing = folderMapper.selectById(NOTES_FOLDER_ID);
        folder = existing != null ? existing : new KbFolderEntity();
        folder.setId(NOTES_FOLDER_ID);
        folder.setKbId(kbId);
        folder.setName("小记");
        folder.setOrd(1);
        folder.setCascadeDel(false);
        folder.setDeletedAt(null);
        if (existing != null) {
            folderMapper.updateById(folder);
        } else {
            folderMapper.insert(folder);
        }
        return folder;
    }

    private DocDto insertDoc(String kbId, String folderId, String title, String author, List<DocBlockDto> blocks) {
        return insertDoc(kbId, folderId, title, author, blocks, List.of());
    }

    private DocDto insertDoc(String kbId, String folderId, String title, String author, List<DocBlockDto> blocks,
            List<DocLinkDto> links) {
        return insertDoc("d" + UUID.randomUUID().toString().replace("-", "").substring(0, 31),
                kbId, folderId, title, author, blocks, null, links);
    }

    private DocDto insertDoc(String kbId, String folderId, String title, String author, List<DocBlockDto> blocks,
            RichDocContentDto content, List<DocLinkDto> links) {
        return insertDoc("d" + UUID.randomUUID().toString().replace("-", "").substring(0, 31),
                kbId, folderId, title, author, blocks, content, links);
    }

    private DocDto insertDoc(String id, String kbId, String folderId, String title, String author,
            List<DocBlockDto> blocks) {
        return insertDoc(id, kbId, folderId, title, author, blocks, null, List.of());
    }

    private DocDto insertDoc(String id, String kbId, String folderId, String title, String author,
            List<DocBlockDto> blocks, List<DocLinkDto> links) {
        return insertDoc(id, kbId, folderId, title, author, blocks, null, links);
    }

    private DocDto insertDoc(String id, String kbId, String folderId, String title, String author,
            List<DocBlockDto> blocks, RichDocContentDto content, List<DocLinkDto> links) {
        return insertDoc(id, kbId, folderId, title, author, blocks, content, links, null);
    }

    private DocDto insertDoc(String id, String kbId, String folderId, String title, String author,
            List<DocBlockDto> blocks, RichDocContentDto content, List<DocLinkDto> links, List<String> tags) {
        Long count = docMapper.selectCount(aliveDocs().eq(DocEntity::getFolderId, folderId));
        LocalDateTime now = LocalDateTime.now();

        DocEntity e = new DocEntity();
        e.setId(id);
        e.setKbId(kbId);
        e.setFolderId(folderId);
        e.setTitle(blankOr(title, ""));
        e.setAuthorId(blankOr(author, "wg"));
        e.setAuto(false);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        e.setRev(1);
        e.setCascadeDel(false);
        e.setBlocksJson(write(blocks));
        applyRichContent(e, content);
        e.setLinksJson(write(links));
        e.setTagsJson(tags == null ? null : write(tags));
        e.setOrd(nextOrd(count));
        docMapper.insert(e);
        return toDto(e);
    }

    /**
     * 自动化写入一篇文档：同一知识库 / 文件夹下标题相同的文档直接覆盖正文，否则新建。
     *
     * <p>「按标题覆盖」是给每天一篇的沉淀任务用的 —— 同一天补跑一次只该更新那一篇，
     * 不该在知识库里堆出一串同名文档。作者记为 {@code auto}，前端会显示成「自动化」。
     *
     * @param kbId     目标知识库 id
     * @param folderId 目标文件夹 id
     * @param title    文档标题（同时也是幂等键）
     * @param blocks   正文块
     * @return 新建或更新后的文档
     */
    @Transactional
    public DocDto upsertAutoDoc(String kbId, String folderId, String title, List<DocBlockDto> blocks) {
        lockAliveKb(kbId);
        KbFolderEntity folder = requireFolder(kbId, folderId);
        DocEntity existing = docMapper.selectList(aliveDocs()
                        .eq(DocEntity::getFolderId, folder.getId()).eq(DocEntity::getTitle, title))
                .stream().findFirst().orElse(null);
        if (existing == null) {
            DocDto created = insertDoc(kbId, folder.getId(), title, "auto", blocks);
            DocEntity inserted = requireDoc(created.id());
            inserted.setAuto(true);
            docMapper.updateById(inserted);
            return toDto(inserted);
        }
        if (existing.getAutoSyncScope() != null && !existing.getAutoSyncScope().isBlank()
                && existing.getAutoSyncDate() != null) {
            throw new ConflictException("该文档已绑定稳定归档身份，必须使用带版本的同步路径");
        }
        existing.setBlocksJson(write(blocks));
        applyRichContent(existing, null);
        existing.setAuto(true);
        existing.setRev(rev(existing) + 1);
        existing.setUpdatedAt(LocalDateTime.now());
        docMapper.updateContentSnapshot(existing);
        return toDto(existing);
    }

    /**
     * 按稳定来源与自然日幂等覆盖自动归档，并用数据库签发版本拒绝旧快照晚到。
     *
     * <p>V77 会根据迁移当时的唯一群配置回填旧文档身份，保留原文档 id 和链接。
     * 运行时不会猜测尚未标记的文档属于哪个群；切换群后同日会建立独立文档。
     *
     * @param kbId 目标知识库 id
     * @param folderId 目标文件夹 id
     * @param title 文档标题
     * @param blocks 正文块
     * @param syncScope 稳定来源标识（飞书群聊使用 chat_id）
     * @param syncDate 归档自然日
     * @param syncVersion 抓取前签发的单调版本
     * @return 实际保存在知识库中的文档
     */
    @Transactional
    public DocDto upsertAutoDoc(String kbId, String folderId, String title,
            List<DocBlockDto> blocks, String syncScope, LocalDate syncDate, long syncVersion) {
        lockAliveKb(kbId);
        KbFolderEntity folder = requireFolder(kbId, folderId);
        DocEntity existing = docMapper.selectList(new LambdaQueryWrapper<DocEntity>()
                        .eq(DocEntity::getKbId, kbId)
                        .eq(DocEntity::getAutoSyncScope, syncScope)
                        .eq(DocEntity::getAutoSyncDate, syncDate))
                .stream().findFirst().orElse(null);
        if (existing == null) {
            DocDto created = insertDoc(kbId, folder.getId(), title, "auto", blocks);
            if (docMapper.initializeAutoArchive(created.id(), syncScope, syncDate, syncVersion) != 1) {
                throw new IllegalStateException("自动归档文档初始化失败");
            }
            DocEntity e = requireDoc(created.id());
            return toDto(e);
        }
        if (docMapper.updateAutoArchive(existing.getId(), kbId, folder.getId(), title, write(blocks),
                syncScope, syncDate, syncVersion) != 1) {
            throw new StaleChatArchiveException();
        }
        DocEntity saved = requireDoc(existing.getId());
        return toDto(saved);
    }

    /**
     * 自动归档以知识库父行作为数据库互斥锁。
     *
     * <p>手动补录、定时同步和聊天复盘都会经过这条路径；即使分布在不同 API 节点，
     * 也必须等前一个事务完成后再查找同名文档，不依赖 JVM 锁。
     */
    private KbEntity lockAliveKb(String kbId) {
        KbEntity kb = kbMapper.selectByIdForUpdate(kbId);
        if (kb == null || kb.getDeletedAt() != null) {
            throw new NotFoundException("知识库不存在：" + kbId);
        }
        return kb;
    }

    /** 局部更新文档：标题 / 正文块 / 出链 / 所在文件夹。正文或标题变化时版本号 +1。 */
    public DocDto updateDoc(String id, PatchDocRequest req) {
        DocEntity e = requireDoc(id);
        if (e.getAutoSyncScope() != null && !e.getAutoSyncScope().isBlank() && e.getAutoSyncDate() != null
                && e.getAutoSyncVersion() != null) {
            return updateVersionedArchive(e, req);
        }
        int currentRev = rev(e);
        if (req.expectedRev() != null && req.expectedRev() != currentRev) {
            throw new ConflictException("文档已在其他窗口更新，请刷新后处理本机草稿");
        }
        boolean contentChanged = false;
        if (req.tags() != null) {
            if (!NOTES_KB_ID.equals(e.getKbId())) throw new BadRequestException("只有小记支持标签");
            String nextTags = write(NoteTagService.normalize(req.tags()));
            contentChanged = !Objects.equals(nextTags, e.getTagsJson());
            e.setTagsJson(nextTags);
        }
        if (req.title() != null && !req.title().equals(e.getTitle())) {
            e.setTitle(req.title());
            contentChanged = true;
        }
        if (req.blocks() != null) {
            String next = write(req.blocks());
            contentChanged |= !next.equals(e.getBlocksJson());
            e.setBlocksJson(next);
        }
        if (req.content() != null) {
            RichDocContentDto content = checkedRichContent(req.content());
            contentChanged |= !sameRichContent(e, content);
            applyRichContent(e, content);
        } else if (req.blocks() != null) {
            contentChanged |= e.getContentJson() != null || e.getContentMarkdown() != null
                    || e.getContentVersion() != null;
            applyRichContent(e, null);
        }
        if (req.links() != null) {
            e.setLinksJson(write(withoutProjectLinks(req.links())));
        }
        if (req.folderId() != null && !req.folderId().isBlank()) {
            requireFolder(e.getKbId(), req.folderId());
            e.setFolderId(req.folderId());
        }
        if (contentChanged) {
            e.setRev(currentRev + 1);
            e.setUpdatedAt(LocalDateTime.now());
        }
        if (req.expectedRev() != null) {
            if (docMapper.updateIfRevision(e, currentRev) != 1) {
                throw new ConflictException("文档已在其他窗口更新，请刷新后处理本机草稿");
            }
        } else {
            docMapper.updateContentSnapshot(e);
        }
        return toDto(e);
    }

    private DocDto updateVersionedArchive(DocEntity e, PatchDocRequest req) {
        if (req.expectedRev() != null && req.expectedRev() != rev(e)) {
            throw new ConflictException("文档已在其他窗口更新，请刷新后处理本机草稿");
        }
        String title = req.title() == null ? e.getTitle() : req.title();
        String blocks = req.blocks() == null ? e.getBlocksJson() : write(req.blocks());
        RichDocContentDto content = req.content() == null ? null : checkedRichContent(req.content());
        String contentJson = req.content() == null
                ? (req.blocks() == null ? e.getContentJson() : null)
                : (content.document() == null ? null : write(content.document()));
        String contentMarkdown = req.content() == null
                ? (req.blocks() == null ? e.getContentMarkdown() : null)
                : content.markdown();
        Integer contentVersion = req.content() == null
                ? (req.blocks() == null ? e.getContentVersion() : null)
                : content.version();
        String links = req.links() == null ? e.getLinksJson() : write(withoutProjectLinks(req.links()));
        String folderId = e.getFolderId();
        if (req.folderId() != null && !req.folderId().isBlank()) {
            requireFolder(e.getKbId(), req.folderId());
            folderId = req.folderId();
        }
        boolean contentChanged = !Objects.equals(title, e.getTitle())
                || !Objects.equals(blocks, e.getBlocksJson())
                || !Objects.equals(contentJson, e.getContentJson())
                || !Objects.equals(contentMarkdown, e.getContentMarkdown())
                || !Objects.equals(contentVersion, e.getContentVersion());
        boolean changed = contentChanged || !Objects.equals(links, e.getLinksJson())
                || !Objects.equals(folderId, e.getFolderId());
        if (!changed) return toDto(e);

        long nextVersion = archiveVersions.nextVersion();
        int nextRev = contentChanged ? rev(e) + 1 : rev(e);
        int updated = req.content() == null
                ? docMapper.updateAutoArchiveManually(e.getId(), folderId, title, blocks, links, nextRev,
                        e.getAutoSyncScope(), e.getAutoSyncDate(), e.getAutoSyncVersion(), nextVersion)
                : docMapper.updateAutoArchiveManuallyRich(e.getId(), folderId, title, blocks, links,
                        contentJson, contentMarkdown, contentVersion, nextRev,
                        e.getAutoSyncScope(), e.getAutoSyncDate(), e.getAutoSyncVersion(), nextVersion);
        if (updated != 1) {
            throw new ConflictException("飞书聊天归档已被同步更新，请刷新后重试");
        }
        DocEntity saved = requireDoc(e.getId());
        return toDto(saved);
    }

    /**
     * 把文档移入回收站。
     *
     * @param id       文档 id
     * @param memberId 操作人 member id
     */
    @Transactional
    public void deleteDoc(String id, String memberId) {
        DocEntity e = requireDoc(id);
        kbMapper.selectByIdForUpdate(e.getKbId());
        requireNoNestedChildren(e.getKbId(), "d:" + id);
        markDocDeleted(e, LocalDateTime.now(), memberId, false);
    }

    /** 只允许撤回飞书原消息时删除它创建的小记，其他知识库文档不能走这条入口。 */
    @Transactional
    public void deleteNoteFromChat(String id) {
        DocEntity note = docMapper.selectById(id);
        if (note == null || note.getDeletedAt() != null) return;
        if (!NOTES_KB_ID.equals(note.getKbId())) {
            throw new BadRequestException("飞书收集目标不是小记");
        }
        deleteDoc(id, note.getAuthorId());
    }

    /**
     * 归档文档：保留正文、链接和原目录位置，只把它移出普通列表与知识检索。
     *
     * @param id 文档 id
     * @param memberId 操作人 member id
     * @return 已归档文档
     */
    @Transactional
    public DocDto archiveDoc(String id, String memberId) {
        DocEntity e = requireDoc(id);
        kbMapper.selectByIdForUpdate(e.getKbId());
        requireNoNestedChildren(e.getKbId(), "d:" + id);
        if (e.getArchivedAt() == null) {
            LocalDateTime now = LocalDateTime.now();
            if (docMapper.markArchived(id, now, memberId) != 1) {
                throw new ConflictException("文档归档状态已变化，请刷新后重试");
            }
            e.setArchivedAt(now);
            e.setArchivedBy(memberId);
        }
        return toDto(e);
    }

    /**
     * 取消归档：校验原知识库和文件夹仍可用后，将文档放回原目录。
     *
     * @param id 文档 id
     * @return 已恢复到日常文档树的文档
     */
    public DocDto unarchiveDoc(String id) {
        DocEntity e = requireDoc(id);
        requireKb(e.getKbId());
        requireFolder(e.getKbId(), e.getFolderId());
        if (e.getArchivedAt() != null) {
            if (docMapper.clearArchived(id) != 1) {
                throw new ConflictException("文档归档状态已变化，请刷新后重试");
            }
            e.setArchivedAt(null);
            e.setArchivedBy(null);
        }
        return toDto(e);
    }

    private DocEntity requireDoc(String id) {
        DocEntity e = docMapper.selectById(id);
        if (e == null || e.getDeletedAt() != null) {
            throw new NotFoundException("文档不存在：" + id);
        }
        return e;
    }

    private void markDocDeleted(DocEntity d, LocalDateTime now, String memberId, boolean cascade) {
        d.setDeletedAt(now);
        d.setDeletedBy(memberId);
        d.setCascadeDel(cascade);
        docMapper.markDeleted(d.getId(), now, memberId, cascade);
    }

    // ---------------- 回收站 ----------------

    /**
     * 列出回收站条目（时间倒序）。
     *
     * <p>随父级一起被删的文件夹 / 文档不单独列出 —— 它们跟着父级恢复，单独摆出来只会
     * 让人以为删了一次却多出十几条。
     */
    public List<TrashEntryDto> listTrash() {
        List<TrashEntryDto> out = new ArrayList<>();
        Map<String, KbEntity> kbById = kbMapper.selectList(null).stream()
                .collect(Collectors.toMap(KbEntity::getId, k -> k, (a, b) -> a));
        Map<String, KbFolderEntity> folderById = folderMapper.selectList(null).stream()
                .collect(Collectors.toMap(KbFolderEntity::getId, f -> f, (a, b) -> a));

        for (KbEntity kb : kbMapper.selectList(new LambdaQueryWrapper<KbEntity>().isNotNull(KbEntity::getDeletedAt))) {
            out.add(new TrashEntryDto("kb", kb.getId(), kb.getName(), "知识库",
                    iso(kb.getDeletedAt()), kb.getDeletedBy(), iso(expiry(kb.getDeletedAt()))));
        }
        for (KbFolderEntity f : folderMapper.selectList(new LambdaQueryWrapper<KbFolderEntity>()
                .isNotNull(KbFolderEntity::getDeletedAt).ne(KbFolderEntity::getCascadeDel, true))) {
            KbEntity kb = kbById.get(f.getKbId());
            out.add(new TrashEntryDto("folder", f.getId(), f.getName(),
                    "文件夹 · " + (kb == null ? "已删除的知识库" : kb.getName()),
                    iso(f.getDeletedAt()), null, iso(expiry(f.getDeletedAt()))));
        }
        for (DocEntity d : docMapper.selectList(new LambdaQueryWrapper<DocEntity>()
                .isNotNull(DocEntity::getDeletedAt).ne(DocEntity::getCascadeDel, true))) {
            KbEntity kb = kbById.get(d.getKbId());
            KbFolderEntity f = folderById.get(d.getFolderId());
            String parent = (kb == null ? "已删除的知识库" : kb.getName()) + (f == null ? "" : " / " + f.getName());
            out.add(new TrashEntryDto("doc", d.getId(), blankOr(d.getTitle(), "未命名文档"), "文档 · " + parent,
                    iso(d.getDeletedAt()), d.getDeletedBy(), iso(expiry(d.getDeletedAt()))));
        }
        out.sort(Comparator.comparing(TrashEntryDto::deletedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    /**
     * 从回收站恢复一个条目。
     *
     * <p>恢复文档 / 文件夹时，若它的知识库还躺在回收站里，会把这条链上的父级一起恢复 ——
     * 否则恢复出来的东西没有容身之处，在界面上等于没恢复。
     *
     * @param type 条目类型：kb / folder / doc
     * @param id   条目 id
     */
    @Transactional
    public void restore(String type, String id) {
        switch (type) {
            case "kb" -> restoreKb(id);
            case "folder" -> restoreFolder(id);
            case "doc" -> restoreDoc(id);
            default -> throw new BadRequestException("未知的回收站条目类型：" + type);
        }
    }

    private void restoreKb(String id) {
        KbEntity kb = kbMapper.selectById(id);
        if (kb == null || kb.getDeletedAt() == null) {
            throw new NotFoundException("回收站里没有这个知识库：" + id);
        }
        kb.setDeletedAt(null);
        kb.setDeletedBy(null);
        kbMapper.clearDeleted(kb.getId());
        for (KbFolderEntity f : folderMapper.selectList(new LambdaQueryWrapper<KbFolderEntity>()
                .eq(KbFolderEntity::getKbId, id).eq(KbFolderEntity::getCascadeDel, true))) {
            f.setDeletedAt(null);
            f.setCascadeDel(false);
            folderMapper.clearDeleted(f.getId());
        }
        for (DocEntity d : docMapper.selectList(new LambdaQueryWrapper<DocEntity>()
                .eq(DocEntity::getKbId, id).eq(DocEntity::getCascadeDel, true))) {
            clearDocDeleted(d);
        }
    }

    private void restoreFolder(String id) {
        KbFolderEntity folder = folderMapper.selectById(id);
        if (folder == null || folder.getDeletedAt() == null) {
            throw new NotFoundException("回收站里没有这个文件夹：" + id);
        }
        folder.setDeletedAt(null);
        folder.setCascadeDel(false);
        folderMapper.clearDeleted(folder.getId());
        for (DocEntity d : docMapper.selectList(new LambdaQueryWrapper<DocEntity>()
                .eq(DocEntity::getFolderId, id).eq(DocEntity::getCascadeDel, true))) {
            clearDocDeleted(d);
        }
        reviveKbOf(folder.getKbId());
    }

    private void restoreDoc(String id) {
        DocEntity doc = docMapper.selectById(id);
        if (doc == null || doc.getDeletedAt() == null) {
            throw new NotFoundException("回收站里没有这篇文档：" + id);
        }
        clearDocDeleted(doc);
        KbFolderEntity folder = folderMapper.selectById(doc.getFolderId());
        if (folder != null && folder.getDeletedAt() != null) {
            folder.setDeletedAt(null);
            folder.setCascadeDel(false);
            folderMapper.clearDeleted(folder.getId());
        }
        reviveKbOf(doc.getKbId());
    }

    private void reviveKbOf(String kbId) {
        KbEntity kb = kbMapper.selectById(kbId);
        if (kb != null && kb.getDeletedAt() != null) {
            kb.setDeletedAt(null);
            kb.setDeletedBy(null);
            kbMapper.clearDeleted(kb.getId());
        }
    }

    private void clearDocDeleted(DocEntity d) {
        d.setDeletedAt(null);
        d.setDeletedBy(null);
        d.setCascadeDel(false);
        docMapper.clearDeleted(d.getId());
    }

    /**
     * 从回收站彻底删除一个条目（不可恢复）。
     *
     * @param type 条目类型：kb / folder / doc
     * @param id   条目 id
     */
    @Transactional
    public void purge(String type, String id) {
        switch (type) {
            case "kb" -> {
                docMapper.delete(new LambdaQueryWrapper<DocEntity>().eq(DocEntity::getKbId, id));
                folderMapper.delete(new LambdaQueryWrapper<KbFolderEntity>().eq(KbFolderEntity::getKbId, id));
                kbMapper.deleteById(id);
            }
            case "folder" -> {
                docMapper.delete(new LambdaQueryWrapper<DocEntity>().eq(DocEntity::getFolderId, id));
                folderMapper.deleteById(id);
            }
            case "doc" -> docMapper.deleteById(id);
            default -> throw new BadRequestException("未知的回收站条目类型：" + type);
        }
    }

    /**
     * 物理删除已过保留期的回收站条目。
     *
     * @param now 当前时间
     * @return 被彻底删除的条目数（知识库 / 文件夹 / 文档合计）
     */
    @Transactional
    public int purgeExpired(LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(TRASH_DAYS);
        int removed = 0;
        for (KbEntity kb : kbMapper.selectList(new LambdaQueryWrapper<KbEntity>()
                .isNotNull(KbEntity::getDeletedAt).lt(KbEntity::getDeletedAt, cutoff))) {
            purge("kb", kb.getId());
            removed++;
        }
        for (KbFolderEntity f : folderMapper.selectList(new LambdaQueryWrapper<KbFolderEntity>()
                .isNotNull(KbFolderEntity::getDeletedAt).lt(KbFolderEntity::getDeletedAt, cutoff))) {
            purge("folder", f.getId());
            removed++;
        }
        for (DocEntity d : docMapper.selectList(new LambdaQueryWrapper<DocEntity>()
                .isNotNull(DocEntity::getDeletedAt).lt(DocEntity::getDeletedAt, cutoff))) {
            purge("doc", d.getId());
            removed++;
        }
        return removed;
    }

    // ---------------- 组装 ----------------

    private LambdaQueryWrapper<KbEntity> aliveKbs() {
        return new LambdaQueryWrapper<KbEntity>().isNull(KbEntity::getDeletedAt);
    }

    private LambdaQueryWrapper<KbFolderEntity> aliveFolders() {
        return new LambdaQueryWrapper<KbFolderEntity>().isNull(KbFolderEntity::getDeletedAt);
    }

    private LambdaQueryWrapper<DocEntity> aliveDocs() {
        return new LambdaQueryWrapper<DocEntity>().isNull(DocEntity::getDeletedAt);
    }

    /** 日常使用的文档：既没有删除，也没有被用户归档。 */
    private LambdaQueryWrapper<DocEntity> activeDocs() {
        return aliveDocs().isNull(DocEntity::getArchivedAt);
    }

    private Map<String, List<String>> docIdsByFolder(List<DocEntity> docs) {
        return docs.stream().collect(Collectors.groupingBy(DocEntity::getFolderId,
                Collectors.mapping(DocEntity::getId, Collectors.toList())));
    }

    private Map<String, String> directoryOverrides(KbEntity kb) {
        if (kb.getDirectoryJson() == null || kb.getDirectoryJson().isBlank()) return Map.of();
        try {
            Map<String, String> raw = json.readValue(kb.getDirectoryJson(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
            Map<String, String> links = new java.util.LinkedHashMap<>();
            raw.forEach((key, value) -> { if (key.startsWith("f:") || key.startsWith("d:")) links.put(key, value); });
            return links;
        }
        catch (java.io.IOException ex) { throw new IllegalStateException("知识库目录数据无法读取", ex); }
    }

    private List<String> directoryOrder(KbEntity kb) {
        if (kb.getDirectoryJson() == null || kb.getDirectoryJson().isBlank()) return List.of();
        try {
            Map<String, String> raw = json.readValue(kb.getDirectoryJson(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
            String order = raw.get("__order");
            return order == null || order.isBlank() ? List.of() : List.of(order.split(","));
        } catch (java.io.IOException ex) { throw new IllegalStateException("知识库目录数据无法读取", ex); }
    }

    private Map<String, String> directoryParents(KbEntity kb, List<KbFolderEntity> folders, List<DocEntity> docs) {
        Map<String, String> parents = new java.util.LinkedHashMap<>();
        for (KbFolderEntity folder : folders) parents.put("f:" + folder.getId(), "");
        for (DocEntity doc : docs) parents.put("d:" + doc.getId(), "f:" + doc.getFolderId());
        Map<String, String> overrides = directoryOverrides(kb);
        List<String> order = directoryOrder(kb);
        if (!order.isEmpty()) {
            Map<String, String> ordered = new java.util.LinkedHashMap<>();
            java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>(order);
            keys.addAll(parents.keySet());
            for (String node : keys) {
                if (!parents.containsKey(node)) continue;
                String parent = overrides.get(node);
                ordered.put(node, parent != null && (parent.isEmpty() || parents.containsKey(parent)) ? parent : parents.get(node));
            }
            return ordered;
        }
        overrides.forEach((node, parent) -> {
            if (parents.containsKey(node) && parent != null && (parent.isEmpty() || parents.containsKey(parent))) parents.put(node, parent);
        });
        return parents;
    }

    /** 原子移动分组或文档，保留整个子树，串行校验当前父级并拒绝循环与跨库引用。 */
    @Transactional
    public KbDto moveDirectoryNode(String kbId, cn.wayuwa.wao.dto.MoveKnowledgeNodeRequest request) {
        requireKb(kbId);
        KbEntity kb = kbMapper.selectByIdForUpdate(kbId);
        if (kb == null || kb.getDeletedAt() != null) throw new NotFoundException("知识库不存在");
        List<KbFolderEntity> folders = folderMapper.selectList(aliveFolders().eq(KbFolderEntity::getKbId, kbId));
        List<DocEntity> docs = docMapper.selectList(aliveDocs().eq(DocEntity::getKbId, kbId));
        Map<String, String> parents = directoryParents(kb, folders, docs);
        if (request == null || !parents.containsKey(request.node()) || request.parent() == null
                || (!request.parent().isEmpty() && !parents.containsKey(request.parent()))) {
            throw new BadRequestException("目录节点不存在或不属于当前知识库");
        }
        if (!Objects.equals(parents.get(request.node()), request.expectedParent())) {
            throw new ConflictException("目录已在其他窗口移动，请刷新后重试");
        }
        String before = request.beforeSibling();
        if (before != null && (!parents.containsKey(before) || before.equals(request.node())
                || !Objects.equals(parents.get(before), request.parent()))) {
            throw new BadRequestException("目标位置不在同一层目录中");
        }
        parents.put(request.node(), request.parent());
        KnowledgeDirectory.validate(parents);
        // LinkedHashMap 的重插入确定同级次序，避免文档与文件夹各自排序后重新交错。
        Map<String, String> reordered = new java.util.LinkedHashMap<>();
        parents.forEach((node, parent) -> {
            if (node.equals(request.node())) return;
            if (node.equals(before)) reordered.put(request.node(), request.parent());
            reordered.put(node, parent);
        });
        if (!reordered.containsKey(request.node())) reordered.put(request.node(), request.parent());
        parents = reordered;
        if (docs.stream().anyMatch(doc -> doc.getArchivedAt() != null
                && (("d:" + doc.getId()).equals(request.node()) || ("d:" + doc.getId()).equals(request.parent())))) {
            throw new ConflictException("文档已归档，请刷新目录后重试");
        }
        for (DocEntity doc : docs) {
            String folder = KnowledgeDirectory.folderOf("d:" + doc.getId(), parents);
            if (!folder.equals(doc.getFolderId())) {
                if (docMapper.moveDirectoryFolder(kbId, doc.getId(), folder) != 1) {
                    throw new ConflictException("目录已变化，请刷新后重试");
                }
            }
        }
        Map<String, String> snapshot = new java.util.LinkedHashMap<>(parents);
        // MySQL JSON 对象会重排键；顺序须作为值单独保存，不能依赖对象键的序列化顺序。
        snapshot.put("__order", String.join(",", parents.keySet()));
        String directory = write(snapshot);
        if (kbMapper.updateDirectory(kbId, directory) != 1) throw new ConflictException("知识库已变化，请刷新后重试");
        kb.setDirectoryJson(directory);
        return getKb(kbId);
    }

    private void requireNoNestedChildren(String kbId, String node) {
        KbEntity kb = requireKb(kbId);
        Map<String, String> parents = directoryParents(kb,
                folderMapper.selectList(aliveFolders().eq(KbFolderEntity::getKbId, kbId)),
                docMapper.selectList(aliveDocs().eq(DocEntity::getKbId, kbId)));
        if (parents.containsValue(node)) throw new BadRequestException("请先移出子分组或子文档，再删除或归档当前节点");
    }

    private KbDto toDto(KbEntity kb, List<KbFolderEntity> folders, Map<String, List<String>> docIdsByFolder) {
        List<FolderDto> folderDtos = folders.stream()
                .map(f -> new FolderDto(f.getId(), f.getName(), docIdsByFolder.getOrDefault(f.getId(), List.of())))
                .toList();
        return new KbDto(kb.getId(), kb.getName(), kb.getIcon(), kb.getColor(), null,
                kb.getAuto(), kb.getDescription(), folderDtos, kb.getGroupId(), kb.getPerm(),
                Boolean.TRUE.equals(kb.getNotes()), directoryOverrides(kb), directoryOrder(kb));
    }

    private DocDto toDto(DocEntity e) {
        List<BacklinkDto> backlinks = e.getDeletedAt() != null ? List.of()
                : docMapper.selectBacklinkSources(e.getId()).stream()
                .map(source -> new BacklinkDto("doc", source.getId(), snippetOf(source))).toList();
        return toDtoWithBacklinks(e, backlinks);
    }

    /**
     * 单次解析每篇文档的出链，建立目标文档到来源文档的反向索引。
     *
     * <p>旧实现为每一篇目标文档重新扫描并解析全库出链，文档列表会退化成 O(n²)。索引构建后，
     * 列表路径只解析 n 次出链；单篇文档由 SQL 筛选引用来源，沿用相同摘要口径。
     */
    private Map<String, List<BacklinkDto>> backlinkIndex(List<DocEntity> docs) {
        return backlinkIndex(docs,
                source -> readList(source.getLinksJson(), LINKS_TYPE), this::snippetOf);
    }

    private Map<String, List<BacklinkDto>> backlinkIndex(List<DocEntity> docs,
            java.util.function.Function<DocEntity, List<DocLinkDto>> linksOf,
            java.util.function.Function<DocEntity, String> snippetOf) {
        Map<String, List<BacklinkDto>> backlinks = new java.util.LinkedHashMap<>();
        Set<String> aliveIds = docs.stream().map(DocEntity::getId).collect(Collectors.toSet());
        for (DocEntity source : docs) {
            List<DocLinkDto> links = linksOf.apply(source);
            if (links.isEmpty()) continue;
            BacklinkDto backlink = new BacklinkDto("doc", source.getId(), snippetOf.apply(source));
            links.stream()
                    .filter(link -> "doc".equals(link.type()))
                    .map(DocLinkDto::id)
                    .filter(targetId -> aliveIds.contains(targetId) && !source.getId().equals(targetId))
                    .distinct()
                    .forEach(targetId -> backlinks.computeIfAbsent(targetId, ignored -> new ArrayList<>())
                            .add(backlink));
        }
        return backlinks;
    }

    private DocDto toDtoWithBacklinks(DocEntity e, List<BacklinkDto> backlinks) {
        List<DocBlockDto> blocks = readList(e.getBlocksJson(), BLOCKS_TYPE);
        List<DocLinkDto> links = readList(e.getLinksJson(), LINKS_TYPE);
        return toDtoWithBacklinks(e, backlinks, links, blocks);
    }

    private DocDto toDtoWithBacklinks(DocEntity e, List<BacklinkDto> backlinks,
            List<DocLinkDto> links, List<DocBlockDto> blocks) {
        return new DocDto(e.getId(), e.getTitle(), e.getAuthorId(),
                stamp(e.getCreatedAt(), e.getCreatedLabel()), stamp(e.getUpdatedAt(), e.getUpdatedLabel()),
                e.getAuto(), withoutProjectLinks(links), blocks, richContentOf(e), backlinks, e.getKbId(), e.getFolderId(), rev(e),
                readList(e.getTagsJson(), new TypeReference<List<String>>() {}));
    }

    private static List<DocLinkDto> withoutProjectLinks(List<DocLinkDto> links) {
        return links.stream().filter(link -> !"project".equals(link.type())).toList();
    }

    /** 时间戳优先，老数据回落到升级前写死的展示文案。 */
    private static String stamp(LocalDateTime at, String legacyLabel) {
        return at != null ? iso(at) : legacyLabel;
    }

    private static String iso(LocalDateTime at) {
        return at == null ? null : at.atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static LocalDateTime expiry(LocalDateTime deletedAt) {
        return deletedAt == null ? null : deletedAt.plusDays(TRASH_DAYS);
    }

    private static int rev(DocEntity e) {
        return e.getRev() == null ? 1 : e.getRev();
    }

    /** 小记没填标题时，用正文首行当标题，太长则截断。 */
    private static String titleFrom(List<DocBlockDto> blocks) {
        for (DocBlockDto b : blocks) {
            String text = firstText(b);
            if (text != null && !text.isBlank()) {
                String t = text.trim();
                return t.length() > 30 ? t.substring(0, 30) + "…" : t;
            }
        }
        return "未命名小记";
    }

    private static String titleFrom(RichDocContentDto content, List<DocBlockDto> blocks) {
        if (content != null && content.markdown() != null) {
            String plain = markdownPlainText(content.markdown());
            if (!plain.isBlank()) return plain.length() > 30 ? plain.substring(0, 30) + "…" : plain;
        }
        return titleFrom(blocks);
    }

    private boolean sameRichContent(DocEntity entity, RichDocContentDto content) {
        if (content == null) {
            return entity.getContentJson() == null && entity.getContentMarkdown() == null
                    && entity.getContentVersion() == null;
        }
        String document = content.document() == null ? null : write(content.document());
        return Objects.equals(document, entity.getContentJson())
                && Objects.equals(content.markdown(), entity.getContentMarkdown())
                && Objects.equals(content.version(), entity.getContentVersion());
    }

    private static String firstText(DocBlockDto b) {
        if (b.h() != null) {
            return b.h();
        }
        if (b.p() != null) {
            return b.p();
        }
        if (b.call() != null) {
            return b.call();
        }
        if (b.ul() != null && !b.ul().isEmpty()) {
            return b.ul().get(0);
        }
        if (b.q() != null && !b.q().isEmpty()) {
            return b.q().get(0);
        }
        return null;
    }

    private String snippetOf(DocEntity e) {
        return snippetOf(e, readList(e.getBlocksJson(), BLOCKS_TYPE));
    }

    private String snippetOf(DocEntity e, List<DocBlockDto> blocks) {
        if (e.getContentMarkdown() != null && !e.getContentMarkdown().isBlank()) {
            String plain = markdownPlainText(e.getContentMarkdown());
            return plain.length() > 60 ? plain.substring(0, 60) + "…" : plain;
        }
        for (DocBlockDto b : blocks) {
            String text = b.p() != null ? b.p() : b.h() != null ? b.h() : b.call();
            if (text != null && !text.isBlank()) {
                return text.length() > 60 ? text.substring(0, 60) + "…" : text;
            }
        }
        return e.getTitle();
    }

    /** 高级正文请求的版本、规模、节点和危险 URL 白名单校验。 */
    private RichDocContentDto checkedRichContent(RichDocContentDto content) {
        if (content == null) return null;
        if (!Objects.equals(content.version(), RICH_CONTENT_VERSION)) {
            throw new BadRequestException("暂不支持该文档正文版本");
        }
        String markdown = content.markdown() == null ? "" : content.markdown();
        if (markdown.length() > MAX_RICH_CONTENT_LENGTH) {
            throw new BadRequestException("文档正文不能超过 1000000 个字符");
        }
        if (markdown.regionMatches(true, 0, "data:", 0, 5)
                || Pattern.compile("(?i)\\]\\(\\s*(?:javascript|data):").matcher(markdown).find()) {
            throw new BadRequestException("文档正文包含不安全的链接或内嵌数据");
        }
        String proseMarkdown = markdown.replaceAll("(?s)```.*?```", "").replaceAll("`[^`]*`", "");
        // Tiptap 表格的 Markdown 投影用无属性 <br> 表示单元格内换行。
        // 只从校验副本移除该安全形式，保留正文及 JSON；带属性或其他 HTML 仍拒绝。
        proseMarkdown = proseMarkdown.replaceAll("(?i)<br\\s*/?>", "");
        if (Pattern.compile("(?i)<\\s*/?\\s*[a-z][^>]*>").matcher(proseMarkdown).find()) {
            throw new BadRequestException("高级文档正文不接受原始 HTML");
        }
        JsonNode document = content.document();
        if (document != null && !document.isNull()) {
            String serialized = write(document);
            if (serialized.length() > MAX_RICH_CONTENT_LENGTH) {
                throw new BadRequestException("文档结构不能超过 1000000 个字符");
            }
            int[] count = {0};
            validateRichNode(document, count);
            if (!"doc".equals(document.path("type").asText())) {
                throw new BadRequestException("文档结构必须以 doc 节点开始");
            }
        }
        return new RichDocContentDto(RICH_CONTENT_VERSION,
                document == null || document.isNull() ? null : document, markdown);
    }

    private void validateRichNode(JsonNode node, int[] count) {
        if (!node.isObject() || ++count[0] > MAX_RICH_NODE_COUNT) {
            throw new BadRequestException("文档结构不合法或超过 5000 个节点");
        }
        String type = node.path("type").asText();
        if (!RICH_NODE_TYPES.contains(type)) {
            throw new BadRequestException("文档包含不支持的节点：" + type);
        }
        JsonNode marks = node.get("marks");
        if (marks != null) {
            if (!marks.isArray()) throw new BadRequestException("文档文字样式不合法");
            for (JsonNode mark : marks) {
                String markType = mark.path("type").asText();
                if (!RICH_MARK_TYPES.contains(markType)) {
                    throw new BadRequestException("文档包含不支持的文字样式：" + markType);
                }
                validateRichAttrs(mark.get("attrs"), null);
            }
        }
        validateRichAttrs(node.get("attrs"), type);
        JsonNode children = node.get("content");
        if (children != null) {
            if (!children.isArray()) throw new BadRequestException("文档子节点不合法");
            children.forEach(child -> validateRichNode(child, count));
        }
    }

    private void validateRichAttrs(JsonNode attrs, String nodeType) {
        if (attrs == null || attrs.isNull()) return;
        if (!attrs.isObject()) throw new BadRequestException("文档节点属性不合法");
        attrs.fields().forEachRemaining(entry -> {
            // 编辑器会为普通有序列表生成 type:null；粘贴列表还可能带 HTML 编号样式。
            if ("orderedList".equals(nodeType) && "type".equals(entry.getKey())) {
                JsonNode style = entry.getValue();
                if (!style.isNull() && (!style.isTextual()
                        || !Set.of("1", "a", "A", "i", "I").contains(style.asText()))) {
                    throw new BadRequestException("有序列表编号样式不受支持");
                }
                return;
            }
            if ("image".equals(nodeType) && "indent".equals(entry.getKey())) {
                JsonNode indent = entry.getValue();
                if (!indent.isIntegralNumber() || !indent.canConvertToInt()
                        || indent.intValue() < 0 || indent.intValue() > 8) {
                    throw new BadRequestException("图片缩进必须为 0–8 级整数");
                }
                return;
            }
            if (!RICH_ATTRS.contains(entry.getKey())) {
                throw new BadRequestException("文档包含不支持的节点属性：" + entry.getKey());
            }
            JsonNode value = entry.getValue();
            if (value.isTextual() && value.asText().length() > 20_000) {
                throw new BadRequestException("文档节点属性过长");
            }
            if (("href".equals(entry.getKey()) || "src".equals(entry.getKey()) || "url".equals(entry.getKey())
                    || "snapshot".equals(entry.getKey()))
                    && value.isTextual() && !value.asText().isBlank() && !safeRichUrl(value.asText())) {
                throw new BadRequestException("文档包含不安全的资源地址");
            }
            if (("color".equals(entry.getKey()) || "backgroundColor".equals(entry.getKey()))
                    && value.isTextual() && !RICH_COLORS.contains(value.asText().toLowerCase())) {
                throw new BadRequestException("文档颜色不在允许的色板中");
            }
            if ("textAlign".equals(entry.getKey()) && value.isTextual()
                    && !Set.of("left", "center", "right").contains(value.asText())) {
                throw new BadRequestException("文档对齐方式不受支持");
            }
            if ("align".equals(entry.getKey()) && value.isTextual()
                    && !Set.of("left", "center", "right").contains(value.asText())) {
                throw new BadRequestException("图片对齐方式不受支持");
            }
            if ("kind".equals(entry.getKey()) && value.isTextual()
                    && !Set.of("info", "success", "warning", "danger").contains(value.asText())) {
                throw new BadRequestException("提示块类型不受支持");
            }
            if ("target".equals(entry.getKey()) && value.isTextual() && !"_blank".equals(value.asText())) {
                throw new BadRequestException("链接打开方式不受支持");
            }
            if ("rel".equals(entry.getKey()) && value.isTextual()
                    && !"noopener noreferrer".equals(value.asText())) {
                throw new BadRequestException("链接安全属性不受支持");
            }
        });
    }

    private static boolean safeRichUrl(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase();
        return value.startsWith("https://") || value.startsWith("http://") || value.startsWith("/")
                || value.startsWith("./") || value.startsWith("../") || value.startsWith("#")
                || value.startsWith("mailto:") || value.startsWith("tel:");
    }

    private void applyRichContent(DocEntity entity, RichDocContentDto supplied) {
        RichDocContentDto content = checkedRichContent(supplied);
        entity.setContentVersion(content == null ? null : content.version());
        entity.setContentJson(content == null || content.document() == null ? null : write(content.document()));
        entity.setContentMarkdown(content == null ? null : content.markdown());
    }

    private void applyRichContent(DocTemplateEntity entity, RichDocContentDto supplied) {
        RichDocContentDto content = checkedRichContent(supplied);
        entity.setContentVersion(content == null ? null : content.version());
        entity.setContentJson(content == null || content.document() == null ? null : write(content.document()));
        entity.setContentMarkdown(content == null ? null : content.markdown());
    }

    private RichDocContentDto richContentOf(DocEntity entity) {
        return richContent(entity.getContentVersion(), entity.getContentJson(), entity.getContentMarkdown());
    }

    private RichDocContentDto richContentOf(DocTemplateEntity entity) {
        return richContent(entity.getContentVersion(), entity.getContentJson(), entity.getContentMarkdown());
    }

    private RichDocContentDto richContent(Integer version, String document, String markdown) {
        if (version == null && document == null && markdown == null) return null;
        JsonNode parsed = null;
        if (document != null && !document.isBlank()) {
            try {
                parsed = json.readTree(document);
            } catch (JsonProcessingException ignored) {
                // 历史损坏的高级结构不阻断整篇文档，前端会回落 Markdown 或旧块。
            }
        }
        return new RichDocContentDto(version == null ? RICH_CONTENT_VERSION : version, parsed,
                markdown == null ? "" : markdown);
    }

    private static String markdownPlainText(String markdown) {
        return markdown
                .replaceAll("(?s)```.*?```", " ")
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", " ")
                .replaceAll("!\\[[^]]*]|[>#*_`~|]", " ")
                .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1")
                .replaceAll("\\s+", " ").trim();
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
