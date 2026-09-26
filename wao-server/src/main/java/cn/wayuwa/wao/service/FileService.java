package cn.wayuwa.wao.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.FileBlobEntity;
import cn.wayuwa.wao.dto.FileItemDto;
import cn.wayuwa.wao.dto.FilePreviewDto;
import cn.wayuwa.wao.mapper.FileBlobMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

/** 通用文件的上传、元数据列表、下载和删除服务。 */
@Service
public class FileService {
    public static final long MAX_BYTES = 50L * 1024 * 1024;
    public static final long MAX_PREVIEW_BYTES = 2L * 1024 * 1024;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private final FileBlobMapper mapper;
    private final FileSettingsService settings;
    private final BinaryObjectStore objects;
    public FileService(FileBlobMapper mapper, FileSettingsService settings) {
        this(mapper, settings, new DisabledBinaryObjectStore());
    }

    @Autowired
    public FileService(FileBlobMapper mapper, FileSettingsService settings, BinaryObjectStore objects) {
        this.mapper = mapper;
        this.settings = settings;
        this.objects = objects;
    }

    /** 列出文件元数据，不把二进制正文加载进内存。 */
    public List<FileItemDto> list() { return mapper.listMeta().stream().map(this::toDto).toList(); }

    /** 列出当前成员可见的全局/项目文件；指定项目时只返回该项目。 */
    public List<FileItemDto> list(Set<String> visibleProjectIds, String projectId) {
        return mapper.listMeta().stream()
                .filter(entity -> projectId != null
                        ? projectId.equals(entity.getProjectId())
                        : entity.getProjectId() == null || visibleProjectIds.contains(entity.getProjectId()))
                .map(this::toDto).toList();
    }

    /** 上传一个不超过当前系统设置上限的文件并持久化。 */
    public FileItemDto upload(MultipartFile file, String memberId) {
        return upload(file, memberId, null);
    }

    /** 上传一个文件并记录可空的项目归属。 */
    public FileItemDto upload(MultipartFile file, String memberId, String projectId) {
        validateUpload(file);
        FileBlobEntity entity = new FileBlobEntity();
        entity.setId("file-" + UUID.randomUUID());
        entity.setName(safeName(file.getOriginalFilename()));
        entity.setContentType(file.getContentType() == null ? "application/octet-stream" : file.getContentType());
        entity.setByteSize(file.getSize());
        entity.setCreatedBy(memberId == null ? "未知成员" : memberId);
        entity.setProjectId(projectId);
        entity.setCreatedAt(LocalDateTime.now());
        byte[] bytes;
        try { bytes = file.getBytes(); }
        catch (IOException ex) { throw new BadRequestException("读取上传文件失败，请重试"); }
        entity.setByteSize((long) bytes.length);
        entity.setContentHash(ImageService.sha256(bytes));
        if (objects.writesEnabled()) {
            String key = "files/" + entity.getId() + "/" + entity.getContentHash();
            try {
                objects.put(key, bytes, entity.getContentType());
                entity.setObjectKey(key);
            } catch (RuntimeException exception) {
                if (!objects.retainMysqlCopy()) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "文件存储暂不可用", exception);
                }
            }
        }
        if (!objects.writesEnabled() || objects.retainMysqlCopy()) {
            entity.setContent(bytes);
        }
        mapper.insert(entity);
        return toDto(entity);
    }

    /** 替换文件正文和上传文件名，保留 id、展示名、项目归属及创建信息。 */
    public FileItemDto replace(String id, MultipartFile file) {
        metadata(id);
        validateUpload(file);
        byte[] content;
        try { content = file.getBytes(); }
        catch (IOException ex) { throw new BadRequestException("读取上传文件失败，请重试"); }
        int byteSize = content.length;
        String contentHash = ImageService.sha256(content);
        String type = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        String key = null;
        if (objects.writesEnabled()) {
            String candidate = "files/" + id + "/" + contentHash;
            try {
                objects.put(candidate, content, type);
                key = candidate;
            } catch (RuntimeException exception) {
                if (!objects.retainMysqlCopy()) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "文件存储暂不可用", exception);
                }
            }
            if (!objects.retainMysqlCopy()) content = null;
        }
        if (mapper.replaceContent(id, safeName(file.getOriginalFilename()),
                type, byteSize, content, key, contentHash) == 0) {
            throw new NotFoundException("文件不存在：" + id);
        }
        return meta(id);
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BadRequestException("请选择要上传的文件");
        int limitMb = settings.get().fileMaxMb();
        if (file.getSize() > limitMb * 1024L * 1024L) {
            throw new BadRequestException("文件超过 " + limitMb + "MB，请压缩后再上传");
        }
    }

    /** 读取文件正文用于下载。 */
    public FileBlobEntity raw(String id) {
        FileBlobEntity entity = mapper.findMeta(id);
        if (entity == null) throw new NotFoundException("文件不存在：" + id);
        if (entity.getObjectKey() != null && !entity.getObjectKey().isBlank()) {
            try {
                byte[] fetched = objects.get(entity.getObjectKey());
                if ((entity.getByteSize() != null && fetched.length != entity.getByteSize())
                        || (entity.getContentHash() != null
                            && !entity.getContentHash().equals(ImageService.sha256(fetched)))) {
                    throw new IllegalStateException("Object bytes differ from file metadata");
                }
                entity.setContent(fetched);
                return entity;
            }
            catch (RuntimeException exception) {
                FileBlobEntity retained = mapper.selectById(id);
                if (retained == null || retained.getContent() == null) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "文件存储暂不可用", exception);
                }
                return retained;
            }
        }
        entity = mapper.selectById(id);
        if (entity == null || entity.getContent() == null) throw new NotFoundException("文件不存在：" + id);
        return entity;
    }

    /** Read an inclusive media range from S3 without downloading the full object on each seek. */
    public byte[] readRange(FileBlobEntity metadata, long start, long end) {
        Long size = metadata.getByteSize();
        if (size == null || start < 0 || end < start || end >= size) {
            throw new IllegalArgumentException("File range is out of bounds");
        }
        String key = metadata.getObjectKey();
        if (key != null && !key.isBlank()) {
            try {
                byte[] part = objects.getRange(key, start, end);
                if (part.length != end - start + 1) throw new IllegalStateException("Incomplete object range");
                return part;
            } catch (RuntimeException ignored) {
                // A retained MySQL copy, or a full S3 read, can still serve this request.
            }
        }
        byte[] full = raw(metadata.getId()).getContent();
        if (full.length != size) throw new IllegalStateException("File length changed during range read");
        return Arrays.copyOfRange(full, Math.toIntExact(start), Math.toIntExact(end + 1));
    }

    /**
     * 只读元数据（文件名 / 大小 / 时间），供公开的下载确认页展示，不把二进制正文拖进内存。
     *
     * @param id 文件 id
     * @return 文件元数据
     * @throws NotFoundException 文件不存在时
     */
    public FileItemDto meta(String id) {
        return toDto(metadata(id));
    }

    /** 只读取单个文件的元数据，供权限判断使用，不把二进制正文载入内存。 */
    public FileBlobEntity metadata(String id) {
        FileBlobEntity entity = mapper.findMeta(id);
        if (entity == null) throw new NotFoundException("文件不存在：" + id);
        return entity;
    }

    /** 修改文件在 WAO 内的展示名称；原始文件名和正文均不变。 */
    public FileItemDto renameDisplayName(String id, String displayName) {
        String normalized = displayName == null ? "" : displayName.trim();
        if (normalized.isEmpty() || normalized.length() > 255) {
            throw new BadRequestException("文件展示名称须为 1～255 个字符");
        }
        if (mapper.updateDisplayName(id, normalized) == 0) {
            throw new NotFoundException("文件不存在：" + id);
        }
        return toDto(metadata(id));
    }

    /** 读取 UTF-8 Markdown 正文供登录后的站内安全渲染，不执行上传内容。 */
    public FilePreviewDto preview(String id) {
        FileBlobEntity entity = raw(id);
        String lower = entity.getName() == null ? "" : entity.getName().toLowerCase(Locale.ROOT);
        String type = entity.getContentType() == null ? "" : entity.getContentType().toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".md") || lower.endsWith(".markdown") || type.equals("text/markdown"))) {
            throw new BadRequestException("当前只支持 Markdown 文件站内预览");
        }
        if (entity.getContent().length > MAX_PREVIEW_BYTES) {
            throw new BadRequestException("Markdown 文件超过 2MB，请下载后查看");
        }
        try {
            String content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(entity.getContent())).toString();
            return new FilePreviewDto(entity.getId(), entity.getName(), entity.getContentType(), content);
        } catch (CharacterCodingException exception) {
            throw new BadRequestException("Markdown 文件必须使用 UTF-8 编码");
        }
    }

    /** 删除一个文件。 */
    public void delete(String id) { if (mapper.deleteById(id) == 0) throw new NotFoundException("文件不存在：" + id); }

    private FileItemDto toDto(FileBlobEntity entity) {
        return new FileItemDto(entity.getId(), entity.getName(), entity.getDisplayName(), entity.getContentType(),
                humanSize(entity.getByteSize()), icon(entity.getName()),
                entity.getCreatedAt() == null ? "" : entity.getCreatedAt().format(TIME_FMT), entity.getCreatedBy(),
                "/api/files/" + entity.getId() + "/download",
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString(), entity.getProjectId());
    }
    private static String safeName(String original) {
        if (original == null || original.isBlank()) return "未命名文件";
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }
    private static String icon(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".pdf")) return "ri-file-pdf-2-line";
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return "ri-file-word-2-line";
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return "ri-file-excel-2-line";
        if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z")) return "ri-file-zip-line";
        return "ri-file-line";
    }
    private static String humanSize(Long value) {
        long bytes = value == null ? 0 : value;
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return Math.round(bytes / 1024.0) + " KB";
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
