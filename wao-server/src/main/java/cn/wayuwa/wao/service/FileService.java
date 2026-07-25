package cn.wayuwa.wao.service;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.FileBlobEntity;
import cn.wayuwa.wao.dto.FileItemDto;
import cn.wayuwa.wao.mapper.FileBlobMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 通用文件的上传、元数据列表、下载和删除服务。 */
@Service
public class FileService {
    public static final long MAX_BYTES = 50L * 1024 * 1024;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private final FileBlobMapper mapper;
    public FileService(FileBlobMapper mapper) { this.mapper = mapper; }

    /** 列出文件元数据，不把二进制正文加载进内存。 */
    public List<FileItemDto> list() { return mapper.listMeta().stream().map(this::toDto).toList(); }

    /** 上传一个不超过 50MB 的文件并持久化。 */
    public FileItemDto upload(MultipartFile file, String memberId) {
        if (file == null || file.isEmpty()) throw new BadRequestException("请选择要上传的文件");
        if (file.getSize() > MAX_BYTES) throw new BadRequestException("文件超过 50MB，请压缩后再上传");
        FileBlobEntity entity = new FileBlobEntity();
        entity.setId("file-" + UUID.randomUUID());
        entity.setName(safeName(file.getOriginalFilename()));
        entity.setContentType(file.getContentType() == null ? "application/octet-stream" : file.getContentType());
        entity.setByteSize(file.getSize());
        entity.setCreatedBy(memberId == null ? "未知成员" : memberId);
        entity.setCreatedAt(LocalDateTime.now());
        try { entity.setContent(file.getBytes()); }
        catch (IOException ex) { throw new BadRequestException("读取上传文件失败，请重试"); }
        mapper.insert(entity);
        return toDto(entity);
    }

    /** 读取文件正文用于下载。 */
    public FileBlobEntity raw(String id) {
        FileBlobEntity entity = mapper.selectById(id);
        if (entity == null || entity.getContent() == null) throw new NotFoundException("文件不存在：" + id);
        return entity;
    }

    /** 删除一个文件。 */
    public void delete(String id) { if (mapper.deleteById(id) == 0) throw new NotFoundException("文件不存在：" + id); }

    private FileItemDto toDto(FileBlobEntity entity) {
        return new FileItemDto(entity.getId(), entity.getName(), humanSize(entity.getByteSize()), icon(entity.getName()),
                entity.getCreatedAt() == null ? "" : entity.getCreatedAt().format(TIME_FMT), entity.getCreatedBy(),
                "/api/files/" + entity.getId() + "/download");
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
