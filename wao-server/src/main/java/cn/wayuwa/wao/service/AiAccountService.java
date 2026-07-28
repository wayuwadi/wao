package cn.wayuwa.wao.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.domain.AiAccountEntity;
import cn.wayuwa.wao.domain.AiOauthSessionEntity;
import cn.wayuwa.wao.dto.AiAccountCredentialsDto;
import cn.wayuwa.wao.dto.AiAccountDto;
import cn.wayuwa.wao.dto.AiAccountFormRequest;
import cn.wayuwa.wao.dto.AiAccountLoginStartDto;
import cn.wayuwa.wao.dto.AiAccountSyncResultDto;
import cn.wayuwa.wao.dto.CompleteAiAccountLoginRequest;
import cn.wayuwa.wao.dto.AiRunAccountDto;
import cn.wayuwa.wao.dto.StartAiAccountLoginRequest;
import cn.wayuwa.wao.mapper.AiAccountMapper;
import cn.wayuwa.wao.mapper.AiOauthSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
    /** AI 供应商限额窗口的中文说明；未收录的窗口直接显示原名。 */
    private static final Map<String, String> WINDOW_LABELS = Map.of(
            "session", "五小时会话额度", "five_hour", "五小时会话额度",
            "weekly_all", "七天额度", "seven_day", "七天额度",
            "weekly_opus", "七天 Opus 额度", "seven_day_opus", "七天 Opus 额度");

    /** access token 剩余不到这个时长就提前续期，避免刚下发给 Runner 就过期。 */
    private static final long REFRESH_MARGIN_SECONDS = 300;

    private final AiAccountMapper mapper;
    private final VaultCryptoService crypto;
    private final ObjectMapper objectMapper;
    private final AnthropicOAuthClient anthropic;
    private final OpenAiCodexOAuthClient codex;
    private final AiCustomApiClient customApi;
    private final AiOauthSessionMapper sessionMapper;

    public AiAccountService(AiAccountMapper mapper, VaultCryptoService crypto, ObjectMapper objectMapper,
            AnthropicOAuthClient anthropic, OpenAiCodexOAuthClient codex,
            AiCustomApiClient customApi, AiOauthSessionMapper sessionMapper) {
        this.mapper = mapper;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
        this.anthropic = anthropic;
        this.codex = codex;
        this.customApi = customApi;
        this.sessionMapper = sessionMapper;
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
        boolean custom = StringUtils.hasText(request.baseUrl());
        if (custom && !"codex".equals(request.provider().trim().toLowerCase())) {
            throw new BadRequestException("自定义 Responses API 目前只用于 Codex");
        }
        if (custom && !StringUtils.hasText(request.apiKey())) throw new BadRequestException("API Key 不能为空");
        if (!custom && !StringUtils.hasText(request.credentials())) throw new BadRequestException("凭据不能为空");
        if (!custom) validateJson(request.credentials());
        AiAccountEntity entity = new AiAccountEntity();
        entity.setId(id("aa"));
        entity.setProvider(request.provider().trim().toLowerCase());
        entity.setLabel(request.label().trim());
        entity.setEmail(trimToNull(request.email()));
        entity.setCredentialJson(encrypt(custom ? apiKeyJson(request.apiKey()) : request.credentials().trim()));
        entity.setEnabled(request.enabled() == null || request.enabled());
        entity.setQuotaStatus("UNKNOWN");
        entity.setAuthMethod(custom ? "api_key" : "manual");
        entity.setBaseUrl(custom ? customApi.normalizeBaseUrl(request.baseUrl()) : null);
        entity.setModel(custom ? requireModel(request.model()) : null);
        entity.setApiProtocol(custom ? "responses" : null);
        entity.setTokenExpiresAt(custom ? null : expiryOf(readTree(request.credentials().trim())));
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.insert(entity);
        return toDto(entity);
    }

    /**
     * 发起一次 OAuth 登录，返回让用户在浏览器里打开的授权地址。
     *
     * <p>用的是授权码 + PKCE：授权完成后 Anthropic 把码显示在回调页上，用户复制回 WAO 换取令牌。
     * 之所以不用「本地回环端口」那套，是因为浏览器在用户自己的电脑上，而 WAO 跑在 Linux 主节点，
     * 回环地址根本连不通；复制授权码这一步换来的是登录可以在任何一台机器的浏览器里完成。
     *
     * @param request  要登录哪个 provider，以及可选的账号显示名
     * @param memberId 发起登录的成员，仅用于审计
     */
    @Transactional
    public AiAccountLoginStartDto startLogin(StartAiAccountLoginRequest request, String memberId) {
        String provider = request.provider().trim().toLowerCase();
        validateProvider(provider);
        if (!"claude".equals(provider) && !"codex".equals(provider)) {
            throw new BadRequestException(label(provider) + " 暂不支持一键登录，请使用手工凭据方式添加");
        }
        purgeExpiredSessions();
        String state = "codex".equals(provider) ? codex.newState() : anthropic.newState();
        String verifier = "codex".equals(provider) ? codex.newCodeVerifier() : anthropic.newCodeVerifier();
        LocalDateTime now = LocalDateTime.now();
        AiOauthSessionEntity session = new AiOauthSessionEntity();
        session.setState(state);
        session.setProvider(provider);
        session.setCodeVerifier(verifier);
        session.setLabel(trimToNull(request.label()));
        session.setCreatedBy(memberId);
        session.setCreatedAt(now);
        session.setExpiresAt(now.plusMinutes(10));
        sessionMapper.insert(session);
        boolean codexLogin = "codex".equals(provider);
        return new AiAccountLoginStartDto(state,
                codexLogin ? codex.authorizeUrl(state, verifier) : anthropic.authorizeUrl(state, verifier),
                session.getExpiresAt().toString(), codexLogin
                        ? "完成授权后浏览器可能显示 localhost 无法连接，这是正常的。复制地址栏里的完整地址回来即可。"
                        : "在新窗口完成授权后，页面会显示一段授权码，把它整段复制回来即可。授权码十分钟内有效。");
    }

    /**
     * 用回填的授权码换取令牌并落库成一个可用账号。
     *
     * <p>换到令牌后立刻拉一次账号资料和额度，用户点完登录就能看到是哪个邮箱、什么套餐、还剩多少。
     */
    @Transactional
    public AiAccountDto completeLogin(CompleteAiAccountLoginRequest request) {
        AiOauthSessionEntity session = sessionMapper.selectById(request.state().trim());
        if (session == null || session.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BadRequestException("这次登录已过期，请重新点击登录");
        }
        if ("codex".equals(session.getProvider())) return completeCodexLogin(session, request.code());
        AnthropicOAuthClient.TokenSet tokens =
                anthropic.exchangeCode(request.code(), session.getCodeVerifier(), session.getState());
        sessionMapper.deleteById(session.getState());

        LocalDateTime expiresAt = tokens.expiresInSeconds() > 0
                ? LocalDateTime.now().plusSeconds(tokens.expiresInSeconds()) : null;
        AiAccountEntity entity = new AiAccountEntity();
        entity.setId(id("aa"));
        entity.setProvider("claude");
        entity.setEnabled(true);
        entity.setAuthMethod("oauth");
        entity.setQuotaStatus("UNKNOWN");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        entity.setTokenExpiresAt(expiresAt);
        entity.setCredentialJson(encrypt(claudeCredentialJson(tokens, expiresAt, null)));

        // 资料和额度失败不能让登录本身失败——令牌已经拿到了，账号必须存下来。
        try {
            JsonNode profile = anthropic.profile(tokens.accessToken());
            entity.setEmail(trimToNull(profile.at("/account/email").asText(null)));
            entity.setPlan(planOf(profile));
            entity.setCredentialJson(encrypt(claudeCredentialJson(tokens, expiresAt, entity.getPlan())));
        } catch (RuntimeException ex) {
            entity.setQuotaMessage("登录成功，但读取账号资料失败：" + ex.getMessage());
        }
        entity.setLabel(StringUtils.hasText(session.getLabel()) ? session.getLabel()
                : (StringUtils.hasText(entity.getEmail()) ? entity.getEmail() : "Claude 账号"));
        mapper.insert(entity);

        try {
            applyClaudeUsage(entity, tokens.accessToken());
            mapper.updateById(entity);
        } catch (RuntimeException ex) {
            // 额度拉不到不影响账号可用，下次手动同步即可。
            entity.setQuotaStatus("FAILED");
            entity.setQuotaMessage(limit("额度查询失败：" + ex.getMessage(), 500));
            mapper.updateById(entity);
        }
        return toDto(entity);
    }

    private AiAccountDto completeCodexLogin(AiOauthSessionEntity session, String rawCode) {
        OpenAiCodexOAuthClient.TokenSet tokens =
                codex.exchangeCode(rawCode, session.getCodeVerifier(), session.getState());
        sessionMapper.deleteById(session.getState());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = tokens.expiresInSeconds() > 0
                ? now.plusSeconds(tokens.expiresInSeconds()) : null;
        JsonNode claims = decodeJwtPayload(tokens.idToken());
        JsonNode profile = claims == null ? null : claims.get("https://api.openai.com/profile");
        JsonNode auth = claims == null ? null : claims.get("https://api.openai.com/auth");
        String email = claims == null ? null : trimToNull(claims.path("email").asText(null));
        if (!StringUtils.hasText(email) && profile != null) email = trimToNull(profile.path("email").asText(null));
        String plan = auth == null ? null : trimToNull(auth.path("chatgpt_plan_type").asText(null));
        String accountId = auth == null ? null : trimToNull(auth.path("chatgpt_account_id").asText(null));

        AiAccountEntity entity = new AiAccountEntity();
        entity.setId(id("aa"));
        entity.setProvider("codex");
        entity.setLabel(StringUtils.hasText(session.getLabel()) ? session.getLabel()
                : (StringUtils.hasText(email) ? email : "Codex 账号"));
        entity.setEmail(email);
        entity.setEnabled(true);
        entity.setAuthMethod("oauth");
        entity.setPlan(plan);
        entity.setQuotaStatus("UNKNOWN");
        entity.setQuotaMessage("Codex 登录成功，正在读取额度");
        entity.setTokenExpiresAt(expiresAt);
        entity.setCredentialJson(encrypt(codexCredentialJson(tokens, accountId, now)));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        try {
            applyCodexUsage(entity);
        } catch (RuntimeException ex) {
            entity.setQuotaStatus("FAILED");
            entity.setQuotaMessage(limit("额度查询失败：" + ex.getMessage(), 500));
        }
        entity.setLastSyncedAt(now);
        entity.setUpdatedAt(now);
        mapper.updateById(entity);
        return toDto(entity);
    }

    /** 清掉过期的登录会话，避免 state 表无限增长。 */
    private void purgeExpiredSessions() {
        sessionMapper.delete(new LambdaQueryWrapper<AiOauthSessionEntity>()
                .lt(AiOauthSessionEntity::getExpiresAt, LocalDateTime.now()));
    }

    @Transactional
    public AiAccountDto update(String id, AiAccountFormRequest request) {
        validateProvider(request.provider());
        AiAccountEntity entity = require(id);
        boolean custom = StringUtils.hasText(request.baseUrl()) || "api_key".equals(entity.getAuthMethod());
        if (custom && !"codex".equals(request.provider().trim().toLowerCase())) {
            throw new BadRequestException("自定义 Responses API 目前只用于 Codex");
        }
        entity.setProvider(request.provider().trim().toLowerCase());
        entity.setLabel(request.label().trim());
        entity.setEmail(trimToNull(request.email()));
        if (custom) {
            entity.setBaseUrl(customApi.normalizeBaseUrl(blankOr(request.baseUrl(), entity.getBaseUrl())));
            entity.setModel(requireModel(blankOr(request.model(), entity.getModel())));
            entity.setApiProtocol("responses");
            if (StringUtils.hasText(request.apiKey())) {
                entity.setCredentialJson(encrypt(apiKeyJson(request.apiKey())));
            } else if (!"api_key".equals(entity.getAuthMethod())) {
                throw new BadRequestException("API Key 不能为空");
            }
            entity.setAuthMethod("api_key");
            entity.setTokenExpiresAt(null);
        } else if (StringUtils.hasText(request.credentials())) {
            validateJson(request.credentials());
            entity.setCredentialJson(encrypt(request.credentials().trim()));
            // 换了凭据就等于重新手工登录了一次，过期时间和来源都要跟着改。
            entity.setAuthMethod("manual");
            entity.setTokenExpiresAt(expiryOf(readTree(request.credentials().trim())));
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
        if ("api_key".equals(entity.getAuthMethod())) {
            try {
                List<String> models = customApi.models(entity.getBaseUrl(), apiKeyOf(entity));
                entity.setQuotaStatus("SYNCED");
                entity.setQuotaMessage(models.isEmpty() ? "连接正常，但 /models 没有返回模型"
                        : "连接正常，可用 " + models.size() + " 个模型");
                entity.setQuotaRawJson(objectMapper.valueToTree(models).toString());
            } catch (RuntimeException ex) {
                applyOutcome(entity, failedOutcome(ex.getMessage()));
            }
        } else if ("claude".equals(entity.getProvider())) {
            try {
                applyClaudeUsage(entity, accessTokenFor(entity));
            } catch (RuntimeException ex) {
                applyOutcome(entity, failedOutcome(ex.getMessage()));
            }
        } else if ("codex".equals(entity.getProvider())) {
            try {
                applyCodexUsage(entity);
            } catch (RuntimeException ex) {
                // 刷新失败只标记本次尝试，保留上一次真实快照，避免网络抖动把可用额度信息抹掉。
                entity.setQuotaStatus("FAILED");
                entity.setQuotaMessage(limit(ex.getMessage(), 500));
            }
        } else {
            String credentials = decrypt(entity.getCredentialJson());
            SyncOutcome outcome = switch (entity.getProvider()) {
                case "qoder" -> syncQoder(credentials);
                default -> throw new BadRequestException("不支持的 provider：" + entity.getProvider());
            };
            applyOutcome(entity, outcome);
        }
        entity.setLastSyncedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
        return new AiAccountSyncResultDto(entity.getId(), entity.getProvider(), entity.getQuotaStatus(),
                entity.getQuotaMessage(), entity.getQuotaRawJson(), entity.getLastSyncedAt().toString());
    }

    private void applyOutcome(AiAccountEntity entity, SyncOutcome outcome) {
        entity.setQuotaStatus(outcome.status);
        entity.setQuotaMessage(limit(outcome.message, 500));
        entity.setQuotaRawJson(outcome.rawJson);
    }

    /**
     * 拉取 Claude 的账号资料与用量额度并写进实体。
     *
     * <p>Anthropic 的 {@code /api/oauth/usage} 会返回若干个限额窗口（五小时会话窗口、七天窗口等），
     * 页面上只展示「最紧张的那一条」——用户真正关心的是还能不能接着跑，而不是六个百分比。
     * 原始响应整份留在 {@code quotaRawJson} 里，需要细看时可以展开。
     */
    private void applyClaudeUsage(AiAccountEntity entity, String accessToken) {
        JsonNode profile = anthropic.profile(accessToken);
        String email = trimToNull(profile.at("/account/email").asText(null));
        if (StringUtils.hasText(email)) entity.setEmail(email);
        entity.setPlan(planOf(profile));

        JsonNode usage = anthropic.usage(accessToken);
        entity.setQuotaRawJson(usage == null ? null : usage.toString());

        Window tightest = tightestWindow(usage);
        if (tightest == null) {
            entity.setQuotaStatus("NO_API");
            entity.setQuotaMessage("Anthropic 未返回可解析的限额窗口");
            entity.setQuotaPercent(null);
            entity.setQuotaWindow(null);
            entity.setQuotaResetsAt(null);
            return;
        }
        entity.setQuotaStatus("SYNCED");
        entity.setQuotaWindow(tightest.name);
        entity.setQuotaPercent(tightest.percent);
        entity.setQuotaResetsAt(tightest.resetsAt);
        entity.setQuotaMessage(limit(describe(tightest, usage), 500));
    }

    /** 在所有限额窗口里挑已用百分比最高的一条。 */
    private Window tightestWindow(JsonNode usage) {
        if (usage == null) return null;
        Window best = null;
        JsonNode limits = usage.path("limits");
        if (limits.isArray()) {
            for (JsonNode limit : limits) {
                Integer percent = intOrNull(limit.path("percent"));
                if (percent == null) continue;
                Window window = new Window(blankOr(limit.path("kind").asText(null), "limit"),
                        percent, parseInstant(limit.path("resets_at").asText(null)));
                if (best == null || window.percent > best.percent) best = window;
            }
        }
        if (best != null) return best;
        // 老响应里没有 limits 数组时，退回到两个具名窗口。
        for (String name : List.of("five_hour", "seven_day")) {
            JsonNode node = usage.path(name);
            Integer percent = intOrNull(node.path("utilization"));
            if (percent == null) continue;
            Window window = new Window(name, percent, parseInstant(node.path("resets_at").asText(null)));
            if (best == null || window.percent > best.percent) best = window;
        }
        return best;
    }

    private String describe(Window window, JsonNode usage) {
        StringBuilder text = new StringBuilder(WINDOW_LABELS.getOrDefault(window.name, window.name))
                .append("已用 ").append(window.percent).append('%');
        if (window.resetsAt != null) text.append("，").append(window.resetsAt).append(" 重置");
        JsonNode extra = usage == null ? null : usage.path("extra_usage");
        if (extra != null && extra.isObject() && extra.path("is_enabled").asBoolean(false)) {
            JsonNode used = extra.path("used_credits");
            if (used.isNumber()) text.append("；额外用量已消耗 ").append(used.asDouble()).append(" 额度");
        }
        return text.toString();
    }

    /** 使用 Codex OAuth 登录态读取 ChatGPT 返回的主次额度窗口。 */
    private void applyCodexUsage(AiAccountEntity entity) {
        refreshCodexIfNeeded(entity);
        JsonNode credentials = readTree(decrypt(entity.getCredentialJson()));
        String accessToken = trimToNull(credentials.at("/tokens/access_token").asText(null));
        String accountId = trimToNull(credentials.at("/tokens/account_id").asText(null));
        if (!StringUtils.hasText(accessToken)) {
            throw new BadRequestException("Codex 凭据缺少 access token，请重新登录");
        }

        JsonNode usage = codex.usage(accessToken, accountId);
        entity.setQuotaRawJson(usage == null ? null : usage.toString());
        String plan = usage == null ? null : trimToNull(usage.path("plan_type").asText(null));
        if (StringUtils.hasText(plan)) entity.setPlan(plan);

        Window tightest = codexWindows(usage).stream()
                .max(Comparator.comparingInt(Window::percent)).orElse(null);
        if (tightest == null) {
            entity.setQuotaStatus("NO_API");
            entity.setQuotaMessage("Codex 额度服务未返回可解析的限额窗口");
            entity.setQuotaWindow(null);
            entity.setQuotaPercent(null);
            entity.setQuotaResetsAt(null);
            return;
        }
        entity.setQuotaStatus("SYNCED");
        entity.setQuotaWindow(tightest.name);
        entity.setQuotaPercent(tightest.percent);
        entity.setQuotaResetsAt(tightest.resetsAt);
        entity.setQuotaMessage(limit(WINDOW_LABELS.getOrDefault(tightest.name, tightest.name)
                + "已用 " + tightest.percent + "%"
                + (tightest.resetsAt == null ? "" : "，" + tightest.resetsAt + " 重置"), 500));
    }

    private List<Window> codexWindows(JsonNode usage) {
        List<Window> windows = new ArrayList<>();
        if (usage == null) return windows;
        collectCodexRateLimit(windows, usage.path("rate_limit"));
        JsonNode additional = usage.path("additional_rate_limits");
        if (additional.isArray()) {
            for (JsonNode item : additional) collectCodexRateLimit(windows, item.path("rate_limit"));
        }
        return windows;
    }

    private void collectCodexRateLimit(List<Window> windows, JsonNode rateLimit) {
        addCodexWindow(windows, rateLimit.path("primary_window"), "five_hour");
        addCodexWindow(windows, rateLimit.path("secondary_window"), "weekly_all");
    }

    private void addCodexWindow(List<Window> windows, JsonNode node, String fallbackName) {
        Integer percent = intOrNull(node.path("used_percent"));
        if (percent == null) return;
        long seconds = node.path("limit_window_seconds").asLong(0);
        String name = seconds == 0 ? fallbackName
                : Math.abs(seconds - 18_000) <= Math.abs(seconds - 604_800) ? "five_hour" : "weekly_all";
        windows.add(new Window(name, percent, parseReset(node.path("reset_at"))));
    }

    private static String planOf(JsonNode profile) {
        if (profile == null) return null;
        if (profile.at("/account/has_claude_max").asBoolean(false)) return "max";
        if (profile.at("/account/has_claude_pro").asBoolean(false)) return "pro";
        String type = profile.at("/organization/organization_type").asText(null);
        return StringUtils.hasText(type) ? type : null;
    }

    private record Window(String name, int percent, LocalDateTime resetsAt) {
    }

    /** Runner 专用：返回启用中的官方/手工 CLI 凭据；自定义 API 不写全局 auth 文件。 */
    public List<AiAccountCredentialsDto> enabledCredentials() {
        return mapper.selectList(new LambdaQueryWrapper<AiAccountEntity>()
                .eq(AiAccountEntity::getEnabled, true)
                .ne(AiAccountEntity::getAuthMethod, "api_key")).stream()
                .map(entity -> new AiAccountCredentialsDto(entity.getProvider(), freshCredential(entity)))
                .toList();
    }

    /** 解析一次运行选中的账号；凭据只交给已经领取该运行的 Runner。 */
    public AiRunAccountDto runAccount(String accountId, String engine) {
        if (!StringUtils.hasText(accountId)) return null;
        AiAccountEntity entity = require(accountId);
        if (!Boolean.TRUE.equals(entity.getEnabled())) throw new BadRequestException("选择的 AI 账号已停用");
        if (!entity.getProvider().equals(engine)) {
            throw new BadRequestException("选择的账号不属于 " + label(engine));
        }
        if ("api_key".equals(entity.getAuthMethod())) {
            return new AiRunAccountDto(entity.getId(), entity.getProvider(), entity.getLabel(),
                    entity.getAuthMethod(), null, apiKeyOf(entity), entity.getBaseUrl(), entity.getModel(),
                    blankOr(entity.getApiProtocol(), "responses"));
        }
        return new AiRunAccountDto(entity.getId(), entity.getProvider(), entity.getLabel(),
                entity.getAuthMethod(), freshCredential(entity), null, null, null, null);
    }

    /**
     * 下发给 Runner 前先确保令牌没过期。
     *
     * <p>续期失败（凭据是手工粘贴的、字段对不上、refresh token 已作废）时原样下发已存的凭据：
     * 这一步是「尽量把新鲜的给出去」，不是准入校验。一个账号续不了期，不该连累同一批的其他账号，
     * 也不该让 Runner 拿不到任何凭据——真过期了，任务失败时的报错比这里静默拦截更容易排查。
     */
    private String freshCredential(AiAccountEntity entity) {
        if ("claude".equals(entity.getProvider())) {
            try {
                accessTokenFor(entity);
            } catch (RuntimeException ignored) {
                // 保持沉默：下面照常返回已存的凭据。
            }
        } else if ("codex".equals(entity.getProvider()) && "oauth".equals(entity.getAuthMethod())) {
            try {
                refreshCodexIfNeeded(entity);
            } catch (RuntimeException ignored) {
                // 和 Claude 一样：续期失败时仍下发旧凭据，让实际运行给出明确错误。
            }
        }
        return decrypt(entity.getCredentialJson());
    }

    private String apiKeyOf(AiAccountEntity entity) {
        String value = readTree(decrypt(entity.getCredentialJson())).path("apiKey").asText(null);
        if (!StringUtils.hasText(value)) throw new BadRequestException("自定义 API 没有可用的 API Key");
        return value;
    }

    /**
     * 取出这个账号当前可用的 access token，必要时先续期。
     *
     * <p>续期后新的令牌会立刻加密回写，所以下一次下发给 Runner 的凭据一定是新鲜的——
     * 否则 Runner 会拿着一个刚过期的令牌去跑任务，失败原因还很难看出来。
     */
    private String accessTokenFor(AiAccountEntity entity) {
        JsonNode root = readTree(decrypt(entity.getCredentialJson()));
        String token = findToken(root, "accessToken", "access_token", "api_key");
        if (!StringUtils.hasText(token)) {
            throw new BadRequestException("凭据中找不到 accessToken，请重新登录这个账号");
        }
        LocalDateTime expiry = entity.getTokenExpiresAt() == null ? expiryOf(root) : entity.getTokenExpiresAt();
        boolean expiring = expiry != null && expiry.isBefore(LocalDateTime.now().plusSeconds(REFRESH_MARGIN_SECONDS));
        if (!expiring) return token;

        String refreshToken = findToken(root, "refreshToken", "refresh_token");
        if (!StringUtils.hasText(refreshToken)) {
            throw new BadRequestException("凭据已过期且没有 refresh token，请重新登录这个账号");
        }
        AnthropicOAuthClient.TokenSet tokens = anthropic.refresh(refreshToken);
        LocalDateTime newExpiry = tokens.expiresInSeconds() > 0
                ? LocalDateTime.now().plusSeconds(tokens.expiresInSeconds()) : null;
        // Anthropic 不一定每次都换新的 refresh token，没换就继续用旧的。
        AnthropicOAuthClient.TokenSet merged = new AnthropicOAuthClient.TokenSet(tokens.accessToken(),
                StringUtils.hasText(tokens.refreshToken()) ? tokens.refreshToken() : refreshToken,
                tokens.expiresInSeconds(), tokens.scope());
        entity.setCredentialJson(encrypt(claudeCredentialJson(merged, newExpiry, entity.getPlan())));
        entity.setTokenExpiresAt(newExpiry);
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
        return merged.accessToken();
    }

    /**
     * 拼出 Claude Code 本机凭据文件的格式。
     *
     * <p>字段名必须和 {@code ~/.claude/.credentials.json} 完全一致：Runner 会把这份 JSON 原样
     * 写到该路径，CLI 直接读它来认证。格式对不上，任务跑起来才会报未登录。
     */
    private String claudeCredentialJson(AnthropicOAuthClient.TokenSet tokens, LocalDateTime expiresAt, String plan) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode oauth = root.putObject("claudeAiOauth");
        oauth.put("accessToken", tokens.accessToken());
        if (StringUtils.hasText(tokens.refreshToken())) oauth.put("refreshToken", tokens.refreshToken());
        if (expiresAt != null) {
            oauth.put("expiresAt", expiresAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        ArrayNode scopes = oauth.putArray("scopes");
        for (String scope : blankOr(tokens.scope(), "").split("\\s+")) {
            if (StringUtils.hasText(scope)) scopes.add(scope);
        }
        if (StringUtils.hasText(plan)) oauth.put("subscriptionType", plan);
        return root.toString();
    }

    /** 生成 Codex CLI 当前识别的 {@code $CODEX_HOME/auth.json}。 */
    private String codexCredentialJson(OpenAiCodexOAuthClient.TokenSet tokens,
            String accountId, LocalDateTime refreshedAt) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("auth_mode", "chatgpt");
        root.putNull("OPENAI_API_KEY");
        ObjectNode tokenNode = root.putObject("tokens");
        tokenNode.put("id_token", tokens.idToken());
        tokenNode.put("access_token", tokens.accessToken());
        if (StringUtils.hasText(tokens.refreshToken())) tokenNode.put("refresh_token", tokens.refreshToken());
        if (StringUtils.hasText(accountId)) tokenNode.put("account_id", accountId);
        root.put("last_refresh", refreshedAt.atZone(ZoneId.systemDefault()).toInstant().toString());
        return root.toString();
    }

    private void refreshCodexIfNeeded(AiAccountEntity entity) {
        LocalDateTime expiry = entity.getTokenExpiresAt();
        if (expiry == null || !expiry.isBefore(LocalDateTime.now().plusSeconds(REFRESH_MARGIN_SECONDS))) return;
        JsonNode root = readTree(decrypt(entity.getCredentialJson()));
        String refreshToken = root.at("/tokens/refresh_token").asText(null);
        if (!StringUtils.hasText(refreshToken)) {
            throw new BadRequestException("Codex 凭据已过期且没有 refresh token，请重新登录");
        }
        OpenAiCodexOAuthClient.TokenSet refreshed = codex.refresh(refreshToken);
        String mergedRefresh = StringUtils.hasText(refreshed.refreshToken())
                ? refreshed.refreshToken() : refreshToken;
        OpenAiCodexOAuthClient.TokenSet merged = new OpenAiCodexOAuthClient.TokenSet(
                refreshed.idToken(), refreshed.accessToken(), mergedRefresh, refreshed.expiresInSeconds());
        JsonNode claims = decodeJwtPayload(refreshed.idToken());
        JsonNode auth = claims == null ? null : claims.get("https://api.openai.com/auth");
        String accountId = auth == null ? root.at("/tokens/account_id").asText(null)
                : blankOr(auth.path("chatgpt_account_id").asText(null), root.at("/tokens/account_id").asText(null));
        LocalDateTime now = LocalDateTime.now();
        entity.setCredentialJson(encrypt(codexCredentialJson(merged, accountId, now)));
        entity.setTokenExpiresAt(refreshed.expiresInSeconds() > 0
                ? now.plusSeconds(refreshed.expiresInSeconds()) : null);
        entity.setUpdatedAt(now);
        mapper.updateById(entity);
    }

    private String apiKeyJson(String apiKey) {
        return objectMapper.createObjectNode().put("apiKey", apiKey.trim()).toString();
    }

    private static String requireModel(String model) {
        if (!StringUtils.hasText(model)) throw new BadRequestException("请填写自定义 API 默认模型");
        return model.trim();
    }

    /** 从凭据 JSON 里读出 access token 的过期时间（毫秒时间戳）。 */
    private static LocalDateTime expiryOf(JsonNode root) {
        JsonNode node = root == null ? null : root.at("/claudeAiOauth/expiresAt");
        if (node == null || !node.isNumber()) return null;
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(node.asLong()), ZoneId.systemDefault());
    }

    private static LocalDateTime parseInstant(String text) {
        if (!StringUtils.hasText(text)) return null;
        try {
            return OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Integer intOrNull(JsonNode node) {
        return node != null && node.isNumber() ? (int) Math.round(node.asDouble()) : null;
    }

    private static LocalDateTime parseReset(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isNumber()) {
            long value = node.asLong();
            Instant instant = value > 10_000_000_000L ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        }
        return parseInstant(node.asText(null));
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
                blankOr(entity.getAuthMethod(), "manual"),
                entity.getPlan(),
                entity.getQuotaStatus(),
                entity.getQuotaMessage(),
                entity.getQuotaWindow(),
                entity.getQuotaPercent(),
                text(entity.getQuotaResetsAt()),
                entity.getQuotaRawJson(),
                text(entity.getTokenExpiresAt()),
                text(entity.getLastSyncedAt()),
                text(entity.getCreatedAt()),
                entity.getBaseUrl(),
                entity.getModel(),
                entity.getApiProtocol());
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

    private static String blankOr(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private static String label(String provider) {
        return PROVIDER_LABELS.getOrDefault(provider, provider);
    }

    private static String text(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private record SyncOutcome(String status, String message, String rawJson) {
    }
}
