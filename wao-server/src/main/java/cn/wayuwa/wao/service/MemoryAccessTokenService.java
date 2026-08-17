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
import cn.wayuwa.wao.dto.CreatedMemoryAccessTokenDto;
import cn.wayuwa.wao.dto.MemoryAccessTokenDto;
import cn.wayuwa.wao.mapper.MemoryAccessTokenMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

/** 签发、校验和吊销共享记忆 MCP 的高熵 Bearer 令牌。 */
@Service
public class MemoryAccessTokenService {

    private static final String TOKEN_PREFIX = "wao_mcp_";
    private static final int MAX_ACTIVE_TOKENS = 10;

    private final MemoryAccessTokenMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public MemoryAccessTokenService(MemoryAccessTokenMapper mapper) {
        this.mapper = mapper;
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
        return row.getMemberId();
    }

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
