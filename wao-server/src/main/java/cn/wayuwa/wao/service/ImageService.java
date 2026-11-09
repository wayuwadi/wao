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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

/**
 * 图床服务：上传、列出与取图。
 *
 * <p>历史正文仍可从 MySQL 读取；新正文可存私有 S3 对象，元数据和旧 URL 保留在 MySQL。
 */
@Service
public class ImageService {
    @org.springframework.beans.factory.annotation.Autowired
    private NoteAssetPrivacyService knowledgeAssets;
    private void requireKnowledgeAsset(String id) { if (knowledgeAssets != null) knowledgeAssets.requireRead("images", id); }
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    @org.springframework.context.annotation.Lazy
    private NoteOriginalService noteOriginals;

    private List<ImageBlobEntity> visibleMetadata(List<ImageBlobEntity> metadata) {
        if (metadata.isEmpty()) return List.of();
        var hidden = knowledgeAssets == null ? java.util.Set.<String>of() : knowledgeAssets.hiddenAssets("images");
        return metadata.stream().filter(item -> !hidden.contains(item.getId()) && !"任务评论".equals(item.getSource())).toList();
    }

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
    private final BinaryObjectStore objects;
    private final ImagePreviewCache previewCache = new ImagePreviewCache();
    private final Object[] previewLocks = java.util.stream.IntStream.range(0, 16)
            .mapToObj(ignored -> new Object()).toArray();

    public ImageService(ImageBlobMapper mapper, ContentService content, FileSettingsService settings) {
        this(mapper, content, settings, new DisabledBinaryObjectStore());
    }

    @Autowired
    public ImageService(ImageBlobMapper mapper, ContentService content, FileSettingsService settings,
            BinaryObjectStore objects) {
        this.mapper = mapper;
        this.content = content;
        this.settings = settings;
        this.objects = objects;
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
        if (objects.writesEnabled()) {
            String key = "images/" + e.getId() + "/" + hash;
            try {
                objects.put(key, content, type);
                e.setObjectKey(key);
                e.setObjectContentType(type);
                e.setObjectByteSize((long) content.length);
                e.setObjectHash(hash);
            } catch (RuntimeException exception) {
                if (!objects.retainMysqlCopy()) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "图片存储暂不可用", exception);
                }
            }
            if (e.getObjectKey() != null) {
                Preview compressed = createPreview(type, content);
                if (compressed.content() != content) {
                    String previewKey = "previews/" + e.getId() + "/" + sha256(compressed.content());
                    try {
                        objects.put(previewKey, compressed.content(), compressed.contentType());
                        e.setPreviewKey(previewKey);
                    } catch (RuntimeException ignored) { /* The original can produce a preview on demand. */ }
                }
            }
        }
        if (!objects.writesEnabled() || objects.retainMysqlCopy()) {
            e.setContent(content);
        }
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

    static String sha256(byte[] content) {
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
        List<ImageItemDto> out = new ArrayList<>(visibleMetadata(mapper.listMeta()).stream().map(this::toDto).toList());
        content.images().forEach(node -> out.add(fromSeed(node)));
        return out;
    }

    /** 列出当前成员可见的全局/项目图片；项目列表不混入历史演示条目。 */
    public List<ImageItemDto> list(Set<String> visibleProjectIds, String projectId) {
        List<ImageItemDto> out = new ArrayList<>(visibleMetadata(mapper.listVisibleMeta(visibleProjectIds, projectId)).stream()
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
        requireKnowledgeAsset(id);
        return rawSharedSnapshot(id);
    }

    /** Only NotePublicShareService may use this after validating its token and image membership. */
    ImageBlobEntity rawSharedSnapshot(String id) {
        ImageBlobEntity e = mapper.findMeta(id);
        if (e == null) {
            throw new NotFoundException("图片不存在：" + id);
        }
        if (e.getObjectKey() != null && !e.getObjectKey().isBlank()) {
            try {
                byte[] fetched = objects.get(e.getObjectKey());
                Long expectedSize = e.getObjectByteSize() == null ? e.getByteSize() : e.getObjectByteSize();
                String expectedHash = e.getObjectHash() == null ? e.getContentHash() : e.getObjectHash();
                if ((expectedSize != null && fetched.length != expectedSize)
                        || (expectedHash != null && !expectedHash.equals(sha256(fetched)))) {
                    throw new IllegalStateException("Object bytes differ from image metadata");
                }
                e.setContent(fetched);
                if (e.getObjectContentType() != null) e.setContentType(e.getObjectContentType());
                return e;
            } catch (RuntimeException exception) {
                ImageBlobEntity original = mapper.selectById(id);
                if (original == null || original.getContent() == null) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "图片存储暂不可用", exception);
                }
                return original;
            }
        }
        e = mapper.selectById(id);
        if (e == null || e.getContent() == null) throw new NotFoundException("图片不存在：" + id);
        return e;
    }

    /** Metadata for access checks without loading the original bytes. */
    public ImageBlobEntity metadata(String id) {
        requireKnowledgeAsset(id);
        ImageBlobEntity entity = mapper.findMeta(id);
        if (entity == null) throw new NotFoundException("图片不存在：" + id);
        return entity;
    }

    /** 小记列表读取轻量预览；原图字节保持不变，放大查看仍走 raw。 */
    public Preview preview(String id) {
        // Always recheck permissions and existence before consulting process-local cached bytes.
        ImageBlobEntity meta = metadata(id);
        ImagePreviewCache.Key key = ImagePreviewCache.Key.of(meta);
        Preview cached = previewCache.get(key);
        if (cached != null) return cached;
        // Coalesce same-image decode bursts without serializing every thumbnail in the gallery.
        synchronized (previewLocks[Math.floorMod(id.hashCode(), previewLocks.length)]) {
            cached = previewCache.get(key);
            if (cached != null) return cached;
            Preview preview = loadPreview(id, meta);
            previewCache.put(key, preview);
            return preview;
        }
    }

    private Preview loadPreview(String id, ImageBlobEntity meta) {
        if (meta.getPreviewKey() != null && !meta.getPreviewKey().isBlank()) {
            try {
                byte[] fetched = objects.get(meta.getPreviewKey());
                if (!meta.getPreviewKey().endsWith("/" + sha256(fetched))) {
                    throw new IllegalStateException("Preview checksum mismatch");
                }
                // Preserve immutable key/checksum conventions used by backup and restore tools.
                boolean png = fetched.length >= 8 && fetched[0] == (byte) 0x89 && fetched[1] == 'P'
                        && fetched[2] == 'N' && fetched[3] == 'G' && fetched[4] == 13
                        && fetched[5] == 10 && fetched[6] == 26 && fetched[7] == 10;
                return new Preview(png ? "image/png" : "image/jpeg", fetched);
            } catch (RuntimeException ignored) { /* Fall back to the original. */ }
        }
        ImageBlobEntity image = rawSharedSnapshot(id);
        return createPreview(image.getContentType(), image.getContent());
    }

    private static Preview createPreview(String type, byte[] original) {
        return ResourceImagePreview.create(type, original);
    }

    public record Preview(String contentType, byte[] content) {}

    /**
     * 删除一张上传的图片。
     *
     * @param id 图片 id
     */
    @org.springframework.transaction.annotation.Transactional
    public void delete(String id) {
        requireKnowledgeAsset(id);
        if(noteOriginals!=null)noteOriginals.requireAssetUnused("images",id);
        mapper.deleteById(id);
        previewCache.invalidate(id);
    }

    private ImageItemDto toDto(ImageBlobEntity e) {
        String[] tint = TINTS[Math.floorMod(e.getId().hashCode(), TINTS.length)];
        return new ImageItemDto(
                e.getId(), e.getName(), humanSize(e.getByteSize() == null ? 0 : e.getByteSize()),
                e.getCreatedAt() == null ? "" : e.getCreatedAt().format(TIME_FMT),
                e.getSource() == null ? "上传" : e.getSource(),
                "/api/images/" + e.getId() + "/raw", tint[0], tint[1], e.getProjectId(), e.getByteSize(),
                e.getContentType(), e.getCreatedAt() == null ? null : e.getCreatedAt().toString(),
                "/api/images/" + e.getId() + "/preview");
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
