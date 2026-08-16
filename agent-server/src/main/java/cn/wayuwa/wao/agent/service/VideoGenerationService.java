package cn.wayuwa.wao.agent.service;

import cn.wayuwa.wao.agent.common.BadRequestException;
import cn.wayuwa.wao.agent.common.ConflictException;
import cn.wayuwa.wao.agent.config.NodeProperties;
import cn.wayuwa.wao.agent.domain.MediaAssetEntity;
import cn.wayuwa.wao.agent.domain.VideoGenerationEntity;
import cn.wayuwa.wao.agent.dto.CreateVideoRequest;
import cn.wayuwa.wao.agent.dto.VideoCapabilityDto;
import cn.wayuwa.wao.agent.dto.VideoCenterDto;
import cn.wayuwa.wao.agent.dto.VideoGenerationDto;
import cn.wayuwa.wao.agent.mapper.VideoGenerationMapper;
import cn.wayuwa.wao.agent.video.OpenAiVideoClient;
import cn.wayuwa.wao.agent.video.VideoGenerationProperties;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 视频生成状态机：落库提交、轮询 OpenAI、下载到媒体卷并提供工作区隔离的播放文件。
 */
@Service
public class VideoGenerationService {
    private static final Logger log = LoggerFactory.getLogger(VideoGenerationService.class);

    public static final String SUBMITTING = "SUBMITTING";
    public static final String QUEUED = "QUEUED";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String DOWNLOADING = "DOWNLOADING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    private final SharedAccountService accounts;
    private final VideoGenerationMapper mapper;
    private final OpenAiVideoClient client;
    private final VideoGenerationProperties properties;
    private final MediaAssetService mediaAssets;
    private final NodeProperties node;

    public VideoGenerationService(SharedAccountService accounts, VideoGenerationMapper mapper,
                                  OpenAiVideoClient client, VideoGenerationProperties properties,
                                  MediaAssetService mediaAssets, NodeProperties node) {
        this.accounts = accounts;
        this.mapper = mapper;
        this.client = client;
        this.properties = properties;
        this.mediaAssets = mediaAssets;
        this.node = node;
    }

    /** 返回真实能力状态和当前工作区最近任务。 */
    public VideoCenterDto center(String accountId) {
        String workspaceId = accounts.workspaceId(accountId);
        List<VideoGenerationEntity> tasks = mapper.selectList(Wrappers.<VideoGenerationEntity>lambdaQuery()
                .eq(VideoGenerationEntity::getWorkspaceId, workspaceId)
                .orderByDesc(VideoGenerationEntity::getCreatedAt).last("LIMIT 20"));
        return new VideoCenterDto(capability(), tasks.stream().map(this::toDto).toList());
    }

    /** 先持久化本地任务，再使用幂等键提交真实 OpenAI 视频任务。 */
    public VideoGenerationDto create(String accountId, CreateVideoRequest request) {
        requireAvailable();
        validate(request);
        String workspaceId = accounts.workspaceId(accountId);
        LocalDateTime now = LocalDateTime.now();
        VideoGenerationEntity task = new VideoGenerationEntity();
        task.setId(UUID.randomUUID().toString());
        task.setWorkspaceId(workspaceId);
        task.setRequestedByAccountId(accountId);
        task.setPrompt(request.prompt().trim());
        task.setModel(request.model());
        task.setSeconds(request.seconds());
        task.setSize(request.size());
        task.setStatus(SUBMITTING);
        task.setProgress(0);
        task.setAttempt(1);
        task.setNodeId(node.resolved());
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        mapper.insert(task);
        submit(task);
        return toDto(require(workspaceId, task.getId()));
    }

    /** 失败任务人工重试；下载失败复用已有上游结果，生成失败才创建新的计费任务。 */
    public VideoGenerationDto retry(String accountId, String taskId) {
        requireAvailable();
        String workspaceId = accounts.workspaceId(accountId);
        VideoGenerationEntity task = require(workspaceId, taskId);
        if (!FAILED.equals(task.getStatus())) throw new ConflictException("只有失败的视频任务可以重试");
        int updated = mapper.update(null, Wrappers.<VideoGenerationEntity>lambdaUpdate()
                .eq(VideoGenerationEntity::getId, taskId)
                .eq(VideoGenerationEntity::getWorkspaceId, workspaceId)
                .eq(VideoGenerationEntity::getStatus, FAILED)
                .eq(VideoGenerationEntity::getAttempt, task.getAttempt())
                .set(VideoGenerationEntity::getAttempt, task.getAttempt() + 1)
                .set(VideoGenerationEntity::getStatus,
                        StringUtils.hasText(task.getProviderJobId()) ? IN_PROGRESS : SUBMITTING)
                .set(VideoGenerationEntity::getProgress, 0)
                .set(VideoGenerationEntity::getErrorMessage, null)
                .set(VideoGenerationEntity::getNodeId, node.resolved())
                .set(VideoGenerationEntity::getUpdatedAt, LocalDateTime.now()));
        if (updated == 0) throw new ConflictException("任务已在别处更新，请刷新后重试");
        task = require(workspaceId, taskId);
        if (SUBMITTING.equals(task.getStatus())) submit(task); else processOne(task);
        return toDto(require(workspaceId, taskId));
    }

    /** 获取已完成任务的媒体记录，同时执行账号、工作区、节点和状态校验。 */
    public MediaFile requireContent(String accountId, String taskId) {
        String workspaceId = accounts.workspaceId(accountId);
        VideoGenerationEntity task = require(workspaceId, taskId);
        if (!COMPLETED.equals(task.getStatus()) || !StringUtils.hasText(task.getMediaAssetId())) {
            throw new ConflictException("视频尚未生成完成");
        }
        if (!node.resolved().equals(task.getNodeId())) {
            throw new ConflictException("视频存放在其他节点，请通过主节点视频入口访问");
        }
        MediaAssetEntity asset = mediaAssets.require(workspaceId, task.getMediaAssetId());
        Path path = mediaAssets.localPath(asset);
        return new MediaFile(path, asset.getContentType(), asset.getOriginalName());
    }

    /** 服务重启后继续查询与下载钉在本节点的未完成任务。 */
    @Scheduled(fixedDelayString = "${wao-agent.video.poll-delay-ms:15000}")
    public void processPending() {
        if (!properties.configured()) return;
        List<VideoGenerationEntity> pending = mapper.selectList(Wrappers.<VideoGenerationEntity>lambdaQuery()
                .eq(VideoGenerationEntity::getNodeId, node.resolved())
                .in(VideoGenerationEntity::getStatus, List.of(SUBMITTING, QUEUED, IN_PROGRESS, DOWNLOADING))
                .orderByAsc(VideoGenerationEntity::getUpdatedAt).last("LIMIT 5"));
        for (VideoGenerationEntity task : pending) processOne(task);
    }

    private void submit(VideoGenerationEntity task) {
        try {
            OpenAiVideoClient.RemoteVideo remote = client.create(task.getPrompt(), task.getModel(),
                    task.getSeconds(), task.getSize(), task.getId() + "-" + task.getAttempt());
            String status = remoteStatus(remote.status());
            if (FAILED.equals(status)) {
                fail(task.getId(), remote.error(), false);
                return;
            }
            boolean readyToDownload = COMPLETED.equals(status);
            int updated = mapper.update(null, Wrappers.<VideoGenerationEntity>lambdaUpdate()
                    .eq(VideoGenerationEntity::getId, task.getId())
                    .eq(VideoGenerationEntity::getStatus, SUBMITTING)
                    .set(VideoGenerationEntity::getProviderJobId, remote.id())
                    .set(VideoGenerationEntity::getStatus, readyToDownload ? DOWNLOADING : status)
                    .set(VideoGenerationEntity::getProgress, remote.progress())
                    .set(VideoGenerationEntity::getUpdatedAt, LocalDateTime.now()));
            if (readyToDownload && updated > 0) {
                task.setProviderJobId(remote.id());
                task.setStatus(DOWNLOADING);
                download(task);
            }
        } catch (RuntimeException ex) {
            log.warn("视频任务提交失败 task={} : {}", task.getId(), ex.getMessage());
            fail(task.getId(), ex.getMessage(), false);
        }
    }

    private void processOne(VideoGenerationEntity task) {
        try {
            if (SUBMITTING.equals(task.getStatus())) {
                submit(task);
                return;
            }
            if (DOWNLOADING.equals(task.getStatus())) {
                download(task);
                return;
            }
            OpenAiVideoClient.RemoteVideo remote = client.retrieve(task.getProviderJobId());
            String status = remoteStatus(remote.status());
            if (FAILED.equals(status)) {
                fail(task.getId(), remote.error(), false);
            } else if (COMPLETED.equals(status)) {
                int updated = mapper.update(null, Wrappers.<VideoGenerationEntity>lambdaUpdate()
                        .eq(VideoGenerationEntity::getId, task.getId())
                        .in(VideoGenerationEntity::getStatus, List.of(QUEUED, IN_PROGRESS))
                        .set(VideoGenerationEntity::getStatus, DOWNLOADING)
                        .set(VideoGenerationEntity::getProgress, 100)
                        .set(VideoGenerationEntity::getErrorMessage, null)
                        .set(VideoGenerationEntity::getUpdatedAt, LocalDateTime.now()));
                if (updated > 0) {
                    task.setStatus(DOWNLOADING);
                    download(task);
                }
            } else {
                mapper.update(null, Wrappers.<VideoGenerationEntity>lambdaUpdate()
                        .eq(VideoGenerationEntity::getId, task.getId())
                        .in(VideoGenerationEntity::getStatus, List.of(QUEUED, IN_PROGRESS))
                        .set(VideoGenerationEntity::getStatus, status)
                        .set(VideoGenerationEntity::getProgress, remote.progress())
                        .set(VideoGenerationEntity::getErrorMessage, null)
                        .set(VideoGenerationEntity::getUpdatedAt, LocalDateTime.now()));
            }
        } catch (RuntimeException ex) {
            log.warn("视频任务推进失败 task={} : {}", task.getId(), ex.getMessage());
            // 查询失败通常是短暂网络问题，不把仍在上游运行的计费任务误标为永久失败。
            mapper.update(null, Wrappers.<VideoGenerationEntity>lambdaUpdate()
                    .eq(VideoGenerationEntity::getId, task.getId())
                    .set(VideoGenerationEntity::getErrorMessage, trimError("状态同步暂时失败：" + ex.getMessage()))
                    .set(VideoGenerationEntity::getUpdatedAt, LocalDateTime.now()));
        }
    }

    private void download(VideoGenerationEntity task) {
        try {
            MediaAssetEntity asset = mediaAssets.storeGenerated(task.getWorkspaceId(),
                    "sora-" + task.getId() + ".mp4", "video/mp4",
                    (output, limit) -> client.download(task.getProviderJobId(), output, limit));
            mapper.update(null, Wrappers.<VideoGenerationEntity>lambdaUpdate()
                    .eq(VideoGenerationEntity::getId, task.getId())
                    .eq(VideoGenerationEntity::getStatus, DOWNLOADING)
                    .set(VideoGenerationEntity::getMediaAssetId, asset.getId())
                    .set(VideoGenerationEntity::getStatus, COMPLETED)
                    .set(VideoGenerationEntity::getProgress, 100)
                    .set(VideoGenerationEntity::getErrorMessage, null)
                    .set(VideoGenerationEntity::getCompletedAt, LocalDateTime.now())
                    .set(VideoGenerationEntity::getUpdatedAt, LocalDateTime.now()));
        } catch (RuntimeException ex) {
            fail(task.getId(), "视频下载失败：" + ex.getMessage(), true);
        }
    }

    private VideoCapabilityDto capability() {
        String note;
        if (!properties.configured()) {
            note = "尚未配置 OpenAI Videos API Key，不能生成真实视频";
        } else if (!java.time.LocalDate.now().isBefore(VideoGenerationProperties.SHUTDOWN_DATE)) {
            note = "OpenAI Videos API 已于 2026-09-24 停用，仅保留历史视频查看";
        } else {
            note = "真实调用 OpenAI Videos API；模型与接口将于 2026-09-24 停用";
        }
        return new VideoCapabilityDto(properties.available(), "OpenAI",
                VideoGenerationProperties.MODELS, VideoGenerationProperties.DURATIONS,
                VideoGenerationProperties.SIZES, VideoGenerationProperties.SHUTDOWN_DATE.toString(), note);
    }

    private void requireAvailable() {
        if (!properties.configured()) {
            throw new BadRequestException("尚未配置真实视频模型，请在 Linux 主节点设置 AGENT_VIDEO_API_KEY");
        }
        if (!properties.available()) {
            throw new BadRequestException("OpenAI Videos API 已于 2026-09-24 停用，不能再创建新任务");
        }
    }

    private void validate(CreateVideoRequest request) {
        if (!VideoGenerationProperties.MODELS.contains(request.model())) {
            throw new BadRequestException("不支持的视频模型：" + request.model());
        }
        if (!VideoGenerationProperties.DURATIONS.contains(request.seconds())) {
            throw new BadRequestException("视频时长只支持 4、8 或 12 秒");
        }
        if (!VideoGenerationProperties.SIZES.contains(request.size())) {
            throw new BadRequestException("不支持的视频画幅：" + request.size());
        }
    }

    private String remoteStatus(String raw) {
        if (!StringUtils.hasText(raw)) throw new BadRequestException("OpenAI 视频任务状态为空");
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "queued" -> QUEUED;
            case "in_progress" -> IN_PROGRESS;
            case "completed" -> COMPLETED;
            case "failed" -> FAILED;
            default -> throw new BadRequestException("OpenAI 返回未知视频状态：" + raw);
        };
    }

    private VideoGenerationEntity require(String workspaceId, String taskId) {
        VideoGenerationEntity task = mapper.selectById(taskId);
        if (task == null || !workspaceId.equals(task.getWorkspaceId())) {
            throw new BadRequestException("视频任务不存在或不属于当前工作区");
        }
        return task;
    }

    private void fail(String taskId, String message, boolean keepProviderJob) {
        var update = Wrappers.<VideoGenerationEntity>lambdaUpdate()
                .eq(VideoGenerationEntity::getId, taskId)
                .set(VideoGenerationEntity::getStatus, FAILED)
                .set(VideoGenerationEntity::getErrorMessage,
                        trimError(StringUtils.hasText(message) ? message : "OpenAI 视频生成失败"))
                .set(VideoGenerationEntity::getUpdatedAt, LocalDateTime.now());
        if (!keepProviderJob) update.set(VideoGenerationEntity::getProviderJobId, null);
        mapper.update(null, update);
    }

    private String trimError(String message) {
        if (!StringUtils.hasText(message)) return "视频生成失败";
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private VideoGenerationDto toDto(VideoGenerationEntity task) {
        String contentUrl = COMPLETED.equals(task.getStatus())
                ? "/api/agent/videos/" + task.getId() + "/content" : null;
        return new VideoGenerationDto(task.getId(), task.getPrompt(), task.getModel(), task.getSeconds(),
                task.getSize(), task.getStatus(), task.getProgress(), task.getAttempt(), task.getMediaAssetId(),
                contentUrl, task.getErrorMessage(), task.getCreatedAt(), task.getUpdatedAt(), task.getCompletedAt());
    }

    public record MediaFile(Path path, String contentType, String fileName) { }
}
