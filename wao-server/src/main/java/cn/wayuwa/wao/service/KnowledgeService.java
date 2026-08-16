package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.domain.KbEntity;
import cn.wayuwa.wao.domain.KbFolderEntity;
import cn.wayuwa.wao.domain.KbGroupEntity;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.dto.BacklinkDto;
import cn.wayuwa.wao.dto.CreateDocRequest;
import cn.wayuwa.wao.dto.CreateFolderRequest;
import cn.wayuwa.wao.dto.CreateKbGroupRequest;
import cn.wayuwa.wao.dto.CreateKbRequest;
import cn.wayuwa.wao.dto.CreateNoteRequest;
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
import cn.wayuwa.wao.dto.TrashEntryDto;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.mapper.KbFolderMapper;
import cn.wayuwa.wao.mapper.KbGroupMapper;
import cn.wayuwa.wao.mapper.KbMapper;
import cn.wayuwa.wao.mapper.MemberMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private static final String NOTES_FOLDER_ID = "kb-notes-f";
    private static final Pattern CLIENT_DOC_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private static final TypeReference<List<DocBlockDto>> BLOCKS_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<DocLinkDto>> LINKS_TYPE = new TypeReference<>() { };

    private final KbGroupMapper groupMapper;
    private final KbMapper kbMapper;
    private final KbFolderMapper folderMapper;
    private final DocMapper docMapper;
    private final MemberMapper memberMapper;
    private final ObjectMapper json;
    private final FeishuTaskNotifier taskNotifier;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(KnowledgeService.class);

    public KnowledgeService(KbGroupMapper groupMapper, KbMapper kbMapper, KbFolderMapper folderMapper,
            DocMapper docMapper, MemberMapper memberMapper, ObjectMapper json, FeishuTaskNotifier taskNotifier) {
        this.groupMapper = groupMapper;
        this.kbMapper = kbMapper;
        this.folderMapper = folderMapper;
        this.docMapper = docMapper;
        this.memberMapper = memberMapper;
        this.json = json;
        this.taskNotifier = taskNotifier;
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
        List<DocEntity> docs = docMapper.selectList(aliveDocs().orderByAsc(DocEntity::getOrd));

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
        List<DocEntity> docs = docMapper.selectList(aliveDocs().eq(DocEntity::getKbId, id).orderByAsc(DocEntity::getOrd));
        return toDto(kb, folders, docIdsByFolder(docs));
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

    /** 局部更新知识库（项目 / 自动化知识库的名称与简介不可改）。 */
    public KbDto updateKb(String id, PatchKbRequest req) {
        KbEntity e = requireKb(id);
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
        requireKb(kbId);
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
        List<String> docIds = docMapper.selectList(aliveDocs()
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
        KbFolderEntity folder = requireFolder(kbId, folderId);
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

    /** 列出全部文档（含正文与反向链接），不含回收站里的。 */
    public List<DocDto> listDocs() {
        List<DocEntity> docs = docMapper.selectList(aliveDocs());
        return docs.stream().map(d -> toDto(d, docs)).toList();
    }

    /** 按 id 获取单篇文档。 */
    public DocDto getDoc(String id) {
        DocEntity e = requireDoc(id);
        return toDto(e, docMapper.selectList(aliveDocs()));
    }

    /**
     * 新建文档：必须指定所属知识库与文件夹，实现「新建文档即关联到知识库」。
     *
     * @param req    新建请求
     * @param author 作者 member id，由服务端从当前会话推导（不接受请求体指定）
     */
    public DocDto createDoc(CreateDocRequest req, String author) {
        requireKb(req.kbId());
        KbFolderEntity folder = requireFolder(req.kbId(), req.folderId());
        return insertDoc(req.kbId(), folder.getId(), req.title(), author, List.of());
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
        String resolvedAuthor = blankOr(author, "wg");
        DocEntity existing = docMapper.selectById(id);
        if (existing != null) {
            if (existing.getDeletedAt() != null || !NOTES_KB_ID.equals(existing.getKbId())
                    || !Objects.equals(resolvedAuthor, existing.getAuthorId())) {
                throw new BadRequestException("小记文档 id 已被占用");
            }
            List<DocBlockDto> blocks = req.blocks() == null ? List.of() : req.blocks();
            String title = blankOr(req.title(), titleFrom(blocks));
            String blocksJson = write(blocks);
            boolean changed = !Objects.equals(title, existing.getTitle())
                    || !Objects.equals(blocksJson, existing.getBlocksJson());
            if (changed) {
                existing.setTitle(title);
                existing.setBlocksJson(blocksJson);
                existing.setRev(rev(existing) + 1);
                existing.setUpdatedAt(LocalDateTime.now());
                docMapper.updateById(existing);
            }
            return toDto(existing, docMapper.selectList(aliveDocs()));
        }
        KbEntity kb = notesKb();
        KbFolderEntity folder = authorFolder(kb.getId(), author);
        List<DocBlockDto> blocks = req.blocks() == null ? List.of() : req.blocks();
        DocDto doc = insertDoc(id, kb.getId(), folder.getId(), blankOr(req.title(), titleFrom(blocks)), author, blocks);
        announceNote(kb.getId(), doc, author);
        return doc;
    }

    /** 新建小记后同步到飞书群；通知失败只记日志，不影响创建结果。 */
    private void announceNote(String kbId, DocDto doc, String author) {
        try {
            MemberEntity member = author == null || author.isBlank() ? null : memberMapper.selectById(author);
            taskNotifier.notifyNoteCreated(kbId, doc.id(), doc.title(),
                    member == null ? author : member.getName());
        } catch (RuntimeException ex) {
            LOG.warn("新建小记的飞书通知发送失败：{}", ex.getMessage());
        }
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
        return insertDoc("d" + System.currentTimeMillis(), kbId, folderId, title, author, blocks);
    }

    private DocDto insertDoc(String id, String kbId, String folderId, String title, String author,
            List<DocBlockDto> blocks) {
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
        e.setLinksJson(write(List.of()));
        e.setOrd(nextOrd(count));
        docMapper.insert(e);
        return toDto(e, docMapper.selectList(aliveDocs()));
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
        requireKb(kbId);
        KbFolderEntity folder = requireFolder(kbId, folderId);
        DocEntity existing = docMapper.selectList(aliveDocs()
                        .eq(DocEntity::getFolderId, folder.getId()).eq(DocEntity::getTitle, title))
                .stream().findFirst().orElse(null);
        if (existing == null) {
            DocDto created = insertDoc(kbId, folder.getId(), title, "auto", blocks);
            DocEntity e = requireDoc(created.id());
            e.setAuto(true);
            docMapper.updateById(e);
            return toDto(e, docMapper.selectList(aliveDocs()));
        }
        existing.setBlocksJson(write(blocks));
        existing.setAuto(true);
        existing.setRev(rev(existing) + 1);
        existing.setUpdatedAt(LocalDateTime.now());
        docMapper.updateById(existing);
        return toDto(existing, docMapper.selectList(aliveDocs()));
    }

    /** 局部更新文档：标题 / 正文块 / 出链 / 所在文件夹。正文或标题变化时版本号 +1。 */
    public DocDto updateDoc(String id, PatchDocRequest req) {
        DocEntity e = requireDoc(id);
        boolean contentChanged = false;
        if (req.title() != null && !req.title().equals(e.getTitle())) {
            e.setTitle(req.title());
            contentChanged = true;
        }
        if (req.blocks() != null) {
            String next = write(req.blocks());
            contentChanged |= !next.equals(e.getBlocksJson());
            e.setBlocksJson(next);
        }
        if (req.links() != null) {
            e.setLinksJson(write(req.links()));
        }
        if (req.folderId() != null && !req.folderId().isBlank()) {
            requireFolder(e.getKbId(), req.folderId());
            e.setFolderId(req.folderId());
        }
        if (contentChanged) {
            e.setRev(rev(e) + 1);
            e.setUpdatedAt(LocalDateTime.now());
        }
        docMapper.updateById(e);
        return toDto(e, docMapper.selectList(aliveDocs()));
    }

    /**
     * 把文档移入回收站。
     *
     * @param id       文档 id
     * @param memberId 操作人 member id
     */
    public void deleteDoc(String id, String memberId) {
        DocEntity e = requireDoc(id);
        markDocDeleted(e, LocalDateTime.now(), memberId, false);
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
        docMapper.updateById(d);
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
        kbMapper.updateById(kb);
        for (KbFolderEntity f : folderMapper.selectList(new LambdaQueryWrapper<KbFolderEntity>()
                .eq(KbFolderEntity::getKbId, id).eq(KbFolderEntity::getCascadeDel, true))) {
            f.setDeletedAt(null);
            f.setCascadeDel(false);
            folderMapper.updateById(f);
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
        folderMapper.updateById(folder);
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
            folderMapper.updateById(folder);
        }
        reviveKbOf(doc.getKbId());
    }

    private void reviveKbOf(String kbId) {
        KbEntity kb = kbMapper.selectById(kbId);
        if (kb != null && kb.getDeletedAt() != null) {
            kb.setDeletedAt(null);
            kb.setDeletedBy(null);
            kbMapper.updateById(kb);
        }
    }

    private void clearDocDeleted(DocEntity d) {
        d.setDeletedAt(null);
        d.setDeletedBy(null);
        d.setCascadeDel(false);
        docMapper.updateById(d);
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

    private Map<String, List<String>> docIdsByFolder(List<DocEntity> docs) {
        return docs.stream().collect(Collectors.groupingBy(DocEntity::getFolderId,
                Collectors.mapping(DocEntity::getId, Collectors.toList())));
    }

    private KbDto toDto(KbEntity kb, List<KbFolderEntity> folders, Map<String, List<String>> docIdsByFolder) {
        List<FolderDto> folderDtos = folders.stream()
                .map(f -> new FolderDto(f.getId(), f.getName(), docIdsByFolder.getOrDefault(f.getId(), List.of())))
                .toList();
        return new KbDto(kb.getId(), kb.getName(), kb.getIcon(), kb.getColor(), kb.getProjectId(),
                kb.getAuto(), kb.getDescription(), folderDtos, kb.getGroupId(), kb.getPerm(),
                Boolean.TRUE.equals(kb.getNotes()));
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
        return new DocDto(e.getId(), e.getTitle(), e.getAuthorId(),
                stamp(e.getCreatedAt(), e.getCreatedLabel()), stamp(e.getUpdatedAt(), e.getUpdatedLabel()),
                e.getAuto(), links, blocks, backlinks, e.getKbId(), e.getFolderId(), rev(e));
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
