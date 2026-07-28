package cn.wayuwa.wao.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.domain.AiAccountEntity;
import cn.wayuwa.wao.domain.AiRunnerNodeEntity;
import cn.wayuwa.wao.dto.AiAccountCredentialsDto;
import cn.wayuwa.wao.dto.AiAccountDto;
import cn.wayuwa.wao.dto.AiAccountFormRequest;
import cn.wayuwa.wao.dto.AiAccountSyncResultDto;
import cn.wayuwa.wao.mapper.AiAccountMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 集中管理 AI 工具账号。
 *
 * <p>凭据用 {@link VaultCryptoService} AES-256-GCM 加密后落库，明文只在服务端按需解密：
 * 设置页永远不回传凭据，已配对的 Runner 通过专用接口拉取明文凭据以写入本机 CLI 配置文件。
 * 额度/余额查询只使用真实存在的接口；没有可靠接口时直接标记 {@code NO_API}，不伪造数字。
 */
@Service
public class AiAccountService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ENC_PREFIX = "enc:";
    private static final List<String> VALID_PROVIDERS = List.of("codex", "claude", "qoder");
    private static final Map<String, String> PROVIDER_LABELS = Map.of("codex", "Codex / ChatGPT",
            "claude", "Claude Code", "qoder", "Qoder");

    private final AiAccountMapper mapper;
    private final VaultCryptoService crypto;
    private final AiRunService aiRunService;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String claudeUsageUrl;

    public AiAccountService(AiAccountMapper mapper, VaultCryptoService crypto,
            AiRunService aiRunService, RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${wao.ai.claude.usage-url:}") String claudeUsageUrl) {
        this.mapper = mapper;
        this.crypto = crypto;
        this.aiRunService = aiRunService;
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.claudeUsageUrl = claudeUsageUrl;
    }

    public List<AiAccountDto> list() {
        return mapper.selectList(null).stream()
                .sorted(Comparator.comparing(AiAccountEntity::getCreatedAt).reversed())
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public AiAccountDto create(AiAccountFormRequest request) {
        validateProvider(request.provider());
        if (!StringUtils.hasText(request.credentials())) {
            throw new BadRequestException("凭据不能为空");
        }
        validateJson(request.credentials());
        AiAccountEntity entity = new AiAccountEntity();
        entity.setId(id("aa"));
        entity.setProvider(request.provider().trim().toLowerCase());
        entity.setLabel(request.label().trim());
        entity.setEmail(trimToNull(request.email()));
        entity.setCredentialJson(encrypt(request.credentials().trim()));
        entity.setEnabled(request.enabled() == null || request.enabled());
        entity.setQuotaStatus("UNKNOWN");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.insert(entity);
        return toDto(entity);
    }

    @Transactional
    public AiAccountDto update(String id, AiAccountFormRequest request) {
        validateProvider(request.provider());
        AiAccountEntity entity = require(id);
        entity.setProvider(request.provider().trim().toLowerCase());
        entity.setLabel(request.label().trim());
        entity.setEmail(trimToNull(request.email()));
        if (StringUtils.hasText(request.credentials())) {
            validateJson(request.credentials());
            entity.setCredentialJson(encrypt(request.credentials().trim()));
        }
        if (request.enabled() != null) {
            entity.setEnabled(request.enabled());
        }
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
        return toDto(entity);
    }

    @Transactional
    public void delete(String id) {
        AiAccountEntity entity = require(id);
        mapper.deleteById(entity.getId());
    }

    @Transactional
    public AiAccountSyncResultDto sync(String id) {
        AiAccountEntity entity = require(id);
        String credentials = decrypt(entity.getCredentialJson());
        SyncOutcome outcome;
        switch (entity.getProvider()) {
            case "claude" -> outcome = syncClaude(credentials);
            case "codex" -> outcome = syncCodex(credentials);
            case "qoder" -> outcome = syncQoder(credentials);
            default -> throw new BadRequestException("不支持的 provider：" + entity.getProvider());
        }
        entity.setQuotaStatus(outcome.status);
        entity.setQuotaMessage(limit(outcome.message, 500));
        entity.setQuotaRawJson(outcome.rawJson);
        entity.setLastSyncedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
        return new AiAccountSyncResultDto(entity.getId(), entity.getProvider(), outcome.status,
                outcome.message, outcome.rawJson, entity.getLastSyncedAt().toString());
    }

    /**
     * Runner 专用：返回所有启用账号的明文凭据 JSON。
     *
     * <p>调用方必须持有有效的 Runner 令牌；响应通过 HTTPS 传输，Runner 写入本机 CLI 凭据文件。
     */
    public List<AiAccountCredentialsDto> enabledCredentials(String runnerToken) {
        AiRunnerNodeEntity runner = aiRunService.authenticateRunner(runnerToken);
        if (runner == null) {
            throw new UnauthorizedException("执行节点令牌无效");
        }
        return mapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiAccountEntity>()
                .eq(AiAccountEntity::getEnabled, true)).stream()
                .map(entity -> new AiAccountCredentialsDto(entity.getProvider(), decrypt(entity.getCredentialJson())))
                .toList();
    }

    private SyncOutcome syncClaude(String credentials) {
        JsonNode root = readTree(credentials);
        String email = extractEmail(root);
        if (!StringUtils.hasText(claudeUsageUrl)) {
            return noApiOutcome(email, "未配置 Claude 额度查询地址（wao.ai.claude.usage-url）");
        }
        String token = findToken(root, "accessToken", "access_token", "id_token", "api_key");
        if (!StringUtils.hasText(token)) {
            return failedOutcome("凭据中找不到 accessToken / access_token / id_token / api_key");
        }
        try {
            String body = restClient.get()
                    .uri(claudeUsageUrl)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(String.class);
            return new SyncOutcome("SYNCED", "已同步 Claude 额度快照", body);
        } catch (RestClientException ex) {
            return failedOutcome("Claude 额度接口调用失败：" + ex.getMessage());
        }
    }

    private SyncOutcome syncCodex(String credentials) {
        JsonNode root = readTree(credentials);
        String email = extractEmail(root);
        // Codex / ChatGPT 没有稳定、官方、面向 Codex CLI 登录态的余额查询接口；
        // OpenAI dashboard billing 接口已废弃且需要 API key，不在这里伪造余额数字。
        return noApiOutcome(email, "Codex / ChatGPT 暂无可靠的程序化余额接口，仅记录账号标识");
    }

    private SyncOutcome syncQoder(String credentials) {
        JsonNode root = readTree(credentials);
        String email = extractEmail(root);
        return noApiOutcome(email, "Qoder 暂无公开的额度查询 API，仅记录账号标识");
    }

    private SyncOutcome noApiOutcome(String email, String reason) {
        String message = reason;
        if (StringUtils.hasText(email)) {
            message = "账号 " + email + "：" + reason;
        }
        return new SyncOutcome("NO_API", message, null);
    }

    private SyncOutcome failedOutcome(String reason) {
        return new SyncOutcome("FAILED", reason, null);
    }

    private String extractEmail(JsonNode root) {
        // 1. 直接在 JSON 中找常见 email 字段
        for (String field : List.of("email", "account_email", "user_email", "mail")) {
            JsonNode node = root.at("/" + field);
            if (node.isMissingNode()) {
                node = findField(root, field);
            }
            if (node != null && node.isTextual() && StringUtils.hasText(node.asText())) {
                return node.asText().trim();
            }
        }
        // 2. 从 JWT id_token 的 payload 中解析 email / sub
        String idToken = findToken(root, "id_token", "idToken", "tokens/id_token");
        if (StringUtils.hasText(idToken)) {
            JsonNode payload = decodeJwtPayload(idToken);
            if (payload != null) {
                JsonNode emailNode = payload.path("email");
                if (emailNode.isTextual() && StringUtils.hasText(emailNode.asText())) {
                    return emailNode.asText().trim();
                }
            }
        }
        return null;
    }

    private JsonNode findField(JsonNode root, String fieldName) {
        if (root.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (entry.getKey().equalsIgnoreCase(fieldName)) {
                    return entry.getValue();
                }
                JsonNode found = findField(entry.getValue(), fieldName);
                if (found != null) {
                    return found;
                }
            }
        } else if (root.isArray()) {
            for (JsonNode item : root) {
                JsonNode found = findField(item, fieldName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private String findToken(JsonNode root, String... fieldNames) {
        for (String name : fieldNames) {
            if (name.contains("/")) {
                JsonNode node = root.at("/" + name.replace('/', '/'));
                if (!node.isMissingNode() && node.isTextual() && StringUtils.hasText(node.asText())) {
                    return node.asText().trim();
                }
            }
            JsonNode found = findField(root, name);
            if (found != null && found.isTextual() && StringUtils.hasText(found.asText())) {
                return found.asText().trim();
            }
        }
        return null;
    }

    private JsonNode decodeJwtPayload(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            return null;
        }
    }

    private AiAccountEntity require(String id) {
        AiAccountEntity entity = mapper.selectById(id);
        if (entity == null) {
            throw new NotFoundException("AI 账号不存在：" + id);
        }
        return entity;
    }

    private void validateProvider(String provider) {
        if (!VALID_PROVIDERS.contains(provider.trim().toLowerCase())) {
            throw new BadRequestException("不支持的 AI 工具：" + provider);
        }
    }

    private void validateJson(String value) {
        try {
            objectMapper.readTree(value);
        } catch (Exception ex) {
            throw new BadRequestException("凭据不是合法的 JSON：" + ex.getMessage());
        }
    }

    private JsonNode readTree(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private AiAccountDto toDto(AiAccountEntity entity) {
        return new AiAccountDto(
                entity.getId(),
                entity.getProvider(),
                entity.getLabel(),
                entity.getEmail(),
                Boolean.TRUE.equals(entity.getEnabled()),
                entity.getQuotaStatus(),
                entity.getQuotaMessage(),
                entity.getQuotaRawJson(),
                entity.getLastSyncedAt() == null ? null : entity.getLastSyncedAt().toString(),
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString());
    }

    private String encrypt(String plaintext) {
        VaultCryptoService.Encrypted enc = crypto.encrypt(plaintext);
        return ENC_PREFIX + Base64.getEncoder().encodeToString(enc.nonce())
                + ":" + Base64.getEncoder().encodeToString(enc.ciphertext());
    }

    private String decrypt(String stored) {
        if (stored == null || !stored.startsWith(ENC_PREFIX)) {
            return stored;
        }
        String[] parts = stored.substring(ENC_PREFIX.length()).split(":", 2);
        if (parts.length != 2) {
            return "";
        }
        byte[] nonce = Base64.getDecoder().decode(parts[0]);
        byte[] ciphertext = Base64.getDecoder().decode(parts[1]);
        return crypto.decrypt(ciphertext, nonce);
    }

    private static String id(String prefix) {
        return prefix + Long.toString(System.currentTimeMillis(), 36)
                + Integer.toString(RANDOM.nextInt(1_679_616), 36);
    }

    private static String trimToNull(String value) {
        return value == null ? null : (value.isBlank() ? null : value.trim());
    }

    private static String limit(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private record SyncOutcome(String status, String message, String rawJson) {
    }
}
