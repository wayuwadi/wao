package cn.wayuwa.wao.agent.service;

import cn.wayuwa.wao.agent.common.UnauthorizedException;
import cn.wayuwa.wao.agent.domain.WorkspaceEntity;
import cn.wayuwa.wao.agent.domain.WorkspaceMemberEntity;
import cn.wayuwa.wao.agent.dto.AgentSessionDto;
import cn.wayuwa.wao.agent.dto.SharedAccountDto;
import cn.wayuwa.wao.agent.mapper.SharedAccountMapper;
import cn.wayuwa.wao.agent.mapper.WorkspaceMapper;
import cn.wayuwa.wao.agent.mapper.WorkspaceMemberMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class SharedAccountService {
    private final SharedAccountMapper accountMapper;
    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;

    public SharedAccountService(SharedAccountMapper accountMapper, WorkspaceMapper workspaceMapper,
                                WorkspaceMemberMapper memberMapper) {
        this.accountMapper = accountMapper;
        this.workspaceMapper = workspaceMapper;
        this.memberMapper = memberMapper;
    }

    /** 校验 WAO 账号，并在首次进入时创建该账号的 Agent 默认工作区。 */
    public AgentSessionDto session(String accountId) {
        SharedAccountDto account = accountMapper.findById(accountId);
        if (account == null || !"ACTIVE".equals(account.status())) {
            throw new UnauthorizedException("WAO 账号不存在或已停用");
        }
        WorkspaceEntity workspace = workspaceMapper.selectOne(Wrappers.<WorkspaceEntity>lambdaQuery()
                .eq(WorkspaceEntity::getOwnerAccountId, accountId).last("LIMIT 1"));
        if (workspace == null) {
            LocalDateTime now = LocalDateTime.now();
            WorkspaceEntity proposed = new WorkspaceEntity();
            proposed.setId(stableId("workspace", accountId));
            proposed.setName(account.displayName() + "的内容工作室");
            proposed.setOwnerAccountId(accountId);
            proposed.setCreatedAt(now);
            try {
                workspaceMapper.insert(proposed);
                workspace = proposed;
            } catch (DuplicateKeyException ignored) {
                workspace = workspaceMapper.selectOne(Wrappers.<WorkspaceEntity>lambdaQuery()
                        .eq(WorkspaceEntity::getOwnerAccountId, accountId).last("LIMIT 1"));
            }
        }
        if (workspace == null) throw new UnauthorizedException("Agent 工作区初始化失败，请重试");

        WorkspaceMemberEntity membership = memberMapper.selectOne(Wrappers.<WorkspaceMemberEntity>lambdaQuery()
                .eq(WorkspaceMemberEntity::getWorkspaceId, workspace.getId())
                .eq(WorkspaceMemberEntity::getAccountId, accountId).last("LIMIT 1"));
        if (membership == null) {
            WorkspaceMemberEntity proposed = new WorkspaceMemberEntity();
            proposed.setId(stableId("member", accountId));
            proposed.setWorkspaceId(workspace.getId());
            proposed.setAccountId(accountId);
            proposed.setRole("OWNER");
            proposed.setCreatedAt(LocalDateTime.now());
            try {
                memberMapper.insert(proposed);
                membership = proposed;
            } catch (DuplicateKeyException ignored) {
                membership = memberMapper.selectOne(Wrappers.<WorkspaceMemberEntity>lambdaQuery()
                        .eq(WorkspaceMemberEntity::getWorkspaceId, workspace.getId())
                        .eq(WorkspaceMemberEntity::getAccountId, accountId).last("LIMIT 1"));
            }
        }
        if (membership == null) throw new UnauthorizedException("Agent 工作区成员关系初始化失败，请重试");
        return new AgentSessionDto(account.accountId(), account.displayName(), account.avatarText(),
                workspace.getId(), workspace.getName(), membership.getRole(), true);
    }

    /** 返回当前 WAO 账号可访问的默认 Agent 工作区 id。 */
    public String workspaceId(String accountId) {
        return session(accountId).workspaceId();
    }

    private String stableId(String type, String accountId) {
        return UUID.nameUUIDFromBytes(("wao-agent:" + type + ":" + accountId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
}
