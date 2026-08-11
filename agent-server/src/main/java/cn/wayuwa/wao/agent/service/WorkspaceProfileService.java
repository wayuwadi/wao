package cn.wayuwa.wao.agent.service;

import cn.wayuwa.wao.agent.domain.WorkspaceProfileEntity;
import cn.wayuwa.wao.agent.dto.WorkspaceProfileDto;
import cn.wayuwa.wao.agent.mapper.WorkspaceProfileMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 账号定位。热点推荐的质量几乎完全取决于它 —— 没有定位，模型只能给出泛泛的大众选题。
 */
@Service
public class WorkspaceProfileService {
    private final SharedAccountService accounts;
    private final WorkspaceProfileMapper profileMapper;

    public WorkspaceProfileService(SharedAccountService accounts, WorkspaceProfileMapper profileMapper) {
        this.accounts = accounts;
        this.profileMapper = profileMapper;
    }

    /** 按工作区读取画像，未设置时返回 null。 */
    public WorkspaceProfileEntity find(String workspaceId) {
        return profileMapper.selectOne(Wrappers.<WorkspaceProfileEntity>lambdaQuery()
                .eq(WorkspaceProfileEntity::getWorkspaceId, workspaceId).last("LIMIT 1"));
    }

    public WorkspaceProfileDto get(String accountId) {
        return toDto(find(accounts.workspaceId(accountId)));
    }

    /** 覆盖式保存画像，字段少且都是自由文本，不做乐观锁。 */
    public WorkspaceProfileDto save(String accountId, WorkspaceProfileDto request) {
        String workspaceId = accounts.workspaceId(accountId);
        WorkspaceProfileEntity existing = find(workspaceId);
        WorkspaceProfileEntity entity = existing == null ? new WorkspaceProfileEntity() : existing;
        if (existing == null) {
            entity.setId(UUID.randomUUID().toString());
            entity.setWorkspaceId(workspaceId);
        }
        entity.setNiche(trim(request.niche(), 200));
        entity.setAudience(trim(request.audience(), 200));
        entity.setTone(trim(request.tone(), 200));
        entity.setAvoid(trim(request.avoid(), 500));
        entity.setUpdatedAt(LocalDateTime.now());
        if (existing == null) {
            try {
                profileMapper.insert(entity);
            } catch (DuplicateKeyException ignored) {
                profileMapper.update(entity, Wrappers.<WorkspaceProfileEntity>lambdaUpdate()
                        .eq(WorkspaceProfileEntity::getWorkspaceId, workspaceId));
            }
        } else {
            profileMapper.updateById(entity);
        }
        return toDto(find(workspaceId));
    }

    private WorkspaceProfileDto toDto(WorkspaceProfileEntity entity) {
        if (entity == null) return new WorkspaceProfileDto(null, null, null, null, false);
        boolean configured = entity.getNiche() != null && !entity.getNiche().isBlank();
        return new WorkspaceProfileDto(entity.getNiche(), entity.getAudience(), entity.getTone(),
                entity.getAvoid(), configured);
    }

    private String trim(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
