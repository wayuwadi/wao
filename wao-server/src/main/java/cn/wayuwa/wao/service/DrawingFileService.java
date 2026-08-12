package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.DrawingFileEntity;
import cn.wayuwa.wao.domain.DrawingFileVersionEntity;
import cn.wayuwa.wao.dto.CreateDrawingFileRequest;
import cn.wayuwa.wao.dto.DrawingFileDto;
import cn.wayuwa.wao.dto.DrawingFileSummaryDto;
import cn.wayuwa.wao.dto.DrawingFileVersionDto;
import cn.wayuwa.wao.dto.RenameDrawingFileRequest;
import cn.wayuwa.wao.dto.SaveDrawingFileRequest;
import cn.wayuwa.wao.mapper.DrawingFileMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 按成员归属管理 Excalidraw 草图文件。 */
@Service
public class DrawingFileService {

    public static final String DEFAULT_TITLE = "未命名草图";
    public static final String EMPTY_SCENE = "{\"type\":\"excalidraw\",\"version\":2,\"source\":\"https://wao.wayuwa.cn\",\"elements\":[],\"appState\":{},\"files\":{}}";

    private final DrawingFileMapper mapper;
    private final ObjectMapper json;

    public DrawingFileService(DrawingFileMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    /** 列出当前成员的草图元数据，不读取大体积场景。 */
    public List<DrawingFileSummaryDto> list(String memberId) {
        return mapper.listOwned(memberId).stream().map(this::summary).toList();
    }

    /** 新建当前成员的空草图。 */
    @Transactional
    public DrawingFileDto create(CreateDrawingFileRequest request, String memberId) {
        LocalDateTime now = LocalDateTime.now();
        DrawingFileEntity entity = new DrawingFileEntity();
        entity.setId("dr" + UUID.randomUUID().toString().replace("-", "").substring(0, 28));
        entity.setOwnerMemberId(memberId);
        entity.setTitle(normalizeTitle(request == null ? null : request.title(), true));
        entity.setSceneJson(EMPTY_SCENE);
        entity.setRevision(1);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        return dto(entity);
    }

    /** 读取当前成员的一份草图；他人文件按不存在处理。 */
    public DrawingFileDto get(String id, String memberId) {
        return dto(required(id, memberId));
    }

    /**
     * 使用乐观版本保存画布；旧版本不得覆盖其它页面已保存的新内容。
     *
     * @return 保存后的新版本快照
     */
    @Transactional
    public DrawingFileDto save(String id, SaveDrawingFileRequest request, String memberId) {
        DrawingFileEntity before = required(id, memberId);
        validateScene(request.sceneJson());
        if (sameScene(before.getSceneJson(), request.sceneJson())) return dto(before);
        LocalDateTime now = LocalDateTime.now();
        int changed = mapper.saveScene(id, memberId, request.sceneJson(), request.revision(), now);
        if (changed == 0) {
            DrawingFileEntity latest = mapper.findOwned(id, memberId);
            if (latest == null) throw notFound();
            if (sameScene(latest.getSceneJson(), request.sceneJson())) return dto(latest);
            throw new ConflictException("草图已在其它页面被修改，当前草稿已保留，请重新加载后处理");
        }
        DrawingFileEntity saved = required(id, memberId);
        recordVersion(saved);
        return dto(saved);
    }

    /** 列出当前成员可恢复的最近三个真实内容版本。 */
    public List<DrawingFileVersionDto> versions(String id, String memberId) {
        required(id, memberId);
        return mapper.listVersions(id, memberId).stream()
                .map(version -> new DrawingFileVersionDto(version.getRevision(), version.getCreatedAt()))
                .toList();
    }

    /**
     * 把指定历史场景恢复为一个新的单调递增版本，不回退乐观锁版本号。
     *
     * @return 恢复后新生成的当前版本
     */
    @Transactional
    public DrawingFileDto restore(String id, int historicalRevision, int expectedRevision, String memberId) {
        DrawingFileVersionEntity historical = mapper.findVersion(id, historicalRevision, memberId);
        if (historical == null) throw new NotFoundException("草图历史版本不存在");
        return save(id, new SaveDrawingFileRequest(historical.getSceneJson(), expectedRevision), memberId);
    }

    /** 重命名当前成员的草图文件。 */
    public DrawingFileSummaryDto rename(String id, RenameDrawingFileRequest request, String memberId) {
        String title = normalizeTitle(request.title(), false);
        if (mapper.renameOwned(id, memberId, title, LocalDateTime.now()) == 0) throw notFound();
        return summary(required(id, memberId));
    }

    /** 删除当前成员的草图文件。 */
    public void delete(String id, String memberId) {
        if (mapper.deleteOwned(id, memberId) == 0) throw notFound();
    }

    private DrawingFileEntity required(String id, String memberId) {
        DrawingFileEntity entity = mapper.findOwned(id, memberId);
        if (entity == null) throw notFound();
        return entity;
    }

    private String normalizeTitle(String value, boolean defaultWhenBlank) {
        String title = value == null ? "" : value.trim();
        if (title.isEmpty() && defaultWhenBlank) return DEFAULT_TITLE;
        if (title.isEmpty() || title.length() > 128) throw new BadRequestException("草图名称须为 1～128 个字符");
        return title;
    }

    private void validateScene(String sceneJson) {
        if (sceneJson.getBytes(StandardCharsets.UTF_8).length > 50_000_000) {
            throw new BadRequestException("画布内容超过 50MB");
        }
        try {
            JsonNode root = json.readTree(sceneJson);
            if (root == null || !root.isObject() || !root.path("elements").isArray()
                    || !root.path("appState").isObject()
                    || (root.has("files") && !root.path("files").isObject())) {
                throw new BadRequestException("画布内容不是完整的 Excalidraw 场景");
            }
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("画布内容不是有效 JSON");
        }
    }

    private boolean sameScene(String left, String right) {
        try {
            JsonNode leftRoot = json.readTree(left);
            JsonNode rightRoot = json.readTree(right);
            return leftRoot.path("elements").equals(rightRoot.path("elements"))
                    && backgroundColor(leftRoot).equals(backgroundColor(rightRoot))
                    && normalizedFiles(leftRoot).equals(normalizedFiles(rightRoot));
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    private String backgroundColor(JsonNode root) {
        return root.path("appState").path("viewBackgroundColor").asText("#ffffff");
    }

    private JsonNode normalizedFiles(JsonNode root) {
        JsonNode files = root.path("files");
        return files.isMissingNode() ? json.createObjectNode() : files;
    }

    private void recordVersion(DrawingFileEntity saved) {
        mapper.insertVersion(saved.getId(), saved.getRevision(), saved.getSceneJson(), saved.getUpdatedAt());
        mapper.pruneVersions(saved.getId());
    }

    private DrawingFileSummaryDto summary(DrawingFileEntity entity) {
        return new DrawingFileSummaryDto(entity.getId(), entity.getTitle(), entity.getRevision(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private DrawingFileDto dto(DrawingFileEntity entity) {
        return new DrawingFileDto(entity.getId(), entity.getTitle(), entity.getSceneJson(), entity.getRevision(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private NotFoundException notFound() {
        return new NotFoundException("草图文件不存在");
    }
}
