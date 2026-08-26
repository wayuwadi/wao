package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.domain.SkillEntity;
import cn.wayuwa.wao.domain.SkillMarketRow;
import cn.wayuwa.wao.domain.SkillPublisherEntity;
import cn.wayuwa.wao.domain.SkillVersionEntity;
import cn.wayuwa.wao.dto.SkillDtos;
import cn.wayuwa.wao.dto.SkillRequests;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.mapper.SkillFavoriteMapper;
import cn.wayuwa.wao.mapper.SkillMapper;
import cn.wayuwa.wao.mapper.SkillPublisherMapper;
import cn.wayuwa.wao.mapper.SkillVersionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** WAO Skill 市场的发布、审核、公开浏览与下载服务。 */
@Service
public class SkillMarketService {
    public static final List<SkillDtos.Category> CATEGORIES = List.of(
            new SkillDtos.Category("office-efficiency", "办公效率"), new SkillDtos.Category("content-creation", "内容创作"),
            new SkillDtos.Category("dev-programming", "开发编程"), new SkillDtos.Category("data-analysis", "数据分析"),
            new SkillDtos.Category("design-media", "设计多媒体"), new SkillDtos.Category("agent-automation", "Agent 自动化"),
            new SkillDtos.Category("knowledge-management", "知识管理"), new SkillDtos.Category("business-ops", "商业运营"),
            new SkillDtos.Category("education", "教育学习"), new SkillDtos.Category("professional", "行业专业"),
            new SkillDtos.Category("it-ops-security", "运维安全"), new SkillDtos.Category("life-service", "生活服务"));
    private static final Set<String> CATEGORY_KEYS = CATEGORIES.stream().map(SkillDtos.Category::key).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> LICENSES = Set.of("MIT", "Apache-2.0", "GPL-3.0", "CC-BY-4.0", "ALL-RIGHTS-RESERVED");
    private static final Set<String> PLATFORMS = Set.of("agent-skills", "codex", "claude-code", "openclaw");
    private static final Pattern HANDLE = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?$");
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$");
    private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?$");

    private final SkillPublisherMapper publishers; private final SkillMapper skills; private final SkillVersionMapper versions;
    private final SkillFavoriteMapper favorites; private final MemberMapper members; private final SkillPackageInspector inspector;
    private final SkillRateLimiter limiter; private final ObjectMapper json;

    public SkillMarketService(SkillPublisherMapper publishers, SkillMapper skills, SkillVersionMapper versions,
            SkillFavoriteMapper favorites, MemberMapper members, SkillPackageInspector inspector,
            SkillRateLimiter limiter, ObjectMapper json) {
        this.publishers=publishers; this.skills=skills; this.versions=versions; this.favorites=favorites;
        this.members=members; this.inspector=inspector; this.limiter=limiter; this.json=json;
    }

    /** 列出一期固定分类。 */
    public List<SkillDtos.Category> categories() { return CATEGORIES; }

    /** 读取当前成员的公开发布者身份。 */
    public SkillDtos.PublisherProfile myPublisher(String memberId) {
        SkillPublisherEntity item = publishers.findByMember(requiredMember(memberId));
        return item == null ? null : new SkillDtos.PublisherProfile(item.getHandle(), item.getDisplayName());
    }

    /** 首次创建 handle，之后只允许修改公开昵称。 */
    @Transactional
    public SkillDtos.PublisherProfile savePublisher(String memberId, SkillRequests.PublisherProfile request) {
        memberId = requiredMember(memberId);
        String handle = text(request == null ? null : request.handle()).toLowerCase();
        String displayName = text(request == null ? null : request.displayName());
        if (!HANDLE.matcher(handle).matches()) throw new BadRequestException("handle 需为 1-32 位小写字母、数字或连字符");
        requireLength(displayName, "发布者昵称", 2, 64);
        SkillPublisherEntity item = publishers.findByMember(memberId);
        LocalDateTime now = LocalDateTime.now();
        if (item == null) {
            item = new SkillPublisherEntity(); item.setId("sp-" + UUID.randomUUID()); item.setMemberId(memberId);
            item.setHandle(handle); item.setDisplayName(displayName); item.setCreatedAt(now); item.setUpdatedAt(now);
            try { publishers.insert(item); } catch (DuplicateKeyException ex) { throw new ConflictException("handle 已被使用"); }
        } else {
            if (!item.getHandle().equals(handle)) throw new BadRequestException("handle 创建后不能修改");
            item.setDisplayName(displayName); item.setUpdatedAt(now); publishers.updateById(item);
        }
        return new SkillDtos.PublisherProfile(item.getHandle(), item.getDisplayName());
    }

    /** 上传 ZIP 并建立不可变草稿版本。 */
    @Transactional
    public SkillDtos.MySkill createZipDraft(String memberId, SkillRequests.Draft request, MultipartFile archive) {
        try {
            return createDraft(memberId, request, inspector.inspectZip(archive == null ? null : archive.getBytes()));
        } catch (java.io.IOException ex) {
            throw new BadRequestException("读取 Skill ZIP 失败，请重试");
        }
    }

    /** 上传浏览器文件夹并建立不可变草稿版本。 */
    @Transactional
    public SkillDtos.MySkill createFolderDraft(String memberId, SkillRequests.Draft request,
            List<MultipartFile> files, List<String> paths) {
        return createDraft(memberId, request, inspector.inspectFiles(files, paths));
    }

    /**
     * 把成功 AI Run 产出的候选包收进发起人的市场草稿，不自动确认授权或提交审核。
     * 同名 Skill 会递增补丁版本，使方法可以迭代但不会覆盖已发布快照。
     */
    @Transactional
    public SkillDtos.MySkill createAiDraft(String memberId, MultipartFile archive, String runId) {
        try {
            SkillPackageInspector.Inspection packageInfo = inspector.inspectZip(archive == null ? null : archive.getBytes());
            String slug = aiSlug(packageInfo.name(), packageInfo.sha256());
            SkillEntity existing = skills.findOwnedBySlug(requiredMember(memberId), slug);
            String version = existing == null ? "0.1.0" : nextPatchVersion(existing.getId());
            SkillRequests.Draft request = new SkillRequests.Draft(existing == null ? null : existing.getId(), slug,
                    limitText(packageInfo.name(), 100), limitText(packageInfo.description(), 500),
                    "agent-automation", List.of("ai-run", "candidate"), version,
                    "由 AI Run " + limitText(text(runId), 64) + " 根据已验收工作生成，等待作者复核适用范围与授权。",
                    "ALL-RIGHTS-RESERVED", List.of("codex"), false);
            return createDraft(memberId, request, packageInfo);
        } catch (java.io.IOException ex) {
            throw new BadRequestException("读取 AI Skill 候选失败，请重试");
        }
    }

    private SkillDtos.MySkill createDraft(String memberId, SkillRequests.Draft request,
            SkillPackageInspector.Inspection packageInfo) {
        memberId = requiredMember(memberId); validateDraft(request);
        SkillPublisherEntity publisher = publishers.findByMember(memberId);
        if (publisher == null) throw new BadRequestException("请先设置公开发布者昵称和 handle");
        if (versions.countOpenByMember(memberId) >= 20) throw new BadRequestException("草稿和待审版本已达 20 个，请先处理已有内容");
        limiter.checkUpload(memberId);
        LocalDateTime now = LocalDateTime.now();
        SkillEntity skill;
        if (text(request.skillId()).isBlank()) {
            skill = new SkillEntity(); skill.setId("skill-" + UUID.randomUUID()); skill.setPublisherId(publisher.getId());
            skill.setSlug(text(request.slug()).toLowerCase()); skill.setDisplayName(text(request.displayName()));
            skill.setSummary(text(request.summary())); skill.setCategory(request.category()); skill.setTagsJson(write(cleanTags(request.tags())));
            skill.setStatus("DRAFT"); skill.setDownloadCount(0L); skill.setFavoriteCount(0L); skill.setCreatedAt(now); skill.setUpdatedAt(now);
            try { skills.insert(skill); } catch (DuplicateKeyException ex) { throw new ConflictException("当前发布者已有同名 slug"); }
        } else {
            skill = owned(request.skillId(), memberId);
        }
        SkillVersionEntity version = new SkillVersionEntity();
        version.setId("sv-" + UUID.randomUUID()); version.setSkillId(skill.getId()); version.setVersion(text(request.version()));
        applyDraft(version, request.displayName(), request.summary(), request.category(), request.tags(), request.changelog(),
                request.license(), request.testedPlatforms(), request.rightsConfirmed());
        version.setCompatiblePlatformsJson(write(packageInfo.compatiblePlatforms())); version.setState("DRAFT");
        version.setPackageName(skill.getSlug() + "-" + version.getVersion() + ".zip");
        version.setPackageContent(packageInfo.packageContent()); version.setPackageSize((long) packageInfo.packageContent().length);
        version.setUnpackedSize(packageInfo.unpackedSize()); version.setFileCount(packageInfo.files().size());
        version.setSha256(packageInfo.sha256()); version.setFileManifestJson(write(packageInfo.files()));
        version.setScanFindingsJson(write(packageInfo.findings())); version.setSkillMd(packageInfo.skillMd());
        version.setCreatedAt(now); version.setUpdatedAt(now);
        try { versions.insert(version); } catch (DuplicateKeyException ex) { throw new ConflictException("版本 " + version.getVersion() + " 已存在"); }
        skill.setUpdatedAt(now); skills.updateById(skill);
        return mySkill(skill, memberId);
    }

    /** 草稿元数据自动保存，不允许修改已提交快照。 */
    @Transactional
    public SkillDtos.Version patchDraft(String memberId, String skillId, String versionId, SkillRequests.DraftPatch request) {
        SkillEntity skill = owned(skillId, requiredMember(memberId));
        SkillVersionEntity version = versionOwned(versionId, skill);
        if (!"DRAFT".equals(version.getState())) throw new BadRequestException("只能修改草稿版本");
        applyDraft(version, request.displayName(), request.summary(), request.category(), request.tags(), request.changelog(),
                request.license(), request.testedPlatforms(), request.rightsConfirmed());
        version.setUpdatedAt(LocalDateTime.now()); versions.updateById(version);
        return toVersion(version);
    }

    /** 草稿通过完整性检查后进入管理员队列。 */
    @Transactional
    public SkillDtos.Version submit(String memberId, String skillId, String versionId) {
        SkillEntity skill = owned(skillId, requiredMember(memberId));
        SkillVersionEntity version = versionOwned(versionId, skill);
        if (!"DRAFT".equals(version.getState())) throw new BadRequestException("只能提交草稿版本");
        if (!Boolean.TRUE.equals(version.getRightsConfirmed())) throw new BadRequestException("请确认拥有公开发布权");
        List<String> findings = readStrings(version.getScanFindingsJson());
        if (!findings.isEmpty()) throw new BadRequestException("包内存在疑似凭据，请清理后上传新版本：" + findings.get(0));
        LocalDateTime now = LocalDateTime.now(); version.setState("PENDING_REVIEW"); version.setSubmittedAt(now); version.setUpdatedAt(now);
        versions.updateById(version);
        if (skill.getPublishedVersionId() == null) { skill.setStatus("PENDING_REVIEW"); skill.setUpdatedAt(now); skills.updateById(skill); }
        return toVersion(version);
    }

    /** 列出当前作者的 Skill 及全部版本状态。 */
    public List<SkillDtos.MySkill> mine(String memberId) {
        memberId = requiredMember(memberId);
        final String owner = memberId;
        return skills.findMine(owner).stream().map(item -> mySkill(item, owner)).toList();
    }

    /** 删除未提交草稿；空 Skill 容器同时清理。 */
    @Transactional
    public void deleteDraft(String memberId, String skillId, String versionId) {
        SkillEntity skill = owned(skillId, requiredMember(memberId));
        SkillVersionEntity version = versionOwned(versionId, skill);
        if (!"DRAFT".equals(version.getState())) throw new BadRequestException("只能删除未提交草稿");
        versions.deleteById(versionId);
        if (skill.getPublishedVersionId() == null && versions.listMeta(skillId).isEmpty()) skills.deleteById(skillId);
    }

    /** 作者上下架已审核的当前版本。 */
    @Transactional
    public void setListed(String memberId, String skillId, boolean listed) {
        SkillEntity skill = owned(skillId, requiredMember(memberId));
        if (skill.getPublishedVersionId() == null) throw new BadRequestException("这个 Skill 尚无已通过版本");
        skill.setStatus(listed ? "PUBLISHED" : "OFFLINE"); skill.setUpdatedAt(LocalDateTime.now()); skills.updateById(skill);
    }

    /** 公开市场分页。 */
    public SkillDtos.Page market(String keyword, String category, String platform, String sort, int page, int pageSize) {
        page = Math.max(1, page); pageSize = Math.max(1, Math.min(60, pageSize));
        if (!text(category).isBlank() && !CATEGORY_KEYS.contains(category)) throw new BadRequestException("未知 Skill 分类");
        if (!text(platform).isBlank() && !PLATFORMS.contains(platform)) throw new BadRequestException("未知兼容平台");
        if (!Set.of("newest", "downloads", "favorites").contains(sort)) sort = "newest";
        String key = limitText(text(keyword), 100);
        List<SkillDtos.Card> items = skills.market(key, text(category), text(platform), sort, pageSize, (page - 1) * pageSize)
                .stream().map(this::toCard).toList();
        return new SkillDtos.Page(items, skills.countMarket(key, text(category), text(platform)), page, pageSize);
    }

    /** 公开详情；只投影已审核版本。 */
    public SkillDtos.Detail detail(String handle, String slug, String memberId) {
        SkillMarketRow row = skills.findPublic(handle, slug);
        if (row == null) throw new NotFoundException("Skill 不存在或已下架");
        SkillVersionEntity published = versions.findMeta(row.getVersionId());
        List<SkillDtos.Version> history = versions.listMeta(row.getId()).stream().filter(v -> "PUBLISHED".equals(v.getState()))
                .map(this::toVersion).toList();
        boolean favorite = memberId != null && favorites.exists(row.getId(), memberId) > 0;
        return new SkillDtos.Detail(toCard(row), toVersion(published), history, favorite);
    }

    /** 预览一个已审核版本中的单个文件；包内容始终只读且不会执行。 */
    public SkillDtos.FilePreview filePreview(String handle, String slug, String requestedVersion,
            String path, String clientKey) {
        SkillMarketRow row = skills.findPublic(handle, slug);
        if (row == null) throw new NotFoundException("Skill 不存在或已下架");
        SkillVersionEntity selected = publicVersion(row, requestedVersion);
        limiter.checkPreview(clientKey, row.getId());
        SkillVersionEntity stored = versions.selectById(selected.getId());
        byte[] content = stored == null ? null : stored.getPackageContent();
        if (content == null) throw new NotFoundException("Skill 包不存在");
        SkillPackageInspector.FilePreview preview = inspector.previewFile(content, path);
        return new SkillDtos.FilePreview(selected.getVersion(), preview.path(), preview.size(), preview.previewable(),
                preview.content(), preview.message());
    }

    /** 收藏或取消收藏公开 Skill。 */
    @Transactional
    public void favorite(String memberId, String skillId, boolean enabled) {
        memberId = requiredMember(memberId);
        SkillEntity skill = skills.selectById(skillId);
        if (skill == null || !"PUBLISHED".equals(skill.getStatus())) throw new NotFoundException("Skill 不存在或已下架");
        int changed = enabled ? favorites.add(skillId, memberId) : favorites.remove(skillId, memberId);
        if (changed > 0) skills.changeFavorites(skillId, enabled ? 1 : -1);
    }

    /** 返回审核通过的不可变 ZIP，并原子累计下载。 */
    @Transactional
    public Download download(String handle, String slug, String requestedVersion, String clientKey) {
        SkillMarketRow row = skills.findPublic(handle, slug);
        if (row == null) throw new NotFoundException("Skill 不存在或已下架");
        SkillVersionEntity selected = publicVersion(row, requestedVersion);
        limiter.checkDownload(clientKey, row.getId());
        SkillVersionEntity stored = versions.selectById(selected.getId());
        byte[] content = stored == null ? null : stored.getPackageContent();
        if (content == null) throw new NotFoundException("Skill 包不存在");
        skills.incrementDownloads(row.getId());
        return new Download(selected.getPackageName(), selected.getSha256(), content);
    }

    private SkillVersionEntity publicVersion(SkillMarketRow row, String requestedVersion) {
        return versions.listMeta(row.getId()).stream()
                .filter(v -> "PUBLISHED".equals(v.getState()))
                .filter(v -> text(requestedVersion).isBlank() ? v.getId().equals(row.getVersionId())
                        : v.getVersion().equals(requestedVersion))
                .findFirst().orElseThrow(() -> new NotFoundException("Skill 版本不存在或未通过审核"));
    }

    /** 管理员待审队列。 */
    public List<SkillDtos.ReviewItem> reviews(String memberId) {
        requireAdmin(memberId);
        return versions.reviewQueue().stream().map(version -> {
            SkillEntity skill = skills.selectById(version.getSkillId());
            SkillPublisherEntity publisher = publishers.selectById(skill.getPublisherId());
            return new SkillDtos.ReviewItem(mySkill(skill, publisher.getMemberId()), toVersion(version),
                    new SkillDtos.Publisher(publisher.getHandle(), publisher.getDisplayName()));
        }).toList();
    }

    /** 通过审核并原子切换对外版本指针。 */
    @Transactional
    public SkillDtos.Version approve(String memberId, String versionId) {
        requireAdmin(memberId);
        SkillVersionEntity version = pending(versionId); SkillEntity skill = requiredSkill(version.getSkillId());
        LocalDateTime now = LocalDateTime.now(); version.setState("PUBLISHED"); version.setReviewedAt(now);
        version.setReviewerId(memberId); version.setReviewNote(null); version.setUpdatedAt(now); versions.updateById(version);
        skill.setDisplayName(version.getDisplayName()); skill.setSummary(version.getSummary()); skill.setCategory(version.getCategory());
        skill.setTagsJson(version.getTagsJson()); skill.setPublishedVersionId(version.getId()); skill.setStatus("PUBLISHED"); skill.setUpdatedAt(now);
        skills.updateById(skill); return toVersion(version);
    }

    /** 驳回待审版本，必须留下可操作原因。 */
    @Transactional
    public SkillDtos.Version reject(String memberId, String versionId, String note) {
        requireAdmin(memberId); requireLength(text(note), "驳回原因", 2, 1000);
        SkillVersionEntity version = pending(versionId); SkillEntity skill = requiredSkill(version.getSkillId());
        LocalDateTime now = LocalDateTime.now(); version.setState("REJECTED"); version.setReviewedAt(now);
        version.setReviewerId(memberId); version.setReviewNote(text(note)); version.setUpdatedAt(now); versions.updateById(version);
        if (skill.getPublishedVersionId() == null) { skill.setStatus("REJECTED"); skill.setUpdatedAt(now); skills.updateById(skill); }
        return toVersion(version);
    }

    private void validateDraft(SkillRequests.Draft request) {
        if (request == null) throw new BadRequestException("Skill 草稿信息不能为空");
        if (!SLUG.matcher(text(request.slug()).toLowerCase()).matches()) throw new BadRequestException("slug 需为 1-64 位小写字母、数字或连字符");
        if (!SEMVER.matcher(text(request.version())).matches()) throw new BadRequestException("版本号必须符合语义化版本，如 1.0.0");
        validateMetadata(request.displayName(), request.summary(), request.category(), request.tags(), request.changelog(), request.license(), request.testedPlatforms());
    }

    private String nextPatchVersion(String skillId) {
        int[] latest = versions.listMeta(skillId).stream().map(SkillVersionEntity::getVersion)
                .map(value -> value == null ? new int[]{0, 0, 0} : java.util.Arrays.stream(value.split("[.-]", 4))
                        .limit(3).mapToInt(part -> part.matches("\\d+") ? Integer.parseInt(part) : 0).toArray())
                .filter(parts -> parts.length == 3)
                .max((left, right) -> left[0] != right[0] ? Integer.compare(left[0], right[0])
                        : left[1] != right[1] ? Integer.compare(left[1], right[1]) : Integer.compare(left[2], right[2]))
                .orElse(new int[]{0, 0, 0});
        return latest[0] + "." + latest[1] + "." + (latest[2] + 1);
    }

    private String aiSlug(String name, String sha256) {
        String value = text(name).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (value.isBlank()) value = "ai-skill-" + sha256.substring(0, 12);
        return value.length() <= 64 ? value : value.substring(0, 64).replaceAll("-+$", "");
    }

    private void applyDraft(SkillVersionEntity version, String displayName, String summary, String category,
            List<String> tags, String changelog, String license, List<String> testedPlatforms, boolean rightsConfirmed) {
        validateMetadata(displayName, summary, category, tags, changelog, license, testedPlatforms);
        version.setDisplayName(text(displayName)); version.setSummary(text(summary)); version.setCategory(category);
        version.setTagsJson(write(cleanTags(tags))); version.setChangelog(limitText(text(changelog), 1000));
        version.setLicenseCode(license); version.setTestedPlatformsJson(write(cleanPlatforms(testedPlatforms)));
        version.setRightsConfirmed(rightsConfirmed);
    }

    private void validateMetadata(String displayName, String summary, String category, List<String> tags,
            String changelog, String license, List<String> testedPlatforms) {
        requireLength(text(displayName), "Skill 名称", 2, 128); requireLength(text(summary), "Skill 简介", 10, 1000);
        if (!CATEGORY_KEYS.contains(category)) throw new BadRequestException("请选择有效分类");
        if (!LICENSES.contains(license)) throw new BadRequestException("请选择有效授权方式");
        if (text(changelog).length() > 1000) throw new BadRequestException("版本说明不能超过 1000 字");
        cleanTags(tags); cleanPlatforms(testedPlatforms);
    }

    private List<String> cleanTags(List<String> values) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (values != null) for (String value : values) {
            String tag = text(value); if (tag.isBlank()) continue;
            if (tag.length() > 24) throw new BadRequestException("单个标签不能超过 24 字"); unique.add(tag);
        }
        if (unique.size() > 8) throw new BadRequestException("最多选择 8 个标签");
        return List.copyOf(unique);
    }

    private List<String> cleanPlatforms(List<String> values) {
        LinkedHashSet<String> unique = new LinkedHashSet<>(values == null ? List.of() : values);
        if (unique.isEmpty() || !PLATFORMS.containsAll(unique)) throw new BadRequestException("请至少选择一个已测平台");
        return List.copyOf(unique);
    }

    private SkillEntity owned(String skillId, String memberId) {
        SkillEntity skill = skills.findOwned(skillId, memberId);
        if (skill == null) throw new NotFoundException("Skill 不存在或不属于当前成员"); return skill;
    }
    private SkillEntity requiredSkill(String id) { SkillEntity value=skills.selectById(id); if(value==null)throw new NotFoundException("Skill 不存在"); return value; }
    private SkillVersionEntity versionOwned(String id, SkillEntity skill) {
        SkillVersionEntity value=versions.findMeta(id); if(value==null||!skill.getId().equals(value.getSkillId()))throw new NotFoundException("Skill 版本不存在"); return value;
    }
    private SkillVersionEntity pending(String id) { SkillVersionEntity value=versions.findMeta(id); if(value==null)throw new NotFoundException("Skill 版本不存在"); if(!"PENDING_REVIEW".equals(value.getState()))throw new BadRequestException("该版本不在待审状态"); return value; }
    private String requiredMember(String memberId) { if(memberId==null||members.selectById(memberId)==null)throw new UnauthorizedException("请先登录 WAO"); return memberId; }
    private void requireAdmin(String memberId) { MemberEntity member=members.selectById(requiredMember(memberId)); if(!Set.of("管理员","超级管理员").contains(member.getRole()))throw new UnauthorizedException("只有管理员可以审核 Skill"); }

    private SkillDtos.MySkill mySkill(SkillEntity skill, String memberId) {
        List<SkillVersionEntity> all = versions.listMeta(skill.getId());
        SkillVersionEntity shown = all.stream().filter(v -> v.getId().equals(skill.getPublishedVersionId())).findFirst().orElse(all.isEmpty()?null:all.get(0));
        return new SkillDtos.MySkill(skill.getId(),skill.getSlug(),shown==null?skill.getDisplayName():shown.getDisplayName(),
                shown==null?skill.getSummary():shown.getSummary(),shown==null?skill.getCategory():shown.getCategory(),skill.getStatus(),
                n(skill.getDownloadCount()),n(skill.getFavoriteCount()),all.stream().map(this::toVersion).toList());
    }

    private SkillDtos.Card toCard(SkillMarketRow row) {
        return new SkillDtos.Card(row.getId(),row.getSlug(),row.getDisplayName(),row.getSummary(),row.getCategory(),
                readStrings(row.getTagsJson()),row.getStatus(),n(row.getDownloadCount()),n(row.getFavoriteCount()),row.getUpdatedAt(),
                new SkillDtos.Publisher(row.getPublisherHandle(),row.getPublisherName()),row.getVersion(),readStrings(row.getCompatiblePlatformsJson()));
    }

    private SkillDtos.Version toVersion(SkillVersionEntity version) {
        return new SkillDtos.Version(version.getId(),version.getVersion(),version.getDisplayName(),version.getSummary(),
                version.getState(),version.getChangelog(),version.getLicenseCode(),
                readStrings(version.getTestedPlatformsJson()),readStrings(version.getCompatiblePlatformsJson()),n(version.getPackageSize()),
                n(version.getUnpackedSize()),version.getFileCount()==null?0:version.getFileCount(),version.getSha256(),
                readMaps(version.getFileManifestJson()),readStrings(version.getScanFindingsJson()),version.getSkillMd(),version.getReviewNote(),
                version.getSubmittedAt(),version.getReviewedAt());
    }

    private String write(Object value) { try{return json.writeValueAsString(value);}catch(JsonProcessingException ex){throw new IllegalStateException(ex);} }
    private List<String> readStrings(String value) { if(value==null||value.isBlank())return List.of(); try{return json.readValue(value,new TypeReference<>(){});}catch(JsonProcessingException ex){return List.of();} }
    private List<Map<String,Object>> readMaps(String value) { if(value==null||value.isBlank())return List.of(); try{return json.readValue(value,new TypeReference<>(){});}catch(JsonProcessingException ex){return List.of();} }
    private static String text(String value){return value==null?"":value.trim();}
    private static String limitText(String value,int max){return value.length()<=max?value:value.substring(0,max);}
    private static void requireLength(String value,String label,int min,int max){if(value.length()<min||value.length()>max)throw new BadRequestException(label+"需为 "+min+"-"+max+" 字");}
    private static long n(Long value){return value==null?0:value;}
    public record Download(String fileName,String sha256,byte[] content) { }
}
