package cn.wayuwa.wao.agent.service;

import cn.wayuwa.wao.agent.common.BadRequestException;
import cn.wayuwa.wao.agent.domain.PlatformAccountEntity;
import cn.wayuwa.wao.agent.dto.PlatformAccountDto;
import cn.wayuwa.wao.agent.dto.PlatformSummaryDto;
import cn.wayuwa.wao.agent.mapper.PlatformAccountMapper;
import cn.wayuwa.wao.agent.platform.CredentialCipher;
import cn.wayuwa.wao.agent.platform.PlatformCapability;
import cn.wayuwa.wao.agent.platform.PlatformCredential;
import cn.wayuwa.wao.agent.platform.PlatformIdentity;
import cn.wayuwa.wao.agent.platform.PlatformRegistry;
import cn.wayuwa.wao.agent.platform.PlatformType;
import cn.wayuwa.wao.agent.platform.PublishPlatform;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.servlet.http.HttpSession;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 平台账号接管：OAuth 授权、凭据加密落库、令牌刷新与解绑。 */
@Service
public class PlatformAccountService {
    private static final Logger log = LoggerFactory.getLogger(PlatformAccountService.class);
    private static final String STATE_PREFIX = "wao-agent.oauth.state.";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_EXPIRED = "EXPIRED";

    private final SharedAccountService accounts;
    private final PlatformAccountMapper accountMapper;
    private final PlatformRegistry registry;
    private final CredentialCipher cipher;
    private final SecureRandom random = new SecureRandom();

    public PlatformAccountService(SharedAccountService accounts, PlatformAccountMapper accountMapper,
                                  PlatformRegistry registry, CredentialCipher cipher) {
        this.accounts = accounts;
        this.accountMapper = accountMapper;
        this.registry = registry;
        this.cipher = cipher;
    }

    /** 返回全部平台的能力声明与该工作区已绑定的账号。 */
    public List<PlatformSummaryDto> summaries(String accountId) {
        String workspaceId = accounts.workspaceId(accountId);
        List<PlatformAccountEntity> bound = accountMapper.selectList(Wrappers.<PlatformAccountEntity>lambdaQuery()
                .eq(PlatformAccountEntity::getWorkspaceId, workspaceId)
                .orderByAsc(PlatformAccountEntity::getCreatedAt));
        return registry.all().stream().map(platform -> {
            PlatformCapability capability = withCipherState(platform.capability());
            List<PlatformAccountDto> list = bound.stream()
                    .filter(entity -> entity.getPlatform().equals(platform.type().name()))
                    .map(this::toDto).toList();
            return new PlatformSummaryDto(platform.type().name(), platform.type().label(),
                    capability.oauthSupported(), capability.publishSupported(),
                    capability.mediaKinds(), capability.note(), list);
        }).toList();
    }

    /** 生成平台授权地址，并把防伪 state 存进共享 Session。 */
    public String authorizeUrl(String accountId, String platformRaw, HttpSession session) {
        PlatformType type = PlatformType.parse(platformRaw);
        accounts.workspaceId(accountId);
        PublishPlatform platform = registry.get(type);
        PlatformCapability capability = withCipherState(platform.capability());
        if (!capability.oauthSupported()) {
            throw new BadRequestException(capability.note());
        }
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        session.setAttribute(STATE_PREFIX + type.name(), state);
        return platform.authorizeUrl(state);
    }

    /** 处理平台回调：校验 state、换取凭据并加密落库。 */
    public PlatformAccountDto handleCallback(String accountId, String platformRaw, String code,
                                             String state, HttpSession session) {
        PlatformType type = PlatformType.parse(platformRaw);
        String expected = (String) session.getAttribute(STATE_PREFIX + type.name());
        session.removeAttribute(STATE_PREFIX + type.name());
        if (!StringUtils.hasText(expected) || !expected.equals(state)) {
            throw new BadRequestException("授权状态校验失败，请重新发起授权");
        }
        if (!StringUtils.hasText(code)) {
            throw new BadRequestException("平台没有返回授权码，请重新授权");
        }
        String workspaceId = accounts.workspaceId(accountId);
        PlatformIdentity identity = registry.get(type).exchangeCode(code);
        return toDto(upsert(workspaceId, type, identity));
    }

    /** 解绑账号。已引用该账号的历史任务保留记录，因此只删账号行。 */
    public void unbind(String accountId, String platformAccountId) {
        String workspaceId = accounts.workspaceId(accountId);
        PlatformAccountEntity entity = accountMapper.selectById(platformAccountId);
        if (entity == null || !workspaceId.equals(entity.getWorkspaceId())) {
            throw new BadRequestException("平台账号不存在");
        }
        accountMapper.deleteById(platformAccountId);
    }

    /** 读取账号并按需刷新令牌；刷新失败会把账号标记为需重新授权。 */
    public PlatformCredential activeCredential(PlatformAccountEntity entity) {
        PlatformType type = PlatformType.valueOf(entity.getPlatform());
        PlatformCredential credential = cipher.decrypt(entity.getCredentialCipher());
        LocalDateTime now = LocalDateTime.now();
        if (!credential.needsRefresh(now)) return credential;
        if (credential.refreshExpired(now)) {
            markExpired(entity, "平台授权已过期，请重新授权");
            throw new BadRequestException("「" + entity.getDisplayName() + "」的授权已过期，请重新授权");
        }
        try {
            PlatformIdentity refreshed = registry.get(type).refresh(credential);
            PlatformCredential next = refreshed.credential();
            entity.setCredentialCipher(cipher.encrypt(next));
            entity.setAccessTokenExpiresAt(next.accessTokenExpiresAt());
            entity.setRefreshTokenExpiresAt(next.refreshTokenExpiresAt());
            entity.setStatus(STATUS_ACTIVE);
            entity.setLastError(null);
            entity.setUpdatedAt(now);
            accountMapper.updateById(entity);
            log.info("已刷新平台令牌 platform={} account={}", entity.getPlatform(), entity.getId());
            return next;
        } catch (RuntimeException ex) {
            markExpired(entity, "刷新令牌失败：" + ex.getMessage());
            throw new BadRequestException("「" + entity.getDisplayName() + "」刷新授权失败，请重新授权");
        }
    }

    /** 按 id 读取本工作区的平台账号。 */
    public PlatformAccountEntity require(String workspaceId, String platformAccountId) {
        PlatformAccountEntity entity = accountMapper.selectById(platformAccountId);
        if (entity == null || !workspaceId.equals(entity.getWorkspaceId())) {
            throw new BadRequestException("平台账号不存在或不属于当前工作区");
        }
        return entity;
    }

    /**
     * 没有加密密钥就不能安全保存令牌，因此把接管能力降级为不可用，而不是等用户点了授权才报错。
     * 发布能力不受影响：已绑定的账号在密钥恢复后仍可用。
     */
    private PlatformCapability withCipherState(PlatformCapability capability) {
        if (cipher.ready() || !capability.oauthSupported()) return capability;
        return new PlatformCapability(false, capability.publishSupported(), capability.mediaKinds(),
                capability.note() + " " + CredentialCipher.missingKeyNote());
    }

    private void markExpired(PlatformAccountEntity entity, String reason) {
        entity.setStatus(STATUS_EXPIRED);
        entity.setLastError(reason.length() > 900 ? reason.substring(0, 900) : reason);
        entity.setUpdatedAt(LocalDateTime.now());
        accountMapper.updateById(entity);
    }

    private PlatformAccountEntity upsert(String workspaceId, PlatformType type, PlatformIdentity identity) {
        PlatformCredential credential = identity.credential();
        LocalDateTime now = LocalDateTime.now();
        PlatformAccountEntity existing = accountMapper.selectOne(Wrappers.<PlatformAccountEntity>lambdaQuery()
                .eq(PlatformAccountEntity::getWorkspaceId, workspaceId)
                .eq(PlatformAccountEntity::getPlatform, type.name())
                .eq(PlatformAccountEntity::getPlatformUserId, credential.platformUserId())
                .last("LIMIT 1"));
        PlatformAccountEntity entity = existing == null ? new PlatformAccountEntity() : existing;
        if (existing == null) {
            entity.setId(UUID.randomUUID().toString());
            entity.setWorkspaceId(workspaceId);
            entity.setPlatform(type.name());
            entity.setPlatformUserId(credential.platformUserId());
            entity.setCreatedAt(now);
        }
        entity.setDisplayName(identity.displayName());
        entity.setAvatarUrl(identity.avatarUrl());
        entity.setStatus(STATUS_ACTIVE);
        entity.setScope(credential.scope());
        entity.setCredentialCipher(cipher.encrypt(credential));
        entity.setAccessTokenExpiresAt(credential.accessTokenExpiresAt());
        entity.setRefreshTokenExpiresAt(credential.refreshTokenExpiresAt());
        entity.setLastError(null);
        entity.setUpdatedAt(now);
        if (existing == null) accountMapper.insert(entity);
        else accountMapper.updateById(entity);
        return entity;
    }

    private PlatformAccountDto toDto(PlatformAccountEntity entity) {
        return new PlatformAccountDto(entity.getId(), entity.getPlatform(),
                PlatformType.valueOf(entity.getPlatform()).label(), entity.getDisplayName(),
                entity.getAvatarUrl(), entity.getStatus(), entity.getScope(), entity.getLastError(),
                entity.getAccessTokenExpiresAt(), entity.getCreatedAt());
    }
}
