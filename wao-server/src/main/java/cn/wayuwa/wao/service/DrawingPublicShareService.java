package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.mapper.DrawingPublicShareMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DrawingPublicShareService {
    private static final Logger LOG = LoggerFactory.getLogger(DrawingPublicShareService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern TOKEN = Pattern.compile("ds_[A-Za-z0-9_-]{43}");
    private static final Pattern TEXT_COLOR = Pattern.compile("#[a-fA-F0-9]{6}");
    private static final Pattern INLINE_IMAGE = Pattern.compile(
            "^data:image/(?:png|jpeg|gif|webp|svg\\+xml|bmp|avif|x-icon);base64,");
    private final DrawingPublicShareMapper mapper;
    private final ObjectMapper json;
    private final VaultCryptoService crypto;
    private final KnowledgeAccessService knowledgeAccess;

    public record Entry(String id, Instant createdAt, Instant expiresAt, String token) {}
    public record PublicScene(String title, int revision, Instant updatedAt, Instant expiresAt, ObjectNode scene) {}

    public DrawingPublicShareService(DrawingPublicShareMapper mapper, ObjectMapper json, VaultCryptoService crypto,
                                     KnowledgeAccessService knowledgeAccess) {
        this.mapper = mapper;
        this.json = json;
        this.crypto = crypto;
        this.knowledgeAccess = knowledgeAccess;
    }

    @Transactional
    public List<Entry> list(String ownerId, String drawingId) {
        requireOwnedDrawing(drawingId, ownerId);
        return mapper.list(drawingId, ownerId, now()).stream()
                .map(link -> new Entry(link.id(), utc(link.createdAt()), utc(link.expiresAt()), recoverToken(link)))
                .toList();
    }

    @Transactional
    public Entry create(String ownerId, String drawingId, String validity) {
        requireOwnedDrawing(drawingId, ownerId);
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
        var encrypted = crypto.encrypt(token);
        mapper.insert(id, drawingId, ownerId, hash(token), now, expiresAt, encrypted.ciphertext(), encrypted.nonce());
        return new Entry(id, utc(now), utc(expiresAt), token);
    }

    private String recoverToken(DrawingPublicShareMapper.Link link) {
        if (link.tokenCiphertext() == null || link.tokenNonce() == null) return null;
        try {
            return crypto.decrypt(link.tokenCiphertext(), link.tokenNonce());
        } catch (IllegalStateException e) {
            // Keep revocation available even when an encrypted copy cannot be recovered.
            LOG.warn("Cannot decrypt stored drawing share {}", link.id());
            return null;
        }
    }

    @Transactional
    public void revoke(String ownerId, String drawingId, String id) {
        requireOwnedDrawing(drawingId, ownerId);
        if (mapper.revoke(id, drawingId, ownerId, now()) == 0) throw unavailable();
    }

    public PublicScene read(String token, int knownRevision) {
        if (token == null || !TOKEN.matcher(token).matches()) throw unavailable();
        var saved = mapper.read(hash(token), Math.max(knownRevision, 0), now());
        if (saved == null || !canAccessKnowledge(saved.kbId(), saved.ownerId())) throw unavailable();
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
                JsonNode custom = element.remove("customData");
                if ("text".equals(type) && custom != null) addPublicTextStyle(element, custom.path("waoTextStyle"));
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

    /** Only expose checked display fields. An older text snapshot may contain private edits. */
    private void addPublicTextStyle(ObjectNode element, JsonNode style) {
        if (!style.isObject()) return;
        var safe = json.createObjectNode();
        String background = style.path("background").asText();
        if (TEXT_COLOR.matcher(background).matches()) safe.put("background", background);
        String text = element.path("originalText").asText(element.path("text").asText()).replace("\r\n", "\n").replace('\r', '\n');
        var runs = style.path("runs");
        if (runs.isArray() && runs.size() <= 256 && text.equals(style.path("source").asText())) {
            var safeRuns = json.createArrayNode();
            int lastEnd = 0;
            for (var run : runs) {
                var start = run.path("start");
                var end = run.path("end");
                String color = run.path("color").asText();
                if (!start.isIntegralNumber() || !end.isIntegralNumber() || !start.canConvertToInt() || !end.canConvertToInt()
                        || start.intValue() < lastEnd || end.intValue() <= start.intValue() || end.intValue() > text.length()
                        || !TEXT_COLOR.matcher(color).matches()) continue;
                safeRuns.addObject().put("start", start.intValue()).put("end", end.intValue()).put("color", color);
                lastEnd = end.intValue();
            }
            if (!safeRuns.isEmpty()) {
                safe.put("source", text);
                safe.set("runs", safeRuns);
            }
        }
        if (!safe.isEmpty()) element.putObject("customData").set("waoTextStyle", safe);
    }

    private void requireOwnedDrawing(String drawingId, String ownerId) {
        if (ownerId == null) throw new UnauthorizedException("请先登录");
        var drawing = mapper.lockOwnedDrawing(drawingId, ownerId);
        if (drawing == null || !canAccessKnowledge(drawing.kbId(), ownerId)) {
            throw new NotFoundException("草图不存在或无权分享");
        }
    }

    private boolean canAccessKnowledge(String kbId, String ownerId) {
        return kbId == null || knowledgeAccess.visible(kbId, ownerId);
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
