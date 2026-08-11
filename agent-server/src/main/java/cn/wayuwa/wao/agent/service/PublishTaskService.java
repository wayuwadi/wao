package cn.wayuwa.wao.agent.service;

import cn.wayuwa.wao.agent.ai.ContentGenerator;
import cn.wayuwa.wao.agent.ai.GeneratedContent;
import cn.wayuwa.wao.agent.common.BadRequestException;
import cn.wayuwa.wao.agent.common.ConflictException;
import cn.wayuwa.wao.agent.config.NodeProperties;
import cn.wayuwa.wao.agent.domain.HotspotEntity;
import cn.wayuwa.wao.agent.domain.MediaAssetEntity;
import cn.wayuwa.wao.agent.domain.PlatformAccountEntity;
import cn.wayuwa.wao.agent.domain.PublishTaskEntity;
import cn.wayuwa.wao.agent.dto.AiStatusDto;
import cn.wayuwa.wao.agent.dto.CreatePublishTaskRequest;
import cn.wayuwa.wao.agent.dto.PublishCenterDto;
import cn.wayuwa.wao.agent.dto.PublishTaskDto;
import cn.wayuwa.wao.agent.dto.UpdatePublishTaskRequest;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
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

    private final SharedAccountService accounts;
    private final PlatformAccountService platformAccounts;
    private final MediaAssetService mediaAssets;
    private final PublishTaskMapper taskMapper;
    private final PlatformRegistry registry;
    private final ContentGenerator generator;
    private final WorkspaceProfileService profiles;
    private final HotspotService hotspots;
    private final NodeProperties node;
    private final ObjectMapper mapper = new ObjectMapper();

    public PublishTaskService(SharedAccountService accounts, PlatformAccountService platformAccounts,
                              MediaAssetService mediaAssets, PublishTaskMapper taskMapper,
                              PlatformRegistry registry, ContentGenerator generator,
                              WorkspaceProfileService profiles, HotspotService hotspots, NodeProperties node) {
        this.accounts = accounts;
        this.platformAccounts = platformAccounts;
        this.mediaAssets = mediaAssets;
        this.taskMapper = taskMapper;
        this.registry = registry;
        this.generator = generator;
        this.profiles = profiles;
        this.hotspots = hotspots;
        this.node = node;
    }

    /** 发布中心聚合数据：会话、模型状态、平台能力和最近任务。 */
    public PublishCenterDto center(String accountId) {
        String workspaceId = accounts.workspaceId(accountId);
        List<PublishTaskEntity> tasks = taskMapper.selectList(Wrappers.<PublishTaskEntity>lambdaQuery()
                .eq(PublishTaskEntity::getWorkspaceId, workspaceId)
                .orderByDesc(PublishTaskEntity::getCreatedAt).last("LIMIT 20"));
        return new PublishCenterDto(accounts.session(accountId),
                new AiStatusDto(generator.available(), generator.provider(), generator.model()),
                platformAccounts.summaries(accountId),
                tasks.stream().map(task -> toDto(task, workspaceId)).toList());
    }

    /** 新建发布任务；requestGeneration 为真时进入异步生成。 */
    public PublishTaskDto create(String accountId, CreatePublishTaskRequest request) {
        String workspaceId = accounts.workspaceId(accountId);
        PlatformType type = PlatformType.parse(request.platform());
        boolean generate = Boolean.TRUE.equals(request.generateCopy());
        if (generate && !generator.available()) {
            throw new BadRequestException("尚未配置文案生成模型，请先配置 AGENT_AI_* 环境变量，或关闭自动生成后手工填写文案");
        }
        LocalDateTime now = LocalDateTime.now();
        PublishTaskEntity task = new PublishTaskEntity();
        task.setId(UUID.randomUUID().toString());
        task.setWorkspaceId(workspaceId);
        task.setRequestedByAccountId(accountId);
        task.setPlatform(type.name());
        task.setTopic(request.topic().trim());
        task.setSourceHotspotId(request.sourceHotspotId());
        task.setStatus(generate ? GENERATING : DRAFT);
        task.setProgress(generate ? 10 : 0);
        task.setVersion(1);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskMapper.insert(task);
        return toDto(task, workspaceId);
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

    /** 推进生成中的任务；任意节点都可以执行，靠乐观锁抢占。 */
    @Scheduled(fixedDelayString = "${wao-agent.publish.generate-delay-ms:2000}")
    public void processGenerating() {
        if (!generator.available()) return;
        List<PublishTaskEntity> pending = taskMapper.selectList(Wrappers.<PublishTaskEntity>lambdaQuery()
                .eq(PublishTaskEntity::getStatus, GENERATING)
                .isNull(PublishTaskEntity::getNodeId)
                .orderByAsc(PublishTaskEntity::getUpdatedAt).last("LIMIT 5"));
        for (PublishTaskEntity task : pending) {
            if (!claim(task)) continue;
            try {
                GeneratedContent copy = generator.generate(task.getTopic(),
                        PlatformType.valueOf(task.getPlatform()), profiles.find(task.getWorkspaceId()));
                taskMapper.update(null, Wrappers.<PublishTaskEntity>lambdaUpdate()
                        .eq(PublishTaskEntity::getId, task.getId())
                        .set(PublishTaskEntity::getTitle, trim(copy.title(), 200))
                        .set(PublishTaskEntity::getContent, copy.body())
                        .set(PublishTaskEntity::getTagsJson, writeTags(copy.tags()))
                        .set(PublishTaskEntity::getCoverText, trim(copy.coverText(), 200))
                        .set(PublishTaskEntity::getStructureJson, writeJson(copy.blocks()))
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
        return new PublishTaskDto(task.getId(), task.getPlatform(), type.label(), task.getPlatformAccountId(),
                accountName, task.getMediaAssetId(), media == null ? null : media.getOriginalName(),
                task.getTopic(), task.getTitle(), task.getContent(), readTags(task.getTagsJson()),
                task.getCoverText(), readBlocks(task.getStructureJson()),
                task.getSourceHotspotId(), hotspot == null ? null : hotspot.getTitle(),
                task.getStatus(), task.getProgress(), task.getPlatformItemId(), task.getErrorMessage(),
                task.getVersion(), task.getCreatedAt(), task.getUpdatedAt(), task.getPublishedAt());
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
}
