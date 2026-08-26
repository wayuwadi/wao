package cn.wayuwa.wao.agent.service;

import cn.wayuwa.wao.agent.common.BadRequestException;
import cn.wayuwa.wao.agent.common.ConflictException;
import cn.wayuwa.wao.agent.domain.MediaAssetEntity;
import cn.wayuwa.wao.agent.domain.VideoProjectEntity;
import cn.wayuwa.wao.agent.dto.CompleteVideoExportRequest;
import cn.wayuwa.wao.agent.dto.UpdateVideoProjectRequest;
import cn.wayuwa.wao.agent.dto.VideoProjectDto;
import cn.wayuwa.wao.agent.dto.VideoSceneDto;
import cn.wayuwa.wao.agent.mapper.VideoProjectMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 可控视频项目：持久化分镜、乐观锁保存，并且只在真实 WebM 已上传后登记导出。
 */
@Service
public class VideoProjectService {
    private static final TypeReference<List<VideoSceneDto>> SCENE_LIST = new TypeReference<>() { };
    private final SharedAccountService accounts;
    private final VideoProjectMapper projects;
    private final MediaAssetService mediaAssets;
    private final ObjectMapper json;

    public VideoProjectService(SharedAccountService accounts, VideoProjectMapper projects,
                               MediaAssetService mediaAssets, ObjectMapper json) {
        this.accounts = accounts;
        this.projects = projects;
        this.mediaAssets = mediaAssets;
        this.json = json;
    }

    /** 读取当前工作区的视频项目；首次访问时原子创建 180 秒 WAO 功能介绍分镜。 */
    @Transactional
    public VideoProjectDto getOrCreate(String accountId) {
        String workspaceId = accounts.workspaceId(accountId);
        VideoProjectEntity project = findByWorkspace(workspaceId);
        if (project == null) {
            LocalDateTime now = LocalDateTime.now();
            VideoProjectEntity proposed = new VideoProjectEntity();
            proposed.setId(stableId(workspaceId));
            proposed.setWorkspaceId(workspaceId);
            proposed.setTitle("三分钟认识 WAO");
            proposed.setScenesJson(writeScenes(defaultScenes()));
            proposed.setStatus("DRAFT");
            proposed.setVersion(1);
            proposed.setCreatedAt(now);
            proposed.setUpdatedAt(now);
            try {
                projects.insert(proposed);
                project = proposed;
            } catch (DuplicateKeyException ignored) {
                project = findByWorkspace(workspaceId);
            }
        }
        if (project == null) throw new BadRequestException("视频项目初始化失败，请重试");
        return toDto(project);
    }

    /** 保存标题和完整分镜。内容变化会回到草稿状态，但不会删除历史媒体文件。 */
    @Transactional
    public VideoProjectDto save(String accountId, UpdateVideoProjectRequest request) {
        String workspaceId = accounts.workspaceId(accountId);
        VideoProjectEntity current = require(workspaceId);
        validateScenes(request.scenes());
        LocalDateTime now = LocalDateTime.now();
        int changed = projects.update(null, Wrappers.<VideoProjectEntity>lambdaUpdate()
                .eq(VideoProjectEntity::getId, current.getId())
                .eq(VideoProjectEntity::getWorkspaceId, workspaceId)
                .eq(VideoProjectEntity::getVersion, request.version())
                .set(VideoProjectEntity::getTitle, request.title().trim())
                .set(VideoProjectEntity::getScenesJson, writeScenes(request.scenes()))
                .set(VideoProjectEntity::getStatus, "DRAFT")
                .set(VideoProjectEntity::getOutputMediaAssetId, null)
                .set(VideoProjectEntity::getExportedAt, null)
                .set(VideoProjectEntity::getVersion, request.version() + 1)
                .set(VideoProjectEntity::getUpdatedAt, now));
        if (changed != 1) throw new ConflictException("分镜已在其他页面更新，请刷新后再编辑");
        return toDto(require(workspaceId));
    }

    /** 把已经真实上传的 WebM 关联到项目，并记录导出时间。 */
    @Transactional
    public VideoProjectDto completeExport(String accountId, CompleteVideoExportRequest request) {
        String workspaceId = accounts.workspaceId(accountId);
        VideoProjectEntity current = require(workspaceId);
        MediaAssetEntity media = mediaAssets.require(workspaceId, request.mediaAssetId());
        if (!"VIDEO".equals(media.getKind()) || !"video/webm".equalsIgnoreCase(media.getContentType())) {
            throw new BadRequestException("导出结果必须是浏览器真实生成的 WebM 视频");
        }
        LocalDateTime now = LocalDateTime.now();
        int changed = projects.update(null, Wrappers.<VideoProjectEntity>lambdaUpdate()
                .eq(VideoProjectEntity::getId, current.getId())
                .eq(VideoProjectEntity::getWorkspaceId, workspaceId)
                .eq(VideoProjectEntity::getVersion, request.version())
                .set(VideoProjectEntity::getOutputMediaAssetId, media.getId())
                .set(VideoProjectEntity::getStatus, "EXPORTED")
                .set(VideoProjectEntity::getExportedAt, now)
                .set(VideoProjectEntity::getUpdatedAt, now));
        if (changed != 1) throw new ConflictException("分镜在合成期间发生变化，成片已保留为素材，请重新导出最新版本");
        return toDto(require(workspaceId));
    }

    /** 读取当前项目已关联的真实输出文件。 */
    public MediaAssetEntity output(String accountId) {
        String workspaceId = accounts.workspaceId(accountId);
        VideoProjectEntity project = require(workspaceId);
        if (project.getOutputMediaAssetId() == null) throw new BadRequestException("当前分镜还没有已导出的成片");
        return mediaAssets.require(workspaceId, project.getOutputMediaAssetId());
    }

    private VideoProjectEntity require(String workspaceId) {
        VideoProjectEntity project = findByWorkspace(workspaceId);
        if (project == null) throw new BadRequestException("视频项目不存在，请刷新后重试");
        return project;
    }

    private VideoProjectEntity findByWorkspace(String workspaceId) {
        return projects.selectOne(Wrappers.<VideoProjectEntity>lambdaQuery()
                .eq(VideoProjectEntity::getWorkspaceId, workspaceId).last("LIMIT 1"));
    }

    private VideoProjectDto toDto(VideoProjectEntity entity) {
        List<VideoSceneDto> scenes = readScenes(entity.getScenesJson());
        MediaAssetEntity output = mediaAssets.find(entity.getOutputMediaAssetId());
        return new VideoProjectDto(entity.getId(), entity.getTitle(), scenes, totalSeconds(scenes),
                entity.getStatus(), entity.getVersion(), entity.getOutputMediaAssetId(),
                output == null ? null : output.getOriginalName(), output == null ? null : output.getByteSize(),
                output == null ? null : "/api/agent/video-workflow/output", entity.getUpdatedAt(), entity.getExportedAt());
    }

    private void validateScenes(List<VideoSceneDto> scenes) {
        Set<String> ids = new HashSet<>();
        for (VideoSceneDto scene : scenes) {
            if (!ids.add(scene.id())) throw new BadRequestException("分镜标识不能重复：" + scene.id());
        }
        int total = totalSeconds(scenes);
        if (total < 30 || total > 600) throw new BadRequestException("启用分镜总时长必须在 30 到 600 秒之间");
    }

    private int totalSeconds(List<VideoSceneDto> scenes) {
        return scenes.stream().filter(VideoSceneDto::enabled).mapToInt(VideoSceneDto::seconds).sum();
    }

    private List<VideoSceneDto> readScenes(String value) {
        try {
            return json.readValue(value, SCENE_LIST);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("视频分镜数据损坏", ex);
        }
    }

    private String writeScenes(List<VideoSceneDto> scenes) {
        try {
            return json.writeValueAsString(scenes);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("视频分镜序列化失败", ex);
        }
    }

    private String stableId(String workspaceId) {
        return UUID.nameUUIDFromBytes(("wao-agent:video-project:" + workspaceId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private List<VideoSceneDto> defaultScenes() {
        return List.of(
                new VideoSceneDto("meet-wao", "WA ALL IN ONE", "把散落的生活和工作，收进一个地方",
                        "WAO 是一个持续进化的个人数字工作台。项目、待办、知识、节奏与 AI 协作，共用同一套真实数据。", 15, "#6556D8", true),
                new VideoSceneDto("projects", "01 · ORGANIZE", "项目和待办，不再各自失联",
                        "从项目看目标、文档和任务，也能在待办中按状态推进。分享链接把一条需求原样交给协作者。", 22, "#347FC5", true),
                new VideoSceneDto("ai-runs", "02 · DELIVER", "让 AI 工作流真正把事情做完",
                        "选择项目、执行电脑和工具后派发任务。计划、运行、提交、部署与结果都可追溯，而不是只停在一段回答。", 24, "#805AD5", true),
                new VideoSceneDto("knowledge", "03 · REMEMBER", "知识、文件与记忆，随时找得回来",
                        "知识库沉淀可复用内容，文件与图床保留原件，记忆中心按成员隔离长期资料，不用演示数据冒充理解。", 22, "#1F9D7A", true),
                new VideoSceneDto("rhythm", "04 · FOCUS", "每天该做什么，一眼看清",
                        "今日节奏把待办、日历和提醒放进同一条时间线；行动计划让长期目标落到今天可执行的一步。", 22, "#D97706", true),
                new VideoSceneDto("reviews", "05 · REFLECT", "做完之后，还能看见成长轨迹",
                        "周报、人生回顾、聊天复盘和系统报告都基于已保存的真实记录，帮助复盘进展、关系和系统状态。", 24, "#D14D72", true),
                new VideoSceneDto("agent", "06 · CREATE", "从选题到发布，也是一条可控链路",
                        "自媒体 Agent 汇集真实热点、账号定位、文案审核和素材。平台有官方接口才发布，没有就明确导出后人工完成。", 24, "#0F9F8F", true),
                new VideoSceneDto("closing", "WAO · KEEP EVOLVING", "你的数据、流程和 AI，终于在一起",
                        "WAO 仍在迭代，但每项能力都坚持真实数据、人工可控和完整验收。把下一件重要的事，交给 WAO。", 27, "#6556D8", true));
    }
}
