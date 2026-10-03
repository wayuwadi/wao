package cn.wayuwa.wao.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 受主机运维管理的原子版本指针；每次启动读取，正在执行的进程保持原版本。 */
final class LocalCodexExecutable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static String verifiedKey;
    private static String verifiedPath;

    static synchronized String resolve(String configured) throws IOException {
        if (!configured.endsWith(".json")) return configured;
        Path manifest = Path.of(configured).toAbsolutePath().normalize();
        if (Files.size(manifest) > 16_384) throw new IOException("本机 Codex 版本指针无效");
        var root = JSON.readTree(Files.readString(manifest));
        if (root.path("schema").asInt() != 1) throw new IOException("本机 Codex 版本指针格式不支持");
        var active = root.path("active");
        Path binary = manifest.getParent().resolve(active.path("path").asText()).normalize();
        Path versions = manifest.getParent().resolve("versions").toRealPath();
        if (!binary.toRealPath().startsWith(versions) || !Files.isRegularFile(binary))
            throw new IOException("本机 Codex 路径超出受管理版本目录");
        String expected = active.path("sha256").asText();
        String key = binary + ":" + Files.size(binary) + ":" + Files.getLastModifiedTime(binary) + ":" + expected;
        if (key.equals(verifiedKey)) return verifiedPath;
        try (var input = Files.newInputStream(binary)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
            if (!HexFormat.of().formatHex(digest.digest()).equals(expected)) throw new IOException("本机 Codex 制品校验失败");
        } catch (java.security.NoSuchAlgorithmException error) { throw new IOException(error); }
        verifiedKey = key;
        verifiedPath = binary.toString();
        return verifiedPath;
    }
}
