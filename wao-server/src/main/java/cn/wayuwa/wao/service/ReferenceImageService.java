package cn.wayuwa.wao.service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.dto.ReferenceImageDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** 私有参考图库。图片正文仅在成员鉴权或精确 Runner 领取后读取。 */
@Service
public class ReferenceImageService {
    private final JdbcTemplate jdbc;

    public ReferenceImageService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 当前成员按创建时间倒序读取图片墙；不把二进制带入列表。 */
    public List<ReferenceImageDto> list(String memberId) {
        return jdbc.query("SELECT id,name,content_type,byte_size,created_at FROM reference_image "
                + "WHERE member_id=? ORDER BY created_at DESC,id DESC LIMIT 500", (rs, i) ->
                dto(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getTimestamp(5).toLocalDateTime()), memberId);
    }

    /** 不要求用户填写表单：只接受真实图片字节并存下原文件名。 */
    @Transactional
    public ReferenceImageDto upload(String memberId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BadRequestException("请选择截图");
        if (file.getSize() > ImageService.MAX_BYTES) throw new BadRequestException("单张图片不能超过 10MB");
        byte[] bytes;
        try { bytes = file.getBytes(); }
        catch (IOException ex) { throw new BadRequestException("读取截图失败，请重试"); }
        String type = detectedType(bytes);
        String hash = sha256(bytes);
        List<ReferenceImageDto> existing = findByHash(memberId, hash);
        if (!existing.isEmpty()) return existing.get(0);
        String name = file.getOriginalFilename() == null ? "截图" : file.getOriginalFilename()
                .replace('\\', '/').replaceAll("^.*/", "").trim();
        if (name.isBlank()) name = "截图";
        if (name.length() > 200) name = name.substring(name.length() - 200);
        String id = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();
        try {
            jdbc.update("INSERT INTO reference_image(id,member_id,name,content_type,byte_size,content_hash,content,created_at) "
                    + "VALUES(?,?,?,?,?,?,?,?)", id, memberId, name, type, bytes.length, hash, bytes, now);
        } catch (DuplicateKeyException exception) {
            List<ReferenceImageDto> winner = findByHash(memberId, hash);
            if (winner.isEmpty()) throw exception;
            return winner.get(0);
        }
        return dto(id, name, type, bytes.length, now);
    }

    private List<ReferenceImageDto> findByHash(String memberId, String hash) {
        return jdbc.query("SELECT id,name,content_type,byte_size,created_at FROM reference_image "
                + "WHERE member_id=? AND content_hash=? LIMIT 1", (rs, i) ->
                dto(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getTimestamp(5).toLocalDateTime()), memberId, hash);
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 不可用", ex); }
    }

    /** 读取一张当前成员拥有的原图；其他成员的 ID 统一表现为不存在。 */
    public Content raw(String memberId, String id) {
        List<Content> found = jdbc.query("SELECT content_type,content FROM reference_image "
                + "WHERE id=? AND member_id=?", (rs, i) -> new Content(rs.getString(1), rs.getBytes(2)),
                id, memberId);
        if (found.isEmpty()) throw new NotFoundException("参考图不存在");
        return found.get(0);
    }

    /** AI 提问前验证所选图片属于当前成员。 */
    public void requireOwned(String memberId, List<String> ids) {
        for (String id : ids) {
            if (id == null || !id.matches("[a-f0-9]{32}")) throw new BadRequestException("参考图 ID 无效");
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM reference_image WHERE id=? AND member_id=?",
                    Integer.class, id, memberId);
            if (count == null || count == 0) throw new NotFoundException("参考图不存在");
        }
    }

    private static ReferenceImageDto dto(String id, String name, String type, long size, LocalDateTime time) {
        return new ReferenceImageDto(id, name, type, size, time, "/api/reference-images/" + id + "/raw");
    }

    private static String detectedType(byte[] b) {
        if (b.length >= 8 && u(b[0]) == 137 && u(b[1]) == 80 && u(b[2]) == 78 && u(b[3]) == 71
                && u(b[4]) == 13 && u(b[5]) == 10 && u(b[6]) == 26 && u(b[7]) == 10) return "image/png";
        if (b.length >= 3 && u(b[0]) == 255 && u(b[1]) == 216 && u(b[2]) == 255) return "image/jpeg";
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F'
                && b[3] == '8' && (b[4] == '7' || b[4] == '9') && b[5] == 'a') return "image/gif";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        throw new BadRequestException("只支持 PNG、JPEG、GIF 或 WebP 截图");
    }

    private static int u(byte b) { return b & 0xff; }

    public record Content(String contentType, byte[] bytes) { }
}
