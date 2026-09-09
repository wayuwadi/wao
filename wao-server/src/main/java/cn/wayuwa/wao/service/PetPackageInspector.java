package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.zip.*;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 宠物包只接受声明与图集，永不执行上传内容。 */
@Component
public class PetPackageInspector {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private final ObjectMapper json;
    public PetPackageInspector(ObjectMapper json) { this.json = json; }
    public record Pack(String slug, String name, String description, int version, String type,
                       byte[] sprite, byte[] archive, String sha256) {}

    /** 校验 ZIP 路径、容量及内容并生成不可变规范包。 */
    public Pack inspect(byte[] archive) {
        if (archive == null || archive.length == 0 || archive.length > MAX_BYTES) throw bad("ZIP 必须在 10 MiB 以内");
        Map<String, byte[]> files = new LinkedHashMap<>();
        int total = 0, count = 0;
        try (ZipFile zip = ZipFile.builder().setByteArray(archive).get()) {
            var entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (++count > 8) throw bad("宠物包文件过多");
                String path = entry.getName();
                if (!path.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)?/?")
                        || Arrays.asList(path.split("/")).stream().anyMatch(p -> p.equals(".") || p.equals(".."))) throw bad("宠物包路径不安全");
                if (entry.isUnixSymlink() || entry.getGeneralPurposeBit().usesEncryption()) throw bad("不接受符号链接或加密包");
                if (entry.isDirectory()) continue;
                byte[] content;
                try (var in = zip.getInputStream(entry)) { content = in.readNBytes(12 * 1024 * 1024 - total + 1); }
                total += content.length;
                if (total > 12 * 1024 * 1024) throw bad("解压后超过 12 MiB");
                if (files.putIfAbsent(path, content) != null) throw bad("宠物包路径重复");
            }
            if (!files.containsKey("pet.json") && !files.isEmpty()) {
                String first = files.keySet().iterator().next();
                int slash = first.indexOf('/');
                if (slash < 0) throw bad("缺少 pet.json");
                String prefix = first.substring(0, slash + 1);
                if (files.keySet().stream().anyMatch(p -> !p.startsWith(prefix))) throw bad("只允许一个共同根目录");
                Map<String, byte[]> stripped = new LinkedHashMap<>();
                files.forEach((p, bytes) -> stripped.put(p.substring(prefix.length()), bytes)); files = stripped;
            }
            for (String path : files.keySet()) if (!Set.of("pet.json", "spritesheet.png", "spritesheet.webp", "README.md", "LICENSE").contains(path)) throw bad("不支持包内文件：" + path);
            byte[] manifest = files.get("pet.json");
            if (manifest == null || manifest.length > 16384) throw bad("缺少或超大 pet.json");
            JsonNode node = json.readTree(manifest);
            String spritePath = node.path("spritesheetPath").asText();
            if (!Set.of("spritesheet.png", "spritesheet.webp").contains(spritePath) || files.get(spritePath) == null) throw bad("图集路径必须为 spritesheet.png 或 spritesheet.webp");
            if (files.containsKey("spritesheet.png") && files.containsKey("spritesheet.webp")) throw bad("只允许一张图集");
            return build(node, files.get(spritePath), spritePath, files);
        } catch (BadRequestException e) { throw e; }
        catch (Exception e) { throw bad("宠物包损坏或格式不正确"); }
    }

    /** 将用户填写的真实角色信息及自制图集合成为可安装包。 */
    public Pack create(String manifest, byte[] sprite) {
        try {
            if (manifest == null || manifest.length() > 16384) throw bad("宠物信息过长");
            JsonNode node = json.readTree(manifest);
            String path = node.path("spritesheetPath").asText();
            if (!Set.of("spritesheet.png", "spritesheet.webp").contains(path)) throw bad("仅支持 PNG/WebP 图集");
            return build(node, sprite, path, Map.of());
        } catch (BadRequestException e) { throw e; }
        catch (Exception e) { throw bad("宠物信息或图集格式不正确"); }
    }

    private Pack build(JsonNode node, byte[] sprite, String path, Map<String, byte[]> original) throws Exception {
        if (sprite == null || sprite.length == 0 || sprite.length > MAX_BYTES) throw bad("图集必须在 10 MiB 以内");
        String slug = value(node, "id", 64), name = value(node, "displayName", 100), description = value(node, "description", 1000);
        if (!slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) throw bad("标识只允许小写英文、数字和连字符");
        JsonNode versionNode = node.get("spriteVersionNumber");
        if (versionNode != null && !versionNode.isIntegralNumber()) throw bad("图集版本必须为整数");
        int version = versionNode == null ? 1 : versionNode.asInt();
        if (version != 1 && version != 2) throw bad("只支持 v1/v2 图集");
        int[] size = dimensions(sprite, path);
        if (size[0] != 1536 || size[1] != (version == 1 ? 1872 : 2288)) throw bad("图集尺寸应为 1536×" + (version == 1 ? 1872 : 2288));
        var normalized = json.createObjectNode().put("id", slug).put("displayName", name).put("description", description)
                .put("spritesheetPath", path).put("spriteVersionNumber", version);
        String kind = node.path("kind").asText("animal");
        if (!Set.of("animal", "person", "object").contains(kind)) throw bad("kind 应为 animal、person 或 object");
        normalized.put("kind", kind);
        Map<String, byte[]> files = new TreeMap<>();
        files.put("pet.json", json.writerWithDefaultPrettyPrinter().writeValueAsBytes(normalized)); files.put(path, sprite);
        for (String extra : List.of("README.md", "LICENSE")) if (original.containsKey(extra)) {
            byte[] text = original.get(extra);
            if (text.length > 65536) throw bad("说明文件超过 64 KiB");
            String decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(text)).toString();
            if (decoded.indexOf('\0') >= 0 || decoded.matches("(?s).*(-----BEGIN .*PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9_-]{20,}).*")) throw bad("说明文件包含二进制或疑似凭据");
            files.put(extra, text);
        }
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipArchiveOutputStream(out)) {
            for (var file : files.entrySet()) {
                var entry = new ZipArchiveEntry(file.getKey()); entry.setTime(0); zip.putArchiveEntry(entry); zip.write(file.getValue()); zip.closeArchiveEntry();
            }
        }
        byte[] archive = out.toByteArray();
        if (archive.length > MAX_BYTES) throw bad("打包后超过 10 MiB");
        return new Pack(slug, name, description, version, path.endsWith("png") ? "image/png" : "image/webp", sprite, archive,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive)));
    }

    private static String value(JsonNode node, String key, int max) {
        JsonNode field = node.get(key);
        if (field == null || !field.isTextual() || field.asText().isBlank() || field.asText().length() > max) throw bad(key + " 缺失或超长");
        return field.asText().trim();
    }
    private static int[] dimensions(byte[] bytes, String path) throws IOException {
        if (path.endsWith("png")) {
            if (bytes.length < 24 || ByteBuffer.wrap(bytes).getLong() != 0x89504e470d0a1a0aL) throw bad("PNG 格式错误");
            var input = ByteBuffer.wrap(bytes); int w = input.getInt(16), h = input.getInt(20);
            if (w != 1536 || (h != 1872 && h != 2288)) return new int[]{w, h};
            if (ImageIO.read(new ByteArrayInputStream(bytes)) == null) throw bad("PNG 无法解码");
            return new int[]{w, h};
        }
        if (bytes.length < 30 || !ascii(bytes, 0, "RIFF") || !ascii(bytes, 8, "WEBP")) throw bad("WebP 格式错误");
        var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (Integer.toUnsignedLong(b.getInt(4)) + 8 != bytes.length) throw bad("WebP 长度错误");
        int w = 0, h = 0; boolean pixels = false;
        for (long pos = 12; pos < bytes.length;) {
            if (pos + 8 > bytes.length) throw bad("WebP 数据截断");
            int p = (int) pos; long size = Integer.toUnsignedLong(b.getInt(p + 4));
            if (pos + 8 + size > bytes.length) throw bad("WebP 数据截断");
            if (ascii(bytes, p, "VP8X") && size >= 10) {
                if ((bytes[p + 8] & 2) != 0) throw bad("请上传静态图集，不接受动画 WebP");
                w = u24(bytes, p + 12) + 1; h = u24(bytes, p + 15) + 1;
            } else if (ascii(bytes, p, "VP8L") && size > 5 && bytes[p + 8] == 0x2f) {
                int bits = b.getInt(p + 9); int pw = (bits & 0x3fff) + 1, ph = ((bits >>> 14) & 0x3fff) + 1;
                if (w != 0 && (w != pw || h != ph)) throw bad("WebP 尺寸不一致"); w = pw; h = ph; pixels = true;
            } else if (ascii(bytes, p, "VP8 ") && size > 10 && bytes[p + 11] == (byte) 0x9d && bytes[p + 12] == 1 && bytes[p + 13] == 0x2a) {
                int pw = b.getShort(p + 14) & 0x3fff, ph = b.getShort(p + 16) & 0x3fff;
                if (w != 0 && (w != pw || h != ph)) throw bad("WebP 尺寸不一致"); w = pw; h = ph; pixels = true;
            }
            pos += 8 + size + (size % 2);
            if (pos > bytes.length) throw bad("WebP 数据截断");
        }
        if (!pixels) throw bad("WebP 缺少图像数据");
        return new int[]{w, h};
    }
    private static int u24(byte[] b, int p) { return (b[p] & 255) | ((b[p+1] & 255) << 8) | ((b[p+2] & 255) << 16); }
    private static boolean ascii(byte[] b, int p, String s) { return new String(b, p, s.length(), StandardCharsets.US_ASCII).equals(s); }
    private static BadRequestException bad(String message) { return new BadRequestException(message); }
}
