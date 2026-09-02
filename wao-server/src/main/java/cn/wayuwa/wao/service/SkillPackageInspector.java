package cn.wayuwa.wao.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import cn.wayuwa.wao.common.BadRequestException;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 只读解析 Skill 包：它从不执行包内脚本。 */
@Component
public class SkillPackageInspector {
    public static final long MAX_PACKAGE_BYTES = 10L * 1024 * 1024;
    public static final long MAX_UNPACKED_BYTES = 20L * 1024 * 1024;
    public static final int MAX_PREVIEW_BYTES = 512 * 1024;
    public static final int MAX_FILES = 200;
    private static final Set<String> EXECUTABLE_SUFFIXES = Set.of(
            ".exe", ".dll", ".so", ".dylib", ".com", ".msi", ".apk", ".dmg", ".class", ".jar");
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----");
    private static final Pattern OPENAI_TOKEN = Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}\\b");
    private static final Pattern GITHUB_TOKEN = Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b");
    private static final Pattern GITHUB_FINE_GRAINED_TOKEN = Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b");
    private static final Pattern AWS_KEY = Pattern.compile("\\bAKIA[A-Z0-9]{16}\\b");
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{12,}");
    private static final Pattern ASSIGNED_SECRET = Pattern.compile(
            "(?i)\\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|passwd|secret)\\s*[=:]\\s*[\\\"']?([^\\s,;\\\"']{8,})");

    /** 解析一个 ZIP，规范化为可下载的稳定 ZIP。 */
    public Inspection inspectZip(byte[] source) {
        if (source == null || source.length == 0) throw new BadRequestException("请选择 Skill ZIP");
        if (source.length > MAX_PACKAGE_BYTES) throw new BadRequestException("Skill 包超过 10 MiB");
        List<EntryData> entries = new ArrayList<>();
        long unpacked = 0;
        try (ZipFile zip = ZipFile.builder().setByteArray(source).get()) {
            Enumeration<ZipArchiveEntry> all = zip.getEntries();
            while (all.hasMoreElements()) {
                ZipArchiveEntry entry = all.nextElement();
                if (entry.isDirectory()) continue;
                if (entry.isUnixSymlink()) throw new BadRequestException("Skill 包不允许符号链接：" + entry.getName());
                if (entry.getGeneralPurposeBit().usesEncryption()) throw new BadRequestException("Skill 包不允许加密 ZIP");
                long remaining = MAX_UNPACKED_BYTES - unpacked;
                if (remaining <= 0 || entry.getSize() > remaining) throw new BadRequestException("Skill 包解压后超过 20 MiB");
                byte[] content = zip.getInputStream(entry).readNBytes((int) remaining + 1);
                if (content.length > remaining) throw new BadRequestException("Skill 包解压后超过 20 MiB");
                unpacked += content.length;
                entries.add(new EntryData(entry.getName(), content));
            }
        } catch (BadRequestException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new BadRequestException("Skill ZIP 无法解压，请检查文件是否完整");
        }
        return inspect(entries);
    }

    /** 把浏览器选中的文件夹规范化成 ZIP。 */
    public Inspection inspectFiles(List<MultipartFile> files, List<String> paths) {
        if (files == null || files.isEmpty()) throw new BadRequestException("请选择 Skill 文件夹");
        if (paths == null || paths.size() != files.size()) throw new BadRequestException("文件夹路径与文件数量不一致");
        List<EntryData> entries = new ArrayList<>();
        long total = 0;
        try {
            for (int i = 0; i < files.size(); i++) {
                MultipartFile file = files.get(i);
                total += file.getSize();
                if (total > MAX_UNPACKED_BYTES) throw new BadRequestException("Skill 文件夹超过 20 MiB");
                entries.add(new EntryData(paths.get(i), file.getBytes()));
            }
        } catch (IOException ex) {
            throw new BadRequestException("读取 Skill 文件夹失败，请重试");
        }
        return inspect(entries);
    }

    /** 从已规范化的审核 ZIP 中读取一个文件；文本可预览，二进制和超大文件只返回说明。 */
    public FilePreview previewFile(byte[] source, String requestedPath) {
        if (source == null || source.length == 0) throw new BadRequestException("Skill 包不存在");
        String path = safePath(requestedPath);
        try (ZipFile zip = ZipFile.builder().setByteArray(source).get()) {
            Enumeration<ZipArchiveEntry> all = zip.getEntries();
            while (all.hasMoreElements()) {
                ZipArchiveEntry entry = all.nextElement();
                if (entry.isDirectory() || !entry.getName().equals(path)) continue;
                long size = entry.getSize();
                if (size > MAX_PREVIEW_BYTES) {
                    return new FilePreview(path, size, false, null, "文件超过 512 KiB，请下载 ZIP 后查看");
                }
                byte[] content = zip.getInputStream(entry).readNBytes(MAX_PREVIEW_BYTES + 1);
                if (content.length > MAX_PREVIEW_BYTES) {
                    return new FilePreview(path, content.length, false, null, "文件超过 512 KiB，请下载 ZIP 后查看");
                }
                if (containsNul(content)) {
                    return new FilePreview(path, content.length, false, null, "二进制文件不支持在线预览，请下载 ZIP 后查看");
                }
                try {
                    String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(content)).toString();
                    return new FilePreview(path, content.length, true, text, null);
                } catch (CharacterCodingException ex) {
                    return new FilePreview(path, content.length, false, null, "非 UTF-8 文件不支持在线预览，请下载 ZIP 后查看");
                }
            }
        } catch (BadRequestException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new BadRequestException("Skill 文件读取失败，请重试");
        }
        throw new cn.wayuwa.wao.common.NotFoundException("Skill 文件不存在");
    }

    private Inspection inspect(List<EntryData> rawEntries) {
        if (rawEntries.isEmpty()) throw new BadRequestException("Skill 包不能为空");
        if (rawEntries.size() > MAX_FILES) throw new BadRequestException("Skill 包最多包含 200 个文件");
        rawEntries.forEach(entry -> safePath(entry.path()));
        List<EntryData> entries = stripCommonRoot(rawEntries);
        Map<String, byte[]> normalized = new LinkedHashMap<>();
        long unpacked = 0;
        for (EntryData entry : entries) {
            String path = safePath(entry.path());
            String key = path.toLowerCase(Locale.ROOT);
            if (normalized.putIfAbsent(key, entry.content()) != null) {
                throw new BadRequestException("Skill 包存在重复路径：" + path);
            }
            unpacked += entry.content().length;
            if (unpacked > MAX_UNPACKED_BYTES) throw new BadRequestException("Skill 包解压后超过 20 MiB");
            assertNotExecutable(path);
        }
        byte[] skillBytes = normalized.get("skill.md");
        if (skillBytes == null) throw new BadRequestException("Skill 包根目录必须包含 SKILL.md");
        String skillMd = decodeText("SKILL.md", skillBytes);
        Map<String, Object> frontmatter = frontmatter(skillMd);
        String name = required(frontmatter, "name");
        String description = required(frontmatter, "description");
        List<String> findings = scanSecrets(normalized);
        List<FileMeta> manifest = normalized.entrySet().stream()
                .map(e -> new FileMeta(actualPath(entries, e.getKey()), e.getValue().length)).toList();
        byte[] canonical = canonicalZip(entries);
        if (canonical.length > MAX_PACKAGE_BYTES) throw new BadRequestException("规范化后的 Skill ZIP 超过 10 MiB");
        return new Inspection(canonical, unpacked, manifest, skillMd, name, description, findings,
                List.of("agent-skills", "codex", "claude-code", "openclaw"), sha256(canonical));
    }

    private static List<EntryData> stripCommonRoot(List<EntryData> source) {
        String root = null;
        for (EntryData entry : source) {
            String path = entry.path().replace('\\', '/');
            int slash = path.indexOf('/');
            if (slash <= 0) return source;
            String candidate = path.substring(0, slash);
            if (root == null) root = candidate;
            else if (!root.equals(candidate)) return source;
        }
        final int prefix = root.length() + 1;
        return source.stream().map(e -> new EntryData(e.path().replace('\\', '/').substring(prefix), e.content())).toList();
    }

    private static String safePath(String input) {
        if (input == null || input.isBlank() || input.indexOf('\0') >= 0) throw new BadRequestException("Skill 包含空路径");
        String path = input.replace('\\', '/');
        if (path.startsWith("/") || path.matches("^[A-Za-z]:/.*")) throw new BadRequestException("Skill 包不允许绝对路径：" + input);
        String[] parts = path.split("/");
        for (String part : parts) if (part.isBlank() || ".".equals(part) || "..".equals(part)) {
            throw new BadRequestException("Skill 包含不安全路径：" + input);
        }
        if (path.length() > 300) throw new BadRequestException("Skill 文件路径过长：" + input);
        return path;
    }

    private static void assertNotExecutable(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (EXECUTABLE_SUFFIXES.stream().anyMatch(lower::endsWith)) {
            throw new BadRequestException("Skill 包不允许可执行二进制：" + path);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> frontmatter(String skillMd) {
        String normalized = skillMd.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) throw new BadRequestException("SKILL.md 必须以 YAML frontmatter 开头");
        int end = normalized.indexOf("\n---", 4);
        if (end < 0) throw new BadRequestException("SKILL.md YAML frontmatter 未闭合");
        Object loaded;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setMaxAliasesForCollections(10);
            loaded = new Yaml(new SafeConstructor(options)).load(normalized.substring(4, end));
        } catch (RuntimeException ex) {
            throw new BadRequestException("SKILL.md YAML frontmatter 格式错误");
        }
        if (!(loaded instanceof Map<?, ?> map)) throw new BadRequestException("SKILL.md YAML frontmatter 不能为空");
        Map<String, Object> result = new HashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static String required(Map<String, Object> values, String key) {
        String value = values.get(key) == null ? "" : String.valueOf(values.get(key)).trim();
        if (value.isBlank()) throw new BadRequestException("SKILL.md frontmatter 缺少 " + key);
        return value;
    }

    private static List<String> scanSecrets(Map<String, byte[]> files) {
        List<String> findings = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            if (containsNul(entry.getValue())) continue;
            String text = new String(entry.getValue(), StandardCharsets.UTF_8);
            if (PRIVATE_KEY.matcher(text).find()) findings.add(entry.getKey() + "：检测到私钥");
            if (OPENAI_TOKEN.matcher(text).find()) findings.add(entry.getKey() + "：检测到疑似 OpenAI Token");
            if (GITHUB_TOKEN.matcher(text).find()) findings.add(entry.getKey() + "：检测到疑似 GitHub Token");
            if (GITHUB_FINE_GRAINED_TOKEN.matcher(text).find()) findings.add(entry.getKey() + "：检测到疑似 GitHub Token");
            if (AWS_KEY.matcher(text).find()) findings.add(entry.getKey() + "：检测到疑似 AWS Access Key");
            if (BEARER_TOKEN.matcher(text).find()) findings.add(entry.getKey() + "：检测到疑似 Bearer Token");
            var assigned = ASSIGNED_SECRET.matcher(text);
            while (assigned.find()) if (!isPlaceholder(assigned.group(1))) {
                findings.add(entry.getKey() + "：检测到疑似密码或密钥配置");
                break;
            }
        }
        return findings.stream().distinct().toList();
    }

    private static boolean isPlaceholder(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return (lower.startsWith("${") && lower.endsWith("}"))
                || (lower.startsWith("{{") && lower.endsWith("}}"))
                || (lower.startsWith("<") && lower.endsWith(">"))
                || lower.contains("placeholder") || lower.contains("example") || lower.contains("changeme")
                || lower.contains("replace-me") || lower.contains("replace_me")
                || lower.startsWith("your-") || lower.startsWith("your_")
                || lower.contains("dummy") || lower.contains("sample") || lower.contains("redacted")
                || lower.contains("已脱敏") || lower.matches("x{8,}");
    }

    private static String decodeText(String name, byte[] content) {
        if (containsNul(content)) throw new BadRequestException(name + " 必须是 UTF-8 文本");
        try {
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(content)).toString();
            if (value.isBlank()) throw new BadRequestException(name + " 不能为空");
            return value;
        } catch (CharacterCodingException ex) {
            throw new BadRequestException(name + " 必须是 UTF-8 文本");
        }
    }

    private static boolean containsNul(byte[] value) {
        for (byte b : value) if (b == 0) return true;
        return false;
    }

    private static String actualPath(List<EntryData> entries, String lower) {
        return entries.stream().map(EntryData::path).map(p -> p.replace('\\', '/'))
                .filter(p -> p.toLowerCase(Locale.ROOT).equals(lower)).findFirst().orElse(lower);
    }

    private static byte[] canonicalZip(List<EntryData> source) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(output)) {
            zip.setEncoding(StandardCharsets.UTF_8.name());
            source.stream().sorted((a, b) -> a.path().compareToIgnoreCase(b.path())).forEach(entry -> {
                try {
                    ZipArchiveEntry archiveEntry = new ZipArchiveEntry(safePath(entry.path()));
                    archiveEntry.setTime(0);
                    zip.putArchiveEntry(archiveEntry);
                    zip.write(entry.content());
                    zip.closeArchiveEntry();
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            });
            zip.finish();
            return output.toByteArray();
        } catch (IOException | IllegalStateException ex) {
            throw new BadRequestException("生成规范 Skill ZIP 失败");
        }
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record InputFile(String path, byte[] content) { }
    private record EntryData(String path, byte[] content) { }
    public record FileMeta(String path, long size) { }
    public record FilePreview(String path, long size, boolean previewable, String content, String message) { }
    public record Inspection(byte[] packageContent, long unpackedSize, List<FileMeta> files, String skillMd,
            String name, String description, List<String> findings, List<String> compatiblePlatforms, String sha256) { }
}
