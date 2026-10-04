package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.WorkspaceMcpTokenEntity;
import cn.wayuwa.wao.dto.CreatedWorkspaceMcpTokenDto;
import cn.wayuwa.wao.dto.CreateWorkspaceMcpTokenRequest;
import cn.wayuwa.wao.dto.WorkspaceMcpTokenDto;
import cn.wayuwa.wao.mapper.WorkspaceMcpTokenMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 签发、校验和吊销独立于个人记忆的工作区 MCP Bearer 令牌。 */
@Service
public class WorkspaceMcpTokenService {
    private static final String PREFIX = "wao_workspace_";
    private static final int MAX_ACTIVE = 10;
    private final WorkspaceMcpTokenMapper mapper;
    private final WorkspaceMcpTools tools;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    public WorkspaceMcpTokenService(WorkspaceMcpTokenMapper mapper) { this(mapper, null); }

    @Autowired
    public WorkspaceMcpTokenService(WorkspaceMcpTokenMapper mapper, WorkspaceMcpTools tools) {
        this.mapper = mapper; this.tools = tools;
    }

    /** 签发逐项授权连接；兼容旧创建请求，资源范围必须属于当前成员可见范围。 */
    @Transactional
    public CreatedWorkspaceMcpTokenDto create(String memberId, CreateWorkspaceMcpTokenRequest request) {
        if (request.permissions() == null) {
            if (request.canWrite() == null || request.projectIds() != null || request.knowledgeBaseIds() != null
                    || request.clientType() != null) throw new BadRequestException("新连接必须提供逐项权限");
            return create(memberId, request.name(), request.canWrite(), Boolean.TRUE.equals(request.canPublishHostedPage()));
        }
        WorkspaceCapabilityPolicy policy = new WorkspaceCapabilityPolicy(request.permissions(), request.projectIds(), request.knowledgeBaseIds());
        if (request.clientType() == null || !List.of("CLI", "MCP").contains(request.clientType())) throw new BadRequestException("连接类型必须为 CLI 或 MCP");
        AuthenticatedToken member = new AuthenticatedToken("scope-validation", memberId, false);
        if (policy.projectIds() != null) for (String id : policy.projectIds()) {
            tools.call("get_project", JSON.createObjectNode().put("project_id", id), member);
        }
        if (policy.knowledgeBaseIds() != null) for (String id : policy.knowledgeBaseIds()) {
            tools.call("get_knowledge_base", JSON.createObjectNode().put("kb_id", id), member);
        }
        return issue(memberId, request.name(), policy.canWrite(), policy.permits("publish_html"), policy, request.clientType());
    }

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
        return create(memberId, requestedName, canWrite, false);
    }

    /** 为当前成员签发一枚仅显示一次、可选允许发布托管 HTML 的工作区令牌。 */
    @Transactional
    public CreatedWorkspaceMcpTokenDto create(String memberId, String requestedName, boolean canWrite,
            boolean canPublishHostedPage) {
        return issue(memberId, requestedName, canWrite, canPublishHostedPage, null, null);
    }

    private CreatedWorkspaceMcpTokenDto issue(String memberId, String requestedName, boolean canWrite,
            boolean canPublishHostedPage, WorkspaceCapabilityPolicy policy, String clientType) {
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
        row.setCanWrite(canWrite); row.setCanPublishHostedPage(canPublishHostedPage);
        row.setPermissionPolicy(writePolicy(policy)); row.setClientType(clientType);
        row.setCreatedAt(LocalDateTime.now());
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
        WorkspaceCapabilityPolicy policy;
        try { policy = readPolicy(row.getPermissionPolicy()); }
        catch (RuntimeException corruptPolicy) { return null; }
        LocalDateTime now = LocalDateTime.now();
        if (row.getLastUsedAt() == null || row.getLastUsedAt().isBefore(now.minusMinutes(1))) {
            row.setLastUsedAt(now); mapper.updateById(row);
        }
        return new AuthenticatedToken(row.getId(), row.getMemberId(), Boolean.TRUE.equals(row.getCanWrite()),
                Boolean.TRUE.equals(row.getCanPublishHostedPage()), policy);
    }

    public record AuthenticatedToken(String tokenId, String memberId, boolean canWrite,
            boolean canPublishHostedPage, WorkspaceCapabilityPolicy policy) {
        public AuthenticatedToken(String tokenId, String memberId, boolean canWrite, boolean canPublishHostedPage) {
            this(tokenId, memberId, canWrite, canPublishHostedPage, null);
        }
        public AuthenticatedToken(String tokenId, String memberId, boolean canWrite) {
            this(tokenId, memberId, canWrite, false);
        }
        /** 操作白名单与旧版兼容标记统一判断，未知能力不自动扩权。 */
        public boolean permits(String operation) {
            if (policy != null) return policy.permits(operation);
            return WorkspaceCapabilityPolicy.LEGACY_READ.contains(operation)
                    || (canWrite && WorkspaceCapabilityPolicy.LEGACY_WRITE.contains(operation))
                    || (canPublishHostedPage && "publish_html".equals(operation));
        }
        /** 检查项目范围；null policy 的旧令牌沿用账号可见性。 */
        public boolean permitsProject(String id) { return policy == null || policy.permitsProject(id); }
        /** 检查知识库范围；null policy 的旧令牌沿用账号可见性。 */
        public boolean permitsKnowledgeBase(String id) { return policy == null || policy.permitsKnowledgeBase(id); }
        /** 每日范围只能来自显式工具授权快照，旧令牌保持关闭。 */
        public boolean canUseDaily() {
            return policy != null && policy.permissions().stream().anyMatch(Set.of("get_todo_day", "list_todo_days",
                    "get_daily_operation", "schedule_task", "record_daily_note")::contains);
        }
    }

    private WorkspaceMcpTokenDto toDto(WorkspaceMcpTokenEntity row) {
        WorkspaceCapabilityPolicy policy = readPolicy(row.getPermissionPolicy());
        return new WorkspaceMcpTokenDto(row.getId(), row.getName(), row.getTokenPrefix(),
                Boolean.TRUE.equals(row.getCanWrite()), Boolean.TRUE.equals(row.getCanPublishHostedPage()),
                row.getCreatedAt(), row.getLastUsedAt(), policy == null ? "legacy" : "granular",
                policy == null ? null : policy.permissions(), policy == null ? null : policy.projectIds(),
                policy == null ? null : policy.knowledgeBaseIds(), row.getClientType());
    }

    private static String writePolicy(WorkspaceCapabilityPolicy policy) {
        if (policy == null) return null;
        try { return JSON.writeValueAsString(policy); }
        catch (JsonProcessingException e) { throw new IllegalStateException("权限快照保存失败", e); }
    }

    private static WorkspaceCapabilityPolicy readPolicy(String raw) {
        if (raw == null) return null;
        try {
            WorkspaceCapabilityPolicy policy = JSON.readValue(raw, WorkspaceCapabilityPolicy.class);
            if (policy == null) throw new IllegalStateException("非空权限快照不能为空");
            return policy;
        }
        catch (JsonProcessingException e) { throw new IllegalStateException("权限快照无效", e); }
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
