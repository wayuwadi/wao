package cn.wayuwa.wao.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Service;

/**
 * 构建时打包进服务端的 Runner 脚本（wao-ai-runner.py）。
 *
 * <p>后台点「更新 Runner」后，各执行电脑在心跳里收到更新指令，来这里拉取最新脚本，
 * 校验哈希后原地替换自身并重启，从而做到一键批量分发、不再逐台手动拷贝。
 * 哈希对脚本原始字节计算，与 Runner 本地对自身文件计算的口径一致。
 */
@Service
public class RunnerScriptService {

    static final String RESOURCE = "runner/wao-ai-runner.py";

    private final String content;
    private final String hash;

    public RunnerScriptService() {
        this(RESOURCE);
    }

    RunnerScriptService(String resource) {
        byte[] bytes = null;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in != null) bytes = in.readAllBytes();
        } catch (IOException ignored) {
            // 读不到时按「构建里没有打包脚本」处理，不影响服务启动。
        }
        this.content = bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        this.hash = bytes == null ? null : sha256(bytes);
    }

    /** 构建里是否打包了 Runner 脚本；本地 IDE 直跑时可能没有。 */
    public boolean available() {
        return content != null;
    }

    public String content() {
        return content;
    }

    /** 最新脚本的 SHA-256（十六进制小写）；脚本缺失时为 null。 */
    public String hash() {
        return hash;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
