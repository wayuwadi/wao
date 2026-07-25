package cn.wayuwa.wao.service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.ImageBlobEntity;
import cn.wayuwa.wao.dto.ImageItemDto;
import cn.wayuwa.wao.mapper.ImageBlobMapper;
import com.fasterxml.jackson.databind.JsonNode;
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

    /** 单张图片上限 10MB —— 与前端限制、Nginx client_max_body_size 保持一致。 */
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

    public ImageService(ImageBlobMapper mapper, ContentService content) {
        this.mapper = mapper;
        this.content = content;
    }

    /**
     * 上传一张图片到图床。
     *
     * @param file   上传的图片文件
     * @param source 来源描述，如 “任务 · 整理证件盒”，可为空
     * @return 落库后的图床条目（{@code url} 可直接用于 img 标签）
     * @throws BadRequestException 文件为空、类型不是图片或超过 {@value #MAX_BYTES} 字节时
     */
    public ImageItemDto upload(MultipartFile file, String source) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("请选择要上传的图片");
        }
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!ALLOWED_TYPES.contains(type)) {
            throw new BadRequestException("只支持 PNG / JPEG / GIF / WebP / SVG / BMP 图片");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BadRequestException("图片超过 10MB，请压缩后再上传");
        }

        ImageBlobEntity e = new ImageBlobEntity();
        e.setId("img" + System.currentTimeMillis() + (int) (Math.random() * 1000));
        e.setName(safeName(file.getOriginalFilename()));
        e.setContentType(type);
        e.setByteSize(file.getSize());
        e.setSource(source);
        e.setCreatedAt(LocalDateTime.now());
        try {
            e.setContent(file.getBytes());
        } catch (IOException ex) {
            throw new BadRequestException("读取上传文件失败，请重试");
        }
        mapper.insert(e);
        return toDto(e);
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
