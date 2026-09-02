package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import cn.wayuwa.wao.common.BadRequestException;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;

class SkillPackageInspectorTest {
    private final SkillPackageInspector inspector = new SkillPackageInspector();

    @Test
    void acceptsSingleRootAndBuildsStableManifestAndHash() throws Exception {
        byte[] zip = zip(Map.of(
                "meeting/SKILL.md", "---\nname: Meeting helper\ndescription: Summarize meetings\n---\n# Guide",
                "meeting/references/rules.md", "Keep decisions."));
        var result = inspector.inspectZip(zip);
        assertThat(result.files()).extracting(SkillPackageInspector.FileMeta::path)
                .containsExactlyInAnyOrder("SKILL.md", "references/rules.md");
        assertThat(result.sha256()).matches("[0-9a-f]{64}");
        assertThat(result.compatiblePlatforms()).containsExactly("agent-skills", "codex", "claude-code", "openclaw");
        assertThat(inspector.inspectZip(result.packageContent()).sha256()).isEqualTo(result.sha256());
    }

    @Test
    void rejectsTraversalDuplicateExecutableAndMissingManifest() throws Exception {
        assertThatThrownBy(() -> inspector.inspectZip(zip(Map.of("../SKILL.md", valid()))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("不安全路径");
        Map<String, String> duplicate = new LinkedHashMap<>(); duplicate.put("SKILL.md", valid()); duplicate.put("skill.md", valid());
        assertThatThrownBy(() -> inspector.inspectZip(zip(duplicate))).isInstanceOf(BadRequestException.class).hasMessageContaining("重复路径");
        assertThatThrownBy(() -> inspector.inspectZip(zip(Map.of("SKILL.md", valid(), "bin/tool.exe", "MZ"))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("可执行二进制");
        assertThatThrownBy(() -> inspector.inspectZip(zip(Map.of("README.md", "none"))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("SKILL.md");
    }

    @Test
    void reportsSecretsAndRejectsInvalidFrontmatterAndUtf8() throws Exception {
        var result = inspector.inspectZip(zip(Map.of("SKILL.md", valid(), "config.txt", "sk-abcdefghijklmnopqrstuvwxyz123456")));
        assertThat(result.findings()).singleElement().asString().contains("OpenAI Token");
        var commonCredentials = inspector.inspectZip(zip(Map.of("SKILL.md", valid(), "config.env",
                "PASSWORD=correct-horse-battery-staple\nAuthorization: Bearer abcdefghijklmnop")));
        assertThat(commonCredentials.findings()).anyMatch(item -> item.contains("密码或密钥配置"));
        assertThat(commonCredentials.findings()).anyMatch(item -> item.contains("Bearer Token"));
        assertThat(inspector.inspectZip(zip(Map.of("SKILL.md", valid(), "example.env",
                "PASSWORD=${PASSWORD}\nAPI_KEY=your-api-key"))).findings()).isEmpty();
        assertThatThrownBy(() -> inspector.inspectZip(zip(Map.of("SKILL.md", "---\nname: only\n---"))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("description");
        assertThatThrownBy(() -> inspector.inspectZip(zipBytes(Map.of("SKILL.md", new byte[] {(byte) 0xc3, 0x28}))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("UTF-8");
    }

    @Test
    void scansSecretsInTextFilesLargerThanOneMebibyte() throws Exception {
        byte[] largeText = new byte[1024 * 1024 + 128];
        java.util.Arrays.fill(largeText, (byte) 'a');
        byte[] secret = "\nPASSWORD=correct-horse-battery-staple\n".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(secret, 0, largeText, largeText.length - secret.length, secret.length);

        var result = inspector.inspectZip(zipBytes(Map.of(
                "SKILL.md", valid().getBytes(StandardCharsets.UTF_8),
                "references/large.txt", largeText)));

        assertThat(result.findings()).anyMatch(item -> item.contains("references/large.txt")
                && item.contains("密码或密钥配置"));
    }

    @Test
    void previewsUtf8FilesAndClosesBinaryOrOversizedContent() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("SKILL.md", valid().getBytes(StandardCharsets.UTF_8));
        entries.put("references/rules.md", "先检查，再执行。".getBytes(StandardCharsets.UTF_8));
        entries.put("assets/logo.png", new byte[] {1, 0, 2});
        entries.put("references/large.txt", new byte[SkillPackageInspector.MAX_PREVIEW_BYTES + 1]);
        byte[] canonical = inspector.inspectZip(zipBytes(entries)).packageContent();

        var text = inspector.previewFile(canonical, "references/rules.md");
        assertThat(text.previewable()).isTrue();
        assertThat(text.content()).isEqualTo("先检查，再执行。");
        assertThat(inspector.previewFile(canonical, "assets/logo.png").message()).contains("二进制");
        assertThat(inspector.previewFile(canonical, "references/large.txt").message()).contains("512 KiB");
        assertThatThrownBy(() -> inspector.previewFile(canonical, "../SKILL.md"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("不安全路径");
    }

    private static String valid() { return "---\nname: Safe skill\ndescription: A safe test skill\n---\n# Safe"; }
    private static byte[] zip(Map<String, String> entries) throws Exception {
        Map<String, byte[]> bytes = new LinkedHashMap<>(); entries.forEach((key, value) -> bytes.put(key, value.getBytes(StandardCharsets.UTF_8)));
        return zipBytes(bytes);
    }
    private static byte[] zipBytes(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(out)) {
            for (var entry : entries.entrySet()) { zip.putArchiveEntry(new ZipArchiveEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeArchiveEntry(); }
        }
        return out.toByteArray();
    }
}
