package cn.wayuwa.wao.agent.service;

import cn.wayuwa.wao.agent.common.BadRequestException;
import cn.wayuwa.wao.agent.config.MediaProperties;
import cn.wayuwa.wao.agent.config.NodeProperties;
import cn.wayuwa.wao.agent.domain.MediaAssetEntity;
import cn.wayuwa.wao.agent.dto.MediaAssetDto;
import cn.wayuwa.wao.agent.mapper.MediaAssetMapper;
import cn.wayuwa.wao.agent.platform.MediaKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 素材上传与定位。
 *
 * <p>文件写在处理节点的本地磁盘，因此记录 nodeId；发布任务必须回到同一节点执行，
 * 否则另一台节点上找不到文件。这是当前没有对象存储时的显式取舍。
 */
@Service
public class MediaAssetService {
    private final SharedAccountService accounts;
    private final MediaAssetMapper mediaMapper;
    private final MediaProperties properties;
    private final NodeProperties node;

    public MediaAssetService(SharedAccountService accounts, MediaAssetMapper mediaMapper,
                             MediaProperties properties, NodeProperties node) {
        this.accounts = accounts;
        this.mediaMapper = mediaMapper;
        this.properties = properties;
        this.node = node;
    }

    /** 保存上传的素材文件并落库。 */
    public MediaAssetDto store(String accountId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BadRequestException("请选择要上传的素材文件");
        if (file.getSize() > properties.limit()) {
            throw new BadRequestException("素材超过上限 " + (properties.limit() / 1024 / 1024) + "MB");
        }
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        String kind = resolveKind(contentType, file.getOriginalFilename());
        String workspaceId = accounts.workspaceId(accountId);

        String id = UUID.randomUUID().toString();
        Path dir = Paths.get(properties.directory(), workspaceId);
        Path target = dir.resolve(id + extension(file.getOriginalFilename()));
        try {
            Files.createDirectories(dir);
            file.transferTo(target.toAbsolutePath());
        } catch (IOException ex) {
            throw new BadRequestException("素材保存失败：" + ex.getMessage());
        }

        MediaAssetEntity entity = new MediaAssetEntity();
        entity.setId(id);
        entity.setWorkspaceId(workspaceId);
        entity.setKind(kind);
        entity.setOriginalName(safeName(file.getOriginalFilename()));
        entity.setContentType(contentType);
        entity.setByteSize(file.getSize());
        entity.setStoragePath(target.toAbsolutePath().toString());
        entity.setNodeId(node.resolved());
        entity.setCreatedAt(LocalDateTime.now());
        mediaMapper.insert(entity);
        return new MediaAssetDto(entity.getId(), entity.getKind(), entity.getOriginalName(),
                entity.getContentType(), entity.getByteSize());
    }

    /** 读取本工作区的素材记录。 */
    public MediaAssetEntity require(String workspaceId, String mediaAssetId) {
        MediaAssetEntity entity = mediaMapper.selectById(mediaAssetId);
        if (entity == null || !workspaceId.equals(entity.getWorkspaceId())) {
            throw new BadRequestException("素材不存在或不属于当前工作区");
        }
        return entity;
    }

    public MediaAssetEntity find(String mediaAssetId) {
        return mediaAssetId == null ? null : mediaMapper.selectById(mediaAssetId);
    }

    private String resolveKind(String contentType, String fileName) {
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (lower.startsWith("video/")) return MediaKind.VIDEO;
        if (lower.startsWith("image/")) return MediaKind.IMAGE;
        String ext = extension(fileName).toLowerCase(Locale.ROOT);
        if (ext.equals(".mp4") || ext.equals(".mov") || ext.equals(".webm")) return MediaKind.VIDEO;
        if (ext.equals(".jpg") || ext.equals(".jpeg") || ext.equals(".png")) return MediaKind.IMAGE;
        throw new BadRequestException("只支持上传视频或图片素材，当前类型：" + contentType);
    }

    private String extension(String fileName) {
        if (!StringUtils.hasText(fileName)) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        String ext = fileName.substring(dot);
        return ext.matches("\\.[A-Za-z0-9]{1,8}") ? ext : "";
    }

    /** 只保留文件名本身，避免上传方通过路径分隔符影响存储位置。 */
    private String safeName(String fileName) {
        if (!StringUtils.hasText(fileName)) return "media";
        String name = Paths.get(fileName.replace('\\', '/')).getFileName().toString();
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }
}
