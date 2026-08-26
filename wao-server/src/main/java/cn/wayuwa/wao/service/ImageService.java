package cn.wayuwa.wao.service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.ImageBlobEntity;
import cn.wayuwa.wao.dto.ImageItemDto;
import cn.wayuwa.wao.mapper.ImageBlobMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 图床服务：上传、列出与取图。
 *
 * <p>图片正文存 MySQL 的 {@code image_blob}（见 V11 migration）。主从两个节点的
 * wao-server 都连主节点的 MySQL，所以图床实际就落在主节点上，且容器重建不丢图。
 */
@Service
public class ImageService {

    /** 单张图片基础设施硬上限 10MB；用户主动上传可在设置中进一步调低。 */
    public static final long MAX_BYTES = 10L * 1024 * 1024;

    private static final Set<String> ALLOWED_TYPES =
            Set.of("image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml", "image/bmp");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    /** 列表缩略图的渐变配色，按 id 散列取一组，纯展示用。 */
    private static final String[][] TINTS = {
            {"#E8ECFA", "#DDE3F6"}, {"#EAF4EE", "#DEEEE5"}, {"#F7EFE2", "#F0E5D0"},
            {"#F3ECF8", "#EAE0F2"}, {"#FDEEEE", "#F6E2E2"}, {"#E9F1F7", "#DCE9F2"},
    };

    private final ImageBlobMapper mapper;
    private final ContentService content;
    private final FileSettingsService settings;

    public ImageService(ImageBlobMapper mapper, ContentService content, FileSettingsService settings) {
        this.mapper = mapper;
        this.content = content;
        this.settings = settings;
    }

    /**
     * 上传一张图片到图床。
     *
     * @param file   上传的图片文件
     * @param source 来源描述，如 “任务 · 整理证件盒”，可为空
     * @return 落库后的图床条目（{@code url} 可直接用于 img 标签）
     * @throws BadRequestException 文件为空、类型不是图片或超过当前用户上传设置时
     */
    public ImageItemDto upload(MultipartFile file, String source) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("请选择要上传的图片");
        }
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!ALLOWED_TYPES.contains(type)) {
            throw new BadRequestException("只支持 PNG / JPEG / GIF / WebP / SVG / BMP 图片");
        }
        int limitMb = settings.get().imageMaxMb();
        if (file.getSize() > limitMb * 1024L * 1024L) {
            throw new BadRequestException("图片超过 " + limitMb + "MB，请压缩后再上传");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw new BadRequestException("读取上传文件失败，请重试");
        }
        return store(content, type, file.getOriginalFilename(), source);
    }

    /**
     * 直接存一份图片字节到图床（供服务端自己抓来的图用，如飞书群聊里的截图）。
     *
     * @param content     图片字节
     * @param contentType MIME 类型，识别不出时按 PNG 处理
     * @param name        文件名
     * @param source      来源描述，如 “飞书群聊 · 研发群”
     * @return 落库后的图床条目
     * @throws BadRequestException 内容为空或超过 {@value #MAX_BYTES} 字节时
     */
    public ImageItemDto store(byte[] content, String contentType, String name, String source) {
        return store(content, contentType, name, source, false);
    }

    /**
     * 存一份图片字节到图床，可选按内容去重。
     *
     * @param content     图片字节
     * @param contentType MIME 类型，识别不出时按 PNG 处理
     * @param name        文件名
     * @param source      来源描述，如 “飞书群聊 · 研发群”
     * @param deduplicate 为 true 时，正文完全相同的图片直接复用已有记录，不再存一份
     * @return 落库后（或已存在）的图床条目
     * @throws BadRequestException 内容为空或超过 {@value #MAX_BYTES} 字节时
     */
    public ImageItemDto store(byte[] content, String contentType, String name, String source, boolean deduplicate) {
        if (content == null || content.length == 0) {
            throw new BadRequestException("图片内容为空");
        }
        if (content.length > MAX_BYTES) {
            throw new BadRequestException("图片超过 10MB，已跳过");
        }
        String type = contentType == null ? "" : contentType.toLowerCase();
        if (!ALLOWED_TYPES.contains(type)) {
            type = "image/png";
        }
        String hash = sha256(content);
        if (deduplicate) {
            // 摘要之外还比字节数，给 SHA-256 再上一道保险。
            ImageBlobEntity same = mapper.findByContentHash(hash, content.length);
            if (same != null) {
                return toDto(same);
            }
        }

        ImageBlobEntity e = new ImageBlobEntity();
        e.setId("img" + System.currentTimeMillis() + (int) (Math.random() * 1000));
        e.setName(safeName(name));
        e.setContentType(type);
        e.setByteSize((long) content.length);
        e.setSource(source);
        e.setContentHash(hash);
        e.setDedupeKey(deduplicate ? hash : null);
        e.setCreatedAt(LocalDateTime.now());
        e.setContent(content);
        try {
            mapper.insert(e);
        } catch (DuplicateKeyException exception) {
            if (!deduplicate) throw exception;
            ImageBlobEntity winner = mapper.findByDedupeKey(hash);
            if (winner == null) throw exception;
            return toDto(winner);
        }
        return toDto(e);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /**
     * 列出图床全部图片：上传的图在前（按时间倒序），随后是内置的演示数据。
     *
     * @return 图床条目列表
     */
    public List<ImageItemDto> list() {
        List<ImageItemDto> out = new ArrayList<>(mapper.listMeta().stream().map(this::toDto).toList());
        content.images().forEach(node -> out.add(fromSeed(node)));
        return out;
    }

    /**
     * 读取图片正文（供 {@code GET /api/images/{id}/raw} 直出）。
     *
     * @param id 图片 id
     * @return 图片实体，含二进制正文
     * @throws NotFoundException 图片不存在时
     */
    public ImageBlobEntity raw(String id) {
        ImageBlobEntity e = mapper.selectById(id);
        if (e == null || e.getContent() == null) {
            throw new NotFoundException("图片不存在：" + id);
        }
        return e;
    }

    /**
     * 删除一张上传的图片。
     *
     * @param id 图片 id
     */
    public void delete(String id) {
        mapper.deleteById(id);
    }

    private ImageItemDto toDto(ImageBlobEntity e) {
        String[] tint = TINTS[Math.floorMod(e.getId().hashCode(), TINTS.length)];
        return new ImageItemDto(
                e.getId(), e.getName(), humanSize(e.getByteSize() == null ? 0 : e.getByteSize()),
                e.getCreatedAt() == null ? "" : e.getCreatedAt().format(TIME_FMT),
                e.getSource() == null ? "上传" : e.getSource(),
                "/api/images/" + e.getId() + "/raw", tint[0], tint[1]);
    }

    private static ImageItemDto fromSeed(JsonNode n) {
        return new ImageItemDto(null, text(n, "name"), text(n, "size"), text(n, "time"),
                text(n, "from"), text(n, "url"), text(n, "c1"), text(n, "c2"));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null ? "" : v.asText();
    }

    /** 去掉路径分隔符，避免文件名把目录结构带进展示层。 */
    private static String safeName(String original) {
        if (original == null || original.isBlank()) {
            return "图片.png";
        }
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return Math.round(bytes / 1024.0) + " KB";
        }
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
