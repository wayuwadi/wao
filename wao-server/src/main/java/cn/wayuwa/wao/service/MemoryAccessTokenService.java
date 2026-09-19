package cn.wayuwa.wao.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.MemoryAccessTokenEntity;
import cn.wayuwa.wao.domain.MemoryAccessGrantEntity;
import cn.wayuwa.wao.dto.CreatedMemoryAccessTokenDto;
import cn.wayuwa.wao.dto.MemoryGrantDto;
import cn.wayuwa.wao.dto.MemoryAccessTokenDto;
import cn.wayuwa.wao.mapper.MemoryAccessGrantMapper;
import cn.wayuwa.wao.mapper.MemoryAccessTokenMapper;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.mapper.OrgMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 签发、校验和吊销共享记忆 MCP 的高熵 Bearer 令牌。 */
@Service
public class MemoryAccessTokenService {

    private static final String TOKEN_PREFIX = "wao_mcp_";
    private static final int MAX_ACTIVE_TOKENS = 10;

    private final MemoryAccessTokenMapper mapper;
    private final MemoryAccessGrantMapper grants;
    private final ProjectService projects;
    private final OrgMapper orgs;
    private final MemberMapper members;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public MemoryAccessTokenService(MemoryAccessTokenMapper mapper, MemoryAccessGrantMapper grants,
            ProjectService projects, OrgMapper orgs, MemberMapper members) {
        this.mapper = mapper;
        this.grants = grants;
        this.projects = projects;
        this.orgs = orgs;
        this.members = members;
    }

    MemoryAccessTokenService(MemoryAccessTokenMapper mapper) {
        this.mapper = mapper;
        this.grants = null;
        this.projects = null;
        this.orgs = null;
        this.members = null;
    }

    /** 列出当前成员仍有效的设备令牌，只返回可公开元数据。 */
    public List<MemoryAccessTokenDto> list(String memberId) {
        return mapper.selectList(new LambdaQueryWrapper<MemoryAccessTokenEntity>()
                        .eq(MemoryAccessTokenEntity::getMemberId, memberId)
                        .isNull(MemoryAccessTokenEntity::getRevokedAt)
                        .orderByDesc(MemoryAccessTokenEntity::getCreatedAt))
                .stream().map(MemoryAccessTokenService::toDto).toList();
    }

    /** 为当前成员新建一枚令牌；明文只在本次调用中返回。 */
    @Transactional
    public CreatedMemoryAccessTokenDto create(String memberId, String requestedName) {
        long active = mapper.selectCount(new LambdaQueryWrapper<MemoryAccessTokenEntity>()
                .eq(MemoryAccessTokenEntity::getMemberId, memberId)
                .isNull(MemoryAccessTokenEntity::getRevokedAt));
        if (active >= MAX_ACTIVE_TOKENS) {
            throw new BadRequestException("每位成员最多保留 10 枚记忆令牌，请先吊销不再使用的设备");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String secret = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime now = LocalDateTime.now();
        MemoryAccessTokenEntity row = new MemoryAccessTokenEntity();
        row.setId(compactId());
        row.setMemberId(memberId);
        row.setName(requestedName.trim());
        row.setTokenPrefix(secret.substring(0, Math.min(16, secret.length())));
        row.setTokenHash(hash(secret));
        row.setCreatedAt(now);
        mapper.insert(row);
        if (grants != null) {
            MemoryAccessGrantEntity grant = new MemoryAccessGrantEntity();
            grant.setId(compactId());
            grant.setTokenId(row.getId());
            grant.setScopeType("personal");
            grant.setScopeId(memberId);
            grant.setCanRead(true);
            grant.setCanPropose(true);
            grant.setCanDirectWrite(true);
            grant.setCreatedAt(now);
            grants.insert(grant);
        }
        return new CreatedMemoryAccessTokenDto(toDto(row), secret);
    }

    /** 吊销当前成员的一枚令牌，已在途和后续 MCP 请求都会失效。 */
    public void revoke(String memberId, String tokenId) {
        MemoryAccessTokenEntity row = mapper.selectById(tokenId);
        if (row == null || !memberId.equals(row.getMemberId()) || row.getRevokedAt() != null) {
            throw new NotFoundException("记忆令牌不存在");
        }
        row.setRevokedAt(LocalDateTime.now());
        mapper.updateById(row);
    }

    /** 校验 MCP Bearer 令牌并返回签发成员；无效或已吊销时返回 {@code null}。 */
    public String authenticate(String secret) {
        AuthenticatedToken token = authenticateToken(secret);
        return token == null ? null : token.memberId();
    }

    /** 校验设备令牌并同时返回令牌 id，供上下文授权和审计使用。 */
    public AuthenticatedToken authenticateToken(String secret) {
        if (secret == null || !secret.startsWith(TOKEN_PREFIX) || secret.length() > 128) return null;
        MemoryAccessTokenEntity row = mapper.selectOne(new LambdaQueryWrapper<MemoryAccessTokenEntity>()
                .eq(MemoryAccessTokenEntity::getTokenHash, hash(secret))
                .isNull(MemoryAccessTokenEntity::getRevokedAt)
                .last("LIMIT 1"));
        if (row == null) return null;
        LocalDateTime now = LocalDateTime.now();
        if (row.getLastUsedAt() == null || row.getLastUsedAt().isBefore(now.minusMinutes(1))) {
            row.setLastUsedAt(now);
            mapper.updateById(row);
        }
        return new AuthenticatedToken(row.getId(), row.getMemberId());
    }

    /** 列出当前成员某枚设备令牌的全部上下文授权。 */
    public List<MemoryGrantDto> listGrants(String memberId, String tokenId) {
        requireOwned(memberId, tokenId);
        if (grants == null) return List.of(new MemoryGrantDto("personal", memberId, true, true, true));
        return grants.selectList(new LambdaQueryWrapper<MemoryAccessGrantEntity>()
                        .eq(MemoryAccessGrantEntity::getTokenId, tokenId)
                        .orderByAsc(MemoryAccessGrantEntity::getScopeType, MemoryAccessGrantEntity::getScopeId))
                .stream().map(row -> new MemoryGrantDto(row.getScopeType(), row.getScopeId(),
                        Boolean.TRUE.equals(row.getCanRead()), Boolean.TRUE.equals(row.getCanPropose()),
                        Boolean.TRUE.equals(row.getCanDirectWrite()))).toList();
    }

    /** 原子替换组织/项目授权，个人域授权固定保留且不能降权。 */
    @Transactional
    public List<MemoryGrantDto> saveGrants(String memberId, String tokenId, List<MemoryGrantDto> requested) {
        requireOwned(memberId, tokenId);
        if (grants == null) return listGrants(memberId, tokenId);
        grants.delete(new LambdaQueryWrapper<MemoryAccessGrantEntity>()
                .eq(MemoryAccessGrantEntity::getTokenId, tokenId));
        List<MemoryGrantDto> normalized = new java.util.ArrayList<>();
        normalized.add(new MemoryGrantDto("personal", memberId, true, true, true));
        if (requested != null) {
            java.util.LinkedHashMap<String, MemoryGrantDto> unique = new java.util.LinkedHashMap<>();
            requested.stream().filter(item -> item != null && !"personal".equals(item.scopeType()))
                    .filter(item -> "organization".equals(item.scopeType()) || "project".equals(item.scopeType())
                            || "codex_archive".equals(item.scopeType()))
                    .filter(item -> item.scopeId() != null && !item.scopeId().isBlank())
                    .forEach(item -> unique.put(item.scopeType() + ":" + ("codex_archive".equals(item.scopeType()) ? memberId : item.scopeId()), item));
            unique.values().forEach(item -> {
                        if ("codex_archive".equals(item.scopeType())) {
                            if (item.canDirectWrite()) normalized.add(new MemoryGrantDto(
                                    "codex_archive", memberId, false, false, true));
                            return;
                        }
                        requireGrantableScope(memberId, item.scopeType(), item.scopeId());
                        normalized.add(new MemoryGrantDto(item.scopeType(), item.scopeId(),
                                item.canRead(), item.canPropose(), false));
            });
        }
        LocalDateTime now = LocalDateTime.now();
        for (MemoryGrantDto item : normalized) {
            MemoryAccessGrantEntity row = new MemoryAccessGrantEntity();
            row.setId(compactId()); row.setTokenId(tokenId); row.setScopeType(item.scopeType());
            row.setScopeId(item.scopeId()); row.setCanRead(item.canRead()); row.setCanPropose(item.canPropose());
            row.setCanDirectWrite(item.canDirectWrite()); row.setCreatedAt(now); grants.insert(row);
        }
        return normalized;
    }

    /** 判断经过认证的令牌能否在指定上下文执行某类动作。 */
    public boolean permits(AuthenticatedToken token, String scopeType, String scopeId, String action) {
        if (token == null || grants == null) return token != null && "personal".equals(scopeType)
                && token.memberId().equals(scopeId);
        MemoryAccessGrantEntity grant = grants.selectOne(new LambdaQueryWrapper<MemoryAccessGrantEntity>()
                .eq(MemoryAccessGrantEntity::getTokenId, token.tokenId())
                .eq(MemoryAccessGrantEntity::getScopeType, scopeType)
                .eq(MemoryAccessGrantEntity::getScopeId, scopeId).last("LIMIT 1"));
        if (grant == null) return false;
        return switch (action) {
            case "read" -> Boolean.TRUE.equals(grant.getCanRead());
            case "propose" -> Boolean.TRUE.equals(grant.getCanPropose());
            case "write" -> Boolean.TRUE.equals(grant.getCanDirectWrite());
            default -> false;
        };
    }

    private MemoryAccessTokenEntity requireOwned(String memberId, String tokenId) {
        MemoryAccessTokenEntity row = mapper.selectById(tokenId);
        if (row == null || !memberId.equals(row.getMemberId()) || row.getRevokedAt() != null) {
            throw new NotFoundException("记忆令牌不存在");
        }
        return row;
    }

    private void requireGrantableScope(String memberId, String scopeType, String scopeId) {
        if (projects == null) return;
        if ("project".equals(scopeType)) {
            projects.requireAccess(scopeId, memberId);
            return;
        }
        boolean memberExists = members.selectById(memberId) != null;
        boolean orgExists = orgs.selectList(null).stream().anyMatch(org -> scopeId.equals(org.getId()));
        if (!memberExists || !orgExists) throw new NotFoundException("记忆范围不存在");
    }

    public record AuthenticatedToken(String tokenId, String memberId) { }

    private static MemoryAccessTokenDto toDto(MemoryAccessTokenEntity row) {
        return new MemoryAccessTokenDto(row.getId(), row.getName(), row.getTokenPrefix(),
                row.getCreatedAt(), row.getLastUsedAt());
    }

    private static String compactId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
        }
    }
}
