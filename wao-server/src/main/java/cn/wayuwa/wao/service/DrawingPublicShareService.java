package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.mapper.DrawingPublicShareMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DrawingPublicShareService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern TOKEN = Pattern.compile("ds_[A-Za-z0-9_-]{43}");
    private static final Pattern INLINE_IMAGE = Pattern.compile(
            "^data:image/(?:png|jpeg|gif|webp|svg\\+xml|bmp|avif|x-icon);base64,");
    private final DrawingPublicShareMapper mapper;
    private final ObjectMapper json;

    public record Entry(String id, Instant createdAt, Instant expiresAt) {}
    public record Created(String id, Instant createdAt, Instant expiresAt, String token) {}
    public record PublicScene(String title, int revision, Instant updatedAt, Instant expiresAt, ObjectNode scene) {}

    public DrawingPublicShareService(DrawingPublicShareMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Transactional
    public List<Entry> list(String ownerId, String drawingId) {
        requirePersonalDrawing(drawingId, ownerId);
        return mapper.list(drawingId, ownerId, now()).stream()
                .map(link -> new Entry(link.id(), utc(link.createdAt()), utc(link.expiresAt())))
                .toList();
    }

    @Transactional
    public Created create(String ownerId, String drawingId, String validity) {
        requirePersonalDrawing(drawingId, ownerId);
        LocalDateTime now = now();
        LocalDateTime expiresAt = switch (validity == null ? "7d" : validity) {
            case "1h" -> now.plusHours(1);
            case "1d" -> now.plusDays(1);
            case "7d" -> now.plusDays(7);
            case "permanent" -> null;
            default -> throw new BadRequestException("无效的分享有效期");
        };
        if (mapper.list(drawingId, ownerId, now).size() >= 20) {
            throw new BadRequestException("最多可有 20 个有效链接，请先撤销一个");
        }
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String token = "ds_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String id = UUID.randomUUID().toString().replace("-", "");
        mapper.insert(id, drawingId, ownerId, hash(token), now, expiresAt);
        return new Created(id, utc(now), utc(expiresAt), token);
    }

    @Transactional
    public void revoke(String ownerId, String drawingId, String id) {
        requirePersonalDrawing(drawingId, ownerId);
        if (mapper.revoke(id, drawingId, ownerId, now()) == 0) throw unavailable();
    }

    public PublicScene read(String token, int knownRevision) {
        if (token == null || !TOKEN.matcher(token).matches()) throw unavailable();
        var saved = mapper.read(hash(token), Math.max(knownRevision, 0), now());
        if (saved == null) throw unavailable();
        return new PublicScene(saved.title(), saved.revision(),
                saved.updatedAt().atZone(ZoneId.systemDefault()).toInstant(), utc(saved.expiresAt()),
                saved.sceneJson() == null ? null : readOnlyScene(saved.sceneJson()));
    }

    private ObjectNode readOnlyScene(String saved) {
        try {
            var source = json.readTree(saved);
            var result = json.createObjectNode();
            var elements = result.putArray("elements");
            var files = result.putObject("files");
            var sourceFiles = source.path("files");
            for (var node : source.path("elements")) {
                if (!(node instanceof ObjectNode element) || element.path("isDeleted").asBoolean()) continue;
                String type = element.path("type").asText();
                if ("embeddable".equals(type) || "iframe".equals(type)) continue;
                element.remove("link");
                element.remove("customData");
                if ("image".equals(type)) {
                    String id = element.path("fileId").asText();
                    var image = sourceFiles.path(id);
                    String data = image.path("dataURL").asText();
                    if (id.isEmpty() || !INLINE_IMAGE.matcher(data).find()) continue;
                    var file = files.putObject(id);
                    file.put("id", id);
                    file.put("dataURL", data);
                    file.put("mimeType", data.substring(5, data.indexOf(';')));
                    file.put("created", image.path("created").asLong());
                }
                elements.add(element);
            }
            String background = source.path("appState").path("viewBackgroundColor").asText("#ffffff");
            result.putObject("appState").put("viewBackgroundColor", background);
            return result;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取已保存的草图");
        }
    }

    private void requirePersonalDrawing(String drawingId, String ownerId) {
        if (ownerId == null) throw new UnauthorizedException("请先登录");
        if (mapper.lockPersonalDrawing(drawingId, ownerId) == null) {
            throw new NotFoundException("个人草图不存在或无权访问");
        }
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static Instant utc(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static NotFoundException unavailable() {
        return new NotFoundException("草图分享链接无效、已过期或已撤销");
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
