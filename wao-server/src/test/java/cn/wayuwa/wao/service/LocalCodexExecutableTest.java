package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalCodexExecutableTest {
    @TempDir Path root;
    @Test void followsAtomicVersionChangeAndRejectsCorruptionOrEscape() throws Exception {
        var json = new ObjectMapper();
        Path versions = Files.createDirectories(root.resolve("versions"));
        Path first = Files.writeString(versions.resolve("one.exe"), "first");
        Path second = Files.writeString(versions.resolve("two.exe"), "second");
        Path pointer = root.resolve("current.json");
        write(json, pointer, first);
        assertThat(LocalCodexExecutable.resolve(pointer.toString())).isEqualTo(first.toString());
        write(json, pointer, second);
        assertThat(LocalCodexExecutable.resolve(pointer.toString())).isEqualTo(second.toString());
        Files.writeString(second, "corrupt");
        assertThatThrownBy(() -> LocalCodexExecutable.resolve(pointer.toString())).hasMessageContaining("校验失败");
        Path outside = Files.writeString(root.resolve("outside.exe"), "outside");
        write(json, pointer, outside);
        assertThatThrownBy(() -> LocalCodexExecutable.resolve(pointer.toString())).hasMessageContaining("超出");
        assertThat(LocalCodexExecutable.resolve("codex")).isEqualTo("codex");
    }
    private void write(ObjectMapper json, Path pointer, Path binary) throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(binary)));
        json.writeValue(pointer.toFile(), Map.of("schema", 1, "active", Map.of("path", root.relativize(binary).toString(), "sha256", digest)));
    }
}
