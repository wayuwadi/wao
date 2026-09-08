package cn.wayuwa.wao.agent.service;

import cn.wayuwa.wao.agent.ai.ContentGenerator;
import cn.wayuwa.wao.agent.ai.GeneratedContent;
import cn.wayuwa.wao.agent.ai.PromptLibrary;
import cn.wayuwa.wao.agent.ai.RecommendedModelCatalog;
import cn.wayuwa.wao.agent.common.BadRequestException;
import cn.wayuwa.wao.agent.common.ConflictException;
import cn.wayuwa.wao.agent.config.NodeProperties;
import cn.wayuwa.wao.agent.domain.HotspotEntity;
import cn.wayuwa.wao.agent.domain.MediaAssetEntity;
import cn.wayuwa.wao.agent.domain.PlatformAccountEntity;
import cn.wayuwa.wao.agent.domain.PublishMetricSnapshotEntity;
import cn.wayuwa.wao.agent.domain.PublishTaskEntity;
import cn.wayuwa.wao.agent.dto.AiStatusDto;
import cn.wayuwa.wao.agent.dto.ContentMetricSnapshotDto;
import cn.wayuwa.wao.agent.dto.ContentSourceDto;
import cn.wayuwa.wao.agent.dto.CreatePublishTaskRequest;
import cn.wayuwa.wao.agent.dto.CreateContentGroupRequest;
import cn.wayuwa.wao.agent.dto.PublishCenterDto;
import cn.wayuwa.wao.agent.dto.PublishTaskDto;
import cn.wayuwa.wao.agent.dto.UpdatePublicationDataRequest;
import cn.wayuwa.wao.agent.dto.UpdatePublishTaskRequest;
import cn.wayuwa.wao.agent.mapper.PublishMetricSnapshotMapper;
import cn.wayuwa.wao.agent.mapper.PublishTaskMapper;
import cn.wayuwa.wao.agent.platform.MediaKind;
import cn.wayuwa.wao.agent.platform.PlatformCapability;
import cn.wayuwa.wao.agent.platform.PlatformCredential;
import cn.wayuwa.wao.agent.platform.PlatformRegistry;
import cn.wayuwa.wao.agent.platform.PlatformType;
import cn.wayuwa.wao.agent.platform.PublishPlatform;
import cn.wayuwa.wao.agent.platform.PublishRequest;
import cn.wayuwa.wao.agent.platform.PublishResult;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Paths;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 发布任务状态机：话题 → 生成文案 → 人工审核 → 平台发布。
 *
 * <p>状态流转 {@code DRAFT/GENERATING → WAITING_REVIEW → PUBLISHING → PUBLISHED|FAILED}。
 * 所有推进都落库，服务重启后由定时器继续；发布阶段只能由持有素材的节点执行。
 */
@Service
public class PublishTaskService {
    private static final Logger log = LoggerFactory.getLogger(PublishTaskService.class);

    public static final String DRAFT = "DRAFT";
    public static final String GENERATING = "GENERATING";
    public static final String WAITING_REVIEW = "WAITING_REVIEW";
    public static final String PUBLISHING = "PUBLISHING";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String FAILED = "FAILED";
    private static final String LOCAL_EXECUTOR = "AGENT_LOCAL";
    private static final String CENTRAL_EXECUTOR = "WAO_RECOMMENDED";
    private static final List<PlatformType> CONTENT_GROUP_PLATFORMS = List.of(
            PlatformType.DOUYIN, PlatformType.XIAOHONGSHU, PlatformType.WECHAT_MP);

    private final SharedAccountService accounts;
    private final PlatformAccountService platformAccounts;
    private final MediaAssetService mediaAssets;
    private final PublishTaskMapper taskMapper;
    private final PlatformRegistry registry;
    private final ContentGenerator generator;
    private final RecommendedModelCatalog recommendedModels;
    private final WorkspaceProfileService profiles;
    private final HotspotService hotspots;
    private final NodeProperties node;
    private final CreatorContextService creatorContext;
    private final PublishMetricSnapshotMapper metrics;
    private final ObjectMapper mapper = new ObjectMapper();

    public PublishTaskService(SharedAccountService accounts, PlatformAccountService platformAccounts,
                              MediaAssetService mediaAssets, PublishTaskMapper taskMapper,
                              PlatformRegistry registry, ContentGenerator generator,
                              RecommendedModelCatalog recommendedModels, WorkspaceProfileService profiles,
                              HotspotService hotspots, NodeProperties node,
                              CreatorContextService creatorContext, PublishMetricSnapshotMapper metrics) {
        this.accounts = accounts;
        this.platformAccounts = platformAccounts;
        this.mediaAssets = mediaAssets;
        this.taskMapper = taskMapper;
        this.registry = registry;
        this.generator = generator;
        this.recommendedModels = recommendedModels;
        this.profiles = profiles;
        this.hotspots = hotspots;
        this.node = node;
        this.creatorContext = creatorContext;
        this.metrics = metrics;
    }

    /** 发布中心聚合数据：会话、模型状态、平台能力和最近任务。 */
    public PublishCenterDto center(String accountId) {
        String workspaceId = accounts.workspaceId(accountId);
        List<PublishTaskEntity> tasks = taskMapper.selectList(Wrappers.<PublishTaskEntity>lambdaQuery()
                .eq(PublishTaskEntity::getWorkspaceId, workspaceId)
                .orderByDesc(PublishTaskEntity::getCreatedAt).last("LIMIT 20"));
        AiStatusDto ai = aiStatus();
        return new PublishCenterDto(accounts.session(accountId), ai,
                platformAccounts.summaries(accountId),
                tasks.stream().map(task -> toDto(task, workspaceId)).toList());
    }

    /** 新建发布任务；requestGeneration 为真时进入异步生成。 */
    public PublishTaskDto create(String accountId, CreatePublishTaskRequest request) {
        String workspaceId = accounts.workspaceId(accountId);
        PlatformType type = PlatformType.parse(request.platform());
        boolean generate = Boolean.TRUE.equals(request.generateCopy());
        String generationExecutor = generate ? generationExecutor() : null;
        if (generate && generationExecutor == null) {
            throw new BadRequestException("当前没有可用的推荐模型，请在 WAO 的 AI 账号中启用模型后重试，或关闭 AI 生成创建手稿");
        }
        LocalDateTime now = LocalDateTime.now();
        PublishTaskEntity task = new PublishTaskEntity();
        task.setId(UUID.randomUUID().toString());
        task.setWorkspaceId(workspaceId);
        task.setRequestedByAccountId(accountId);
        task.setPlatform(type.name());
        task.setTopic(request.topic().trim());
        task.setSourceHotspotId(request.sourceHotspotId());
        task.setSourceNotes(emptyToNull(request.sourceNotes()));
        task.setUseWaoContext(!Boolean.FALSE.equals(request.useWaoContext()));
        task.setStatus(generate ? GENERATING : DRAFT);
        task.setProgress(generate ? 10 : 0);
        if (generate) prepareGeneration(task, type, generationExecutor);
        task.setVersion(1);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskMapper.insert(task);
        return toDto(task, workspaceId);
    }

    /**
     * 原子创建一个三端内容组；三条任务复用同一份权限收窄后的上下文快照，分别固化平台提示词。
     */
    @Transactional
    public List<PublishTaskDto> createContentGroup(String accountId, CreateContentGroupRequest request) {
        String executor = generationExecutor();
        if (executor == null) {
            throw new BadRequestException("当前没有可用的推荐模型，请在 WAO 的 AI 账号中启用模型后重试");
        }
        String workspaceId = accounts.workspaceId(accountId);
        String topic = request.topic().trim();
        String sourceNotes = emptyToNull(request.sourceNotes());
        boolean useWaoContext = !Boolean.FALSE.equals(request.useWaoContext());
        CreatorContextService.Context context = useWaoContext
                ? creatorContext.find(accounts.memberId(accountId), workspaceId, topic, sourceNotes)
                : new CreatorContextService.Context("", List.of());
        String contentGroupId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();

        return CONTENT_GROUP_PLATFORMS.stream().map(platform -> {
            PublishTaskEntity task = new PublishTaskEntity();
            task.setId(UUID.randomUUID().toString());
            task.setWorkspaceId(workspaceId);
            task.setRequestedByAccountId(accountId);
            task.setContentGroupId(contentGroupId);
            task.setPlatform(platform.name());
            task.setTopic(topic);
            task.setSourceNotes(sourceNotes);
            task.setUseWaoContext(useWaoContext);
            task.setStatus(GENERATING);
            task.setProgress(10);
            prepareGeneration(task, platform, executor, context);
            task.setVersion(1);
            task.setCreatedAt(now);
            task.setUpdatedAt(now);
            taskMapper.insert(task);
            return toDto(task, workspaceId);
        }).toList();
    }

    /** 生成失败后沿用当时固化的资料再次入队，不重新读取可能已变化的上下文。 */
    public PublishTaskDto retryGeneration(String accountId, String taskId) {
        String workspaceId = accounts.workspaceId(accountId);
        PublishTaskEntity task = require(workspaceId, taskId);
        if (!FAILED.equals(task.getStatus()) || !StringUtils.hasText(task.getGenerationSystemPrompt())
                || !StringUtils.hasText(task.getGenerationUserPrompt())) {
            throw new ConflictException("当前任务没有可重试的文案生成请求");
        }
        String executor = generationExecutor();
        if (executor == null) {
            throw new BadRequestException("当前没有可用的推荐模型，请先在 WAO 的 AI 账号中启用模型");
        }
        int updated = taskMapper.update(null, Wrappers.<PublishTaskEntity>lambdaUpdate()
                .eq(PublishTaskEntity::getId, taskId)
                .eq(PublishTaskEntity::getWorkspaceId, workspaceId)
                .eq(PublishTaskEntity::getStatus, FAILED)
                .set(PublishTaskEntity::getGenerationExecutor, executor)
                .set(PublishTaskEntity::getGenerationClaimToken, null)
                .set(PublishTaskEntity::getGenerationLeaseUntil, null)
                .set(PublishTaskEntity::getNodeId, null)
                .set(PublishTaskEntity::getStatus, GENERATING)
                .set(PublishTaskEntity::getProgress, 10)
                .set(PublishTaskEntity::getErrorMessage, null)
                .set(PublishTaskEntity::getUpdatedAt, LocalDateTime.now()));
        if (updated == 0) throw new ConflictException("任务状态已经变化，请刷新后重试");
        return toDto(require(workspaceId, taskId), workspaceId);
    }

    /** 人工审核阶段修改文案、素材和目标账号，按 version 乐观锁。 */
    public PublishTaskDto update(String accountId, String taskId, UpdatePublishTaskRequest request) {
        String workspaceId = accounts.workspaceId(accountId);
        PublishTaskEntity task = require(workspaceId, taskId);
        if (!List.of(DRAFT, WAITING_REVIEW, FAILED).contains(task.getStatus())) {
            throw new ConflictException("当前状态不可编辑：" + task.getStatus());
        }
        if (StringUtils.hasText(request.platformAccountId())) {
            PlatformAccountEntity bound = platformAccounts.require(workspaceId, request.platformAccountId());
            if (!bound.getPlatform().equals(task.getPlatform())) {
                throw new BadRequestException("所选账号与任务平台不一致");
            }
        }
        if (StringUtils.hasText(request.mediaAssetId())) {
            mediaAssets.require(workspaceId, request.mediaAssetId());
        }
        int expected = request.version();
        int updated = taskMapper.update(null, Wrappers.<PublishTaskEntity>lambdaUpdate()
                .eq(PublishTaskEntity::getId, taskId)
                .eq(PublishTaskEntity::getWorkspaceId, workspaceId)
                .eq(PublishTaskEntity::getVersion, expected)
                .set(PublishTaskEntity::getTitle, trim(request.title(), 200))
                .set(PublishTaskEntity::getContent, request.content())
                .set(PublishTaskEntity::getTagsJson, writeTags(request.tags()))
                .set(PublishTaskEntity::getPlatformAccountId, emptyToNull(request.platformAccountId()))
                .set(PublishTaskEntity::getMediaAssetId, emptyToNull(request.mediaAssetId()))
                .set(PublishTaskEntity::getStatus, WAITING_REVIEW)
                .set(PublishTaskEntity::getGenerationSystemPrompt, null)
                .set(PublishTaskEntity::getGenerationUserPrompt, null)
                .set(PublishTaskEntity::getGenerationClaimToken, null)
                .set(PublishTaskEntity::getGenerationLeaseUntil, null)
                .set(PublishTaskEntity::getErrorMessage, null)
                .set(PublishTaskEntity::getVersion, expected + 1)
                .set(PublishTaskEntity::getUpdatedAt, LocalDateTime.now()));
        if (updated == 0) throw new ConflictException("任务已在别处更新，请刷新后重试");
        return toDto(require(workspaceId, taskId), workspaceId);
    }

    /**
     * 人工确认后提交发布。这里做全部前置校验，并把任务钉到持有素材的节点。
     */
    public PublishTaskDto submit(String accountId, String taskId) {
        String workspaceId = accounts.workspaceId(accountId);
        PublishTaskEntity task = require(workspaceId, taskId);
        if (!List.of(WAITING_REVIEW, FAILED).contains(task.getStatus())) {
            throw new ConflictException("只有等待审核或发布失败的任务可以提交");
        }
        PlatformType type = PlatformType.valueOf(task.getPlatform());
        PublishPlatform platform = registry.get(type);
        PlatformCapability capability = platform.capability();
        if (!capability.publishSupported()) {
            throw new BadRequestException(capability.note());
        }
        if (!StringUtils.hasText(task.getPlatformAccountId())) {
            throw new BadRequestException("请先选择要发布到的" + type.label() + "账号");
        }
        PlatformAccountEntity bound = platformAccounts.require(workspaceId, task.getPlatformAccountId());
        if (!PlatformAccountService.STATUS_ACTIVE.equals(bound.getStatus())) {
            throw new BadRequestException("「" + bound.getDisplayName() + "」需要重新授权后才能发布");
        }
        if (!StringUtils.hasText(task.getContent()) && !StringUtils.hasText(task.getTitle())) {
            throw new BadRequestException("请先填写标题或正文");
        }
        MediaAssetEntity media = null;
        if (capability.mediaKinds().contains(MediaKind.VIDEO) || capability.mediaKinds().contains(MediaKind.IMAGE)) {
            if (!StringUtils.hasText(task.getMediaAssetId())) {
                throw new BadRequestException(type.label() + "发布需要先上传素材");
            }
            media = mediaAssets.require(workspaceId, task.getMediaAssetId());
            if (!capability.mediaKinds().contains(media.getKind())) {
                throw new BadRequestException(type.label() + "暂不支持该素材形态：" + media.getKind());
            }
        }

        int expected = task.getVersion();
        int updated = taskMapper.update(null, Wrappers.<PublishTaskEntity>lambdaUpdate()
                .eq(PublishTaskEntity::getId, taskId)
                .eq(PublishTaskEntity::getVersion, expected)
                .in(PublishTaskEntity::getStatus, List.of(WAITING_REVIEW, FAILED))
                .set(PublishTaskEntity::getStatus, PUBLISHING)
                .set(PublishTaskEntity::getProgress, 60)
                .set(PublishTaskEntity::getErrorMessage, null)
                // 素材在哪台节点，就由哪台节点执行发布。
                .set(PublishTaskEntity::getNodeId, media == null ? node.resolved() : media.getNodeId())
                .set(PublishTaskEntity::getVersion, expected + 1)
                .set(PublishTaskEntity::getUpdatedAt, LocalDateTime.now()));
        if (updated == 0) throw new ConflictException("任务已在别处更新，请刷新后重试");
        return toDto(require(workspaceId, taskId), workspaceId);
    }

    /** 对没有官方发布接口的平台，由成员在站外发布后手动确认已发布。 */
    public PublishTaskDto markPublished(String accountId, String taskId) {
        String workspaceId = accounts.workspaceId(accountId);
        PublishTaskEntity task = require(workspaceId, taskId);
        PlatformCapability capability = registry.get(PlatformType.valueOf(task.getPlatform())).capability();
        if (capability.publishSupported()) {
            throw new BadRequestException("该平台已接入官方发布，请使用人工确认后的平台提交入口");
        }
        if (!List.of(WAITING_REVIEW, FAILED).contains(task.getStatus())) {
            throw new ConflictException("只有待发布或发布失败的内容可以手动标记已发布");
        }
        int expected = task.getVersion();
        int updated = taskMapper.update(null, Wrappers.<PublishTaskEntity>lambdaUpdate()
                .eq(PublishTaskEntity::getId, taskId)
                .eq(PublishTaskEntity::getWorkspaceId, workspaceId)
                .eq(PublishTaskEntity::getVersion, expected)
                .in(PublishTaskEntity::getStatus, List.of(WAITING_REVIEW, FAILED))
                .set(PublishTaskEntity::getStatus, PUBLISHED)
                .set(PublishTaskEntity::getProgress, 100)
                .set(PublishTaskEntity::getPublishedAt, LocalDateTime.now())
                .set(PublishTaskEntity::getErrorMessage, null)
                .set(PublishTaskEntity::getVersion, expected + 1)
                .set(PublishTaskEntity::getUpdatedAt, LocalDateTime.now()));
        if (updated == 0) throw new ConflictException("任务已在别处更新，请刷新后重试");
        return toDto(require(workspaceId, taskId), workspaceId);
    }

    /** 已发布后保存真实链接，并在填写了指标时追加一条不可变数据快照。 */
    @Transactional
    public PublishTaskDto updatePublicationData(String accountId, String taskId,
                                                UpdatePublicationDataRequest request) {
        String workspaceId = accounts.workspaceId(accountId);
        PublishTaskEntity task = require(workspaceId, taskId);
        if (!PUBLISHED.equals(task.getStatus())) {
            throw new ConflictException("只有已发布内容可以记录发布链接和数据");
        }
        String publicationUrl = safePublicationUrl(request.publicationUrl());
        int expected = request.version();
        int updated = taskMapper.update(null, Wrappers.<PublishTaskEntity>lambdaUpdate()
                .eq(PublishTaskEntity::getId, taskId)
                .eq(PublishTaskEntity::getWorkspaceId, workspaceId)
                .eq(PublishTaskEntity::getVersion, expected)
                .eq(PublishTaskEntity::getStatus, PUBLISHED)
                .set(PublishTaskEntity::getPublicationUrl, publicationUrl)
                .set(PublishTaskEntity::getVersion, expected + 1)
                .set(PublishTaskEntity::getUpdatedAt, LocalDateTime.now()));
        if (updated == 0) throw new ConflictException("内容已在别处更新，请刷新后重试");
        if (request.hasMetrics()) {
            PublishMetricSnapshotEntity row = new PublishMetricSnapshotEntity();
            row.setId(UUID.randomUUID().toString());
            row.setPublishTaskId(taskId);
            row.setWorkspaceId(workspaceId);
            row.setViewsCount(request.views());
            row.setLikesCount(request.likes());
            row.setCommentsCount(request.comments());
            row.setFavoritesCount(request.favorites());
            row.setSharesCount(request.shares());
            row.setRecordedAt(LocalDateTime.now());
            metrics.insert(row);
        }
        return toDto(require(workspaceId, taskId), workspaceId);
    }

    /** 推进由 Agent 本地模型执行的生成任务；WAO 推荐模型任务由主服务领取。 */
    @Scheduled(fixedDelayString = "${wao-agent.publish.generate-delay-ms:2000}")
    public void processGenerating() {
        if (!generator.available()) return;
        List<PublishTaskEntity> pending = taskMapper.selectList(Wrappers.<PublishTaskEntity>lambdaQuery()
                .eq(PublishTaskEntity::getStatus, GENERATING)
                .eq(PublishTaskEntity::getGenerationExecutor, LOCAL_EXECUTOR)
                .isNull(PublishTaskEntity::getNodeId)
                .orderByAsc(PublishTaskEntity::getUpdatedAt).last("LIMIT 5"));
        for (PublishTaskEntity task : pending) {
            if (!claim(task)) continue;
            try {
                GeneratedContent copy = generator.generatePrepared(task.getGenerationSystemPrompt(),
                        task.getGenerationUserPrompt(), task.getTopic());
                taskMapper.update(null, Wrappers.<PublishTaskEntity>lambdaUpdate()
                        .eq(PublishTaskEntity::getId, task.getId())
                        .set(PublishTaskEntity::getTitle, trim(copy.title(), 200))
                        .set(PublishTaskEntity::getContent, copy.body())
                        .set(PublishTaskEntity::getTagsJson, writeTags(copy.tags()))
                        .set(PublishTaskEntity::getCoverText, trim(copy.coverText(), 200))
                        .set(PublishTaskEntity::getStructureJson, writeJson(copy.blocks()))
                        .set(PublishTaskEntity::getGenerationSystemPrompt, null)
                        .set(PublishTaskEntity::getGenerationUserPrompt, null)
                        .set(PublishTaskEntity::getGenerationClaimToken, null)
                        .set(PublishTaskEntity::getGenerationLeaseUntil, null)
                        .set(PublishTaskEntity::getStatus, WAITING_REVIEW)
                        .set(PublishTaskEntity::getProgress, 45)
                        .set(PublishTaskEntity::getNodeId, null)
                        .set(PublishTaskEntity::getUpdatedAt, LocalDateTime.now()));
            } catch (RuntimeException ex) {
                log.warn("生成文案失败 task={} : {}", task.getId(), ex.getMessage());
                fail(task.getId(), "生成文案失败：" + ex.getMessage());
            }
        }
    }

    /** 执行发布；只处理钉在本节点的任务，因为素材在本地磁盘上。 */
    @Scheduled(fixedDelayString = "${wao-agent.publish.publish-delay-ms:2000}")
    public void processPublishing() {
        List<PublishTaskEntity> pending = taskMapper.selectList(Wrappers.<PublishTaskEntity>lambdaQuery()
                .eq(PublishTaskEntity::getStatus, PUBLISHING)
                .eq(PublishTaskEntity::getNodeId, node.resolved())
                .orderByAsc(PublishTaskEntity::getUpdatedAt).last("LIMIT 3"));
        for (PublishTaskEntity task : pending) {
            try {
                publishOne(task);
            } catch (RuntimeException ex) {
                log.warn("发布失败 task={} : {}", task.getId(), ex.getMessage());
                fail(task.getId(), ex.getMessage());
            }
        }
    }

    private void publishOne(PublishTaskEntity task) {
        PlatformType type = PlatformType.valueOf(task.getPlatform());
        PlatformAccountEntity bound = platformAccounts.require(task.getWorkspaceId(), task.getPlatformAccountId());
        PlatformCredential credential = platformAccounts.activeCredential(bound);
        MediaAssetEntity media = mediaAssets.find(task.getMediaAssetId());
        PublishRequest request = new PublishRequest(task.getTitle(), task.getContent(), readTags(task.getTagsJson()),
                media == null ? null : media.getKind(),
                media == null ? null : Paths.get(media.getStoragePath()),
                media == null ? null : media.getOriginalName());
        PublishResult result = registry.get(type).publish(credential, request);
        LocalDateTime now = LocalDateTime.now();
        taskMapper.update(null, Wrappers.<PublishTaskEntity>lambdaUpdate()
                .eq(PublishTaskEntity::getId, task.getId())
                .eq(PublishTaskEntity::getStatus, PUBLISHING)
                .set(PublishTaskEntity::getStatus, PUBLISHED)
                .set(PublishTaskEntity::getProgress, 100)
                .set(PublishTaskEntity::getPlatformItemId, result.platformItemId())
                .set(PublishTaskEntity::getErrorMessage, null)
                .set(PublishTaskEntity::getPublishedAt, now)
                .set(PublishTaskEntity::getUpdatedAt, now));
        log.info("发布完成 task={} platform={} item={}", task.getId(), task.getPlatform(), result.platformItemId());
    }

    /** 抢占一条生成任务，成功返回 true。 */
    private boolean claim(PublishTaskEntity task) {
        return taskMapper.update(null, Wrappers.<PublishTaskEntity>lambdaUpdate()
                .eq(PublishTaskEntity::getId, task.getId())
                .eq(PublishTaskEntity::getStatus, GENERATING)
                .isNull(PublishTaskEntity::getNodeId)
                .set(PublishTaskEntity::getNodeId, node.resolved())
                .set(PublishTaskEntity::getProgress, 25)
                .set(PublishTaskEntity::getUpdatedAt, LocalDateTime.now())) == 1;
    }

    private void fail(String taskId, String message) {
        taskMapper.update(null, Wrappers.<PublishTaskEntity>lambdaUpdate()
                .eq(PublishTaskEntity::getId, taskId)
                .set(PublishTaskEntity::getStatus, FAILED)
                .set(PublishTaskEntity::getNodeId, null)
                .set(PublishTaskEntity::getErrorMessage, trim(message, 900))
                .set(PublishTaskEntity::getUpdatedAt, LocalDateTime.now()));
    }

    private PublishTaskEntity require(String workspaceId, String taskId) {
        PublishTaskEntity task = taskMapper.selectById(taskId);
        if (task == null || !workspaceId.equals(task.getWorkspaceId())) {
            throw new BadRequestException("发布任务不存在");
        }
        return task;
    }

    private PublishTaskDto toDto(PublishTaskEntity task, String workspaceId) {
        PlatformType type = PlatformType.valueOf(task.getPlatform());
        String accountName = null;
        if (StringUtils.hasText(task.getPlatformAccountId())) {
            try {
                accountName = platformAccounts.require(workspaceId, task.getPlatformAccountId()).getDisplayName();
            } catch (BadRequestException ignored) {
                accountName = "账号已解绑";
            }
        }
        MediaAssetEntity media = mediaAssets.find(task.getMediaAssetId());
        HotspotEntity hotspot = hotspots.find(task.getSourceHotspotId());
        return new PublishTaskDto(task.getId(), task.getContentGroupId(), task.getPlatform(), type.label(), task.getPlatformAccountId(),
                accountName, task.getMediaAssetId(), media == null ? null : media.getOriginalName(),
                task.getTopic(), task.getTitle(), task.getContent(), readTags(task.getTagsJson()),
                task.getCoverText(), readBlocks(task.getStructureJson()),
                task.getSourceHotspotId(), hotspot == null ? null : hotspot.getTitle(),
                task.getSourceNotes(), !Boolean.FALSE.equals(task.getUseWaoContext()),
                readSources(task.getSourceContextJson()), task.getStatus(), task.getProgress(),
                task.getPlatformItemId(), task.getPublicationUrl(), metricDtos(task.getId(), workspaceId),
                task.getErrorMessage(), FAILED.equals(task.getStatus())
                        && StringUtils.hasText(task.getGenerationSystemPrompt()),
                task.getVersion(), task.getCreatedAt(), task.getUpdatedAt(), task.getPublishedAt());
    }

    private AiStatusDto aiStatus() {
        if (generator.available()) {
            return new AiStatusDto(true, generator.provider(), generator.model());
        }
        RecommendedModelCatalog.ModelMetadata central = recommendedModels.current();
        return central == null
                ? new AiStatusDto(false, null, null)
                : new AiStatusDto(true, central.displayProvider(), central.model());
    }

    private String generationExecutor() {
        if (generator.available()) return LOCAL_EXECUTOR;
        return recommendedModels.current() == null ? null : CENTRAL_EXECUTOR;
    }

    private void prepareGeneration(PublishTaskEntity task, PlatformType platform, String executor) {
        CreatorContextService.Context context = Boolean.FALSE.equals(task.getUseWaoContext())
                ? new CreatorContextService.Context("", List.of())
                : creatorContext.find(accounts.memberId(task.getRequestedByAccountId()), task.getWorkspaceId(),
                task.getTopic(), task.getSourceNotes());
        prepareGeneration(task, platform, executor, context);
    }

    private void prepareGeneration(PublishTaskEntity task, PlatformType platform, String executor,
                                   CreatorContextService.Context context) {
        task.setGenerationExecutor(executor);
        task.setGenerationSystemPrompt(PromptLibrary.content(platform, profiles.find(task.getWorkspaceId()),
                context.prompt()));
        task.setGenerationUserPrompt(ContentGenerator.userPrompt(task.getTopic(), task.getSourceNotes()));
        task.setSourceContextJson(writeSources(context.sources()));
    }

    private List<ContentMetricSnapshotDto> metricDtos(String taskId, String workspaceId) {
        return metrics.recent(taskId, workspaceId).stream().map(row -> new ContentMetricSnapshotDto(
                row.getId(), row.getViewsCount(), row.getLikesCount(), row.getCommentsCount(),
                row.getFavoritesCount(), row.getSharesCount(), row.getRecordedAt())).toList();
    }

    private String writeSources(List<ContentSourceDto> sources) {
        if (sources == null || sources.isEmpty()) return null;
        try {
            return mapper.writeValueAsString(sources);
        } catch (Exception ex) {
            throw new IllegalStateException("生成来源快照保存失败", ex);
        }
    }

    private List<ContentSourceDto> readSources(String raw) {
        if (!StringUtils.hasText(raw)) return List.of();
        try {
            return mapper.readValue(raw, new TypeReference<List<ContentSourceDto>>() { });
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String writeJson(List<GeneratedContent.Block> blocks) {
        if (blocks == null || blocks.isEmpty()) return null;
        try {
            return mapper.writeValueAsString(blocks);
        } catch (Exception ex) {
            return null;
        }
    }

    private List<GeneratedContent.Block> readBlocks(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<List<GeneratedContent.Block>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String writeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return null;
        try {
            String json = mapper.writeValueAsString(tags.stream()
                    .filter(StringUtils::hasText).map(tag -> tag.replace("#", "").trim()).limit(10).toList());
            return json.length() > 1000 ? null : json;
        } catch (Exception ex) {
            return null;
        }
    }

    private List<String> readTags(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String trim(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String safePublicationUrl(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            URI uri = URI.create(value.trim());
            if (!List.of("http", "https").contains(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
                throw new IllegalArgumentException();
            }
            return uri.toString();
        } catch (IllegalArgumentException ignored) {
            throw new BadRequestException("发布链接必须是完整的 http 或 https 地址");
        }
    }
}
