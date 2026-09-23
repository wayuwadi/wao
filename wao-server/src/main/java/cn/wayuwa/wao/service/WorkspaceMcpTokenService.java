package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.WorkspaceMcpTokenEntity;
import cn.wayuwa.wao.dto.CreatedWorkspaceMcpTokenDto;
import cn.wayuwa.wao.dto.WorkspaceMcpTokenDto;
import cn.wayuwa.wao.mapper.WorkspaceMcpTokenMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 签发、校验和吊销独立于个人记忆的工作区 MCP Bearer 令牌。 */
@Service
public class WorkspaceMcpTokenService {
    private static final String PREFIX = "wao_workspace_";
    private static final int MAX_ACTIVE = 10;
    private final WorkspaceMcpTokenMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public WorkspaceMcpTokenService(WorkspaceMcpTokenMapper mapper) { this.mapper = mapper; }

    /** 列出当前成员的有效令牌，永不返回摘要或明文。 */
    public List<WorkspaceMcpTokenDto> list(String memberId) {
        return mapper.selectList(new LambdaQueryWrapper<WorkspaceMcpTokenEntity>()
                .eq(WorkspaceMcpTokenEntity::getMemberId, memberId)
                .isNull(WorkspaceMcpTokenEntity::getRevokedAt)
                .orderByDesc(WorkspaceMcpTokenEntity::getCreatedAt)).stream().map(this::toDto).toList();
    }

    /** 为已登录成员签发一枚仅显示一次明文的设备令牌。 */
    @Transactional
    public CreatedWorkspaceMcpTokenDto create(String memberId, String requestedName, boolean canWrite) {
        String name = requestedName == null ? "" : requestedName.trim();
        if (name.isEmpty() || name.length() > 80) throw new BadRequestException("设备名称长度必须在 1 到 80 字符之间");
        long active = mapper.selectCount(new LambdaQueryWrapper<WorkspaceMcpTokenEntity>()
                .eq(WorkspaceMcpTokenEntity::getMemberId, memberId)
                .isNull(WorkspaceMcpTokenEntity::getRevokedAt));
        if (active >= MAX_ACTIVE) throw new BadRequestException("最多保留 10 枚工作区令牌，请先吊销不用的设备");
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        WorkspaceMcpTokenEntity row = new WorkspaceMcpTokenEntity();
        row.setId(UUID.randomUUID().toString().replace("-", ""));
        row.setMemberId(memberId); row.setName(name);
        row.setTokenPrefix(secret.substring(0, 18)); row.setTokenHash(hash(secret));
        row.setCanWrite(canWrite); row.setCreatedAt(LocalDateTime.now());
        mapper.insert(row);
        return new CreatedWorkspaceMcpTokenDto(toDto(row), secret);
    }

    /** 吊销当前成员名下的令牌；后续 MCP 请求立即失效。 */
    public void revoke(String memberId, String tokenId) {
        WorkspaceMcpTokenEntity row = mapper.selectById(tokenId);
        if (row == null || row.getRevokedAt() != null || !memberId.equals(row.getMemberId())) {
            throw new NotFoundException("工作区令牌不存在");
        }
        row.setRevokedAt(LocalDateTime.now()); mapper.updateById(row);
    }

    /** 每次 MCP 请求重新查询摘要与吊销状态，返回令牌的成员和写权限。 */
    public AuthenticatedToken authenticate(String secret) {
        if (secret == null || !secret.startsWith(PREFIX) || secret.length() > 128) return null;
        WorkspaceMcpTokenEntity row = mapper.selectOne(new LambdaQueryWrapper<WorkspaceMcpTokenEntity>()
                .eq(WorkspaceMcpTokenEntity::getTokenHash, hash(secret))
                .isNull(WorkspaceMcpTokenEntity::getRevokedAt).last("LIMIT 1"));
        if (row == null) return null;
        LocalDateTime now = LocalDateTime.now();
        if (row.getLastUsedAt() == null || row.getLastUsedAt().isBefore(now.minusMinutes(1))) {
            row.setLastUsedAt(now); mapper.updateById(row);
        }
        return new AuthenticatedToken(row.getId(), row.getMemberId(), Boolean.TRUE.equals(row.getCanWrite()));
    }

    public record AuthenticatedToken(String tokenId, String memberId, boolean canWrite) { }

    private WorkspaceMcpTokenDto toDto(WorkspaceMcpTokenEntity row) {
        return new WorkspaceMcpTokenDto(row.getId(), row.getName(), row.getTokenPrefix(),
                Boolean.TRUE.equals(row.getCanWrite()), row.getCreatedAt(), row.getLastUsedAt());
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
