package cn.wayuwa.wao.service;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;

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
        return upload(file, source, null);
    }

    /** 上传图片并记录可空的项目归属。 */
    public ImageItemDto upload(MultipartFile file, String source, String projectId) {
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
        return store(content, type, file.getOriginalFilename(), source, false, projectId);
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
        return store(content, contentType, name, source, deduplicate, null);
    }

    private ImageItemDto store(byte[] content, String contentType, String name, String source, boolean deduplicate,
            String projectId) {
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
        e.setProjectId(projectId);
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

    /** 列出当前成员可见的全局/项目图片；项目列表不混入历史演示条目。 */
    public List<ImageItemDto> list(Set<String> visibleProjectIds, String projectId) {
        List<ImageItemDto> out = new ArrayList<>(mapper.listMeta().stream()
                .filter(entity -> projectId != null
                        ? projectId.equals(entity.getProjectId())
                        : entity.getProjectId() == null || visibleProjectIds.contains(entity.getProjectId()))
                .map(this::toDto).toList());
        if (projectId == null) content.images().forEach(node -> out.add(fromSeed(node)));
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

    /** 小记列表读取轻量预览；原图字节保持不变，放大查看仍走 raw。 */
    public Preview preview(String id) {
        ImageBlobEntity image = raw(id);
        byte[] original = image.getContent();
        String type = image.getContentType();
        if (original.length <= 220_000 || !("image/png".equals(type) || "image/jpeg".equals(type))) {
            return new Preview(type, original);
        }
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
            if (input == null) return new Preview(type, original);
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return new Preview(type, original);
            ImageReader reader = readers.next();
            BufferedImage decoded;
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > 16_000_000L) {
                    return new Preview(type, original);
                }
                decoded = reader.read(0);
            } finally {
                reader.dispose();
            }
            double scale = Math.min(1.0, 768.0 / Math.max(decoded.getWidth(), decoded.getHeight()));
            int width = Math.max(1, (int) Math.round(decoded.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(decoded.getHeight() * scale));
            BufferedImage thumbnail = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = thumbnail.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(decoded, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }
            var writers = ImageIO.getImageWritersByMIMEType("image/jpeg");
            if (!writers.hasNext()) return new Preview(type, original);
            ImageWriter writer = writers.next();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (var imageOutput = ImageIO.createImageOutputStream(output)) {
                writer.setOutput(imageOutput);
                ImageWriteParam options = writer.getDefaultWriteParam();
                options.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                options.setCompressionQuality(0.84f);
                writer.write(null, new IIOImage(thumbnail, null, null), options);
            } finally {
                writer.dispose();
            }
            byte[] bytes = output.toByteArray();
            return bytes.length < original.length ? new Preview("image/jpeg", bytes) : new Preview(type, original);
        } catch (IOException | RuntimeException exception) {
            // Unknown or damaged legacy images keep the existing raw-image behavior.
            return new Preview(type, original);
        }
    }

    public record Preview(String contentType, byte[] content) {}

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
                "/api/images/" + e.getId() + "/raw", tint[0], tint[1], e.getProjectId());
    }

    private static ImageItemDto fromSeed(JsonNode n) {
        return new ImageItemDto(null, text(n, "name"), text(n, "size"), text(n, "time"),
                text(n, "from"), text(n, "url"), text(n, "c1"), text(n, "c2"), null);
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
