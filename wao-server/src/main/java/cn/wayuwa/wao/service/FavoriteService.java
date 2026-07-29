package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.domain.MemberFavoriteEntity;
import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.FavoriteDto;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.mapper.MemberFavoriteMapper;
import cn.wayuwa.wao.mapper.ProjectMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

/**
 * 成员收藏：项目 / 任务 / 文档各存一条 (type, targetId)。
 *
 * <p>列表时实时解析展示名，目标被删（含进回收站）就自动跳过并顺手清掉收藏记录，
 * 避免侧边栏里挂着点不开的死链接。
 */
@Service
public class FavoriteService {

    private static final Set<String> TYPES = Set.of("project", "task", "doc");

    private final MemberFavoriteMapper favoriteMapper;
    private final ProjectMapper projectMapper;
    private final TaskMapper taskMapper;
    private final DocMapper docMapper;

    public FavoriteService(MemberFavoriteMapper favoriteMapper, ProjectMapper projectMapper,
            TaskMapper taskMapper, DocMapper docMapper) {
        this.favoriteMapper = favoriteMapper;
        this.projectMapper = projectMapper;
        this.taskMapper = taskMapper;
        this.docMapper = docMapper;
    }

    /**
     * 列出成员的全部收藏（按收藏时间正序），目标已不存在的自动清理。
     *
     * @param memberId 成员 id
     * @return 收藏列表
     */
    public List<FavoriteDto> list(String memberId) {
        List<MemberFavoriteEntity> rows = favoriteMapper.selectList(new LambdaQueryWrapper<MemberFavoriteEntity>()
                .eq(MemberFavoriteEntity::getMemberId, memberId)
                .orderByAsc(MemberFavoriteEntity::getCreatedAt));
        List<FavoriteDto> out = new ArrayList<>();
        for (MemberFavoriteEntity row : rows) {
            FavoriteDto dto = resolve(row);
            if (dto == null) {
                favoriteMapper.deleteById(row.getId());
            } else {
                out.add(dto);
            }
        }
        return out;
    }

    /**
     * 收藏一个目标；重复收藏静默幂等。
     *
     * @param memberId 成员 id
     * @param type     project / task / doc
     * @param targetId 目标 id
     */
    public void add(String memberId, String type, String targetId) {
        requireType(type);
        if (exists(memberId, type, targetId)) {
            return;
        }
        MemberFavoriteEntity e = new MemberFavoriteEntity();
        e.setId("fav" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        e.setMemberId(memberId);
        e.setTargetType(type);
        e.setTargetId(targetId);
        e.setCreatedAt(LocalDateTime.now());
        favoriteMapper.insert(e);
    }

    /**
     * 取消收藏；不存在时静默幂等。
     *
     * @param memberId 成员 id
     * @param type     project / task / doc
     * @param targetId 目标 id
     */
    public void remove(String memberId, String type, String targetId) {
        requireType(type);
        favoriteMapper.delete(new LambdaQueryWrapper<MemberFavoriteEntity>()
                .eq(MemberFavoriteEntity::getMemberId, memberId)
                .eq(MemberFavoriteEntity::getTargetType, type)
                .eq(MemberFavoriteEntity::getTargetId, targetId));
    }

    private boolean exists(String memberId, String type, String targetId) {
        return favoriteMapper.selectCount(new LambdaQueryWrapper<MemberFavoriteEntity>()
                .eq(MemberFavoriteEntity::getMemberId, memberId)
                .eq(MemberFavoriteEntity::getTargetType, type)
                .eq(MemberFavoriteEntity::getTargetId, targetId)) > 0;
    }

    private FavoriteDto resolve(MemberFavoriteEntity row) {
        switch (row.getTargetType()) {
            case "project" -> {
                ProjectEntity p = projectMapper.selectById(row.getTargetId());
                return p == null ? null : new FavoriteDto("project", p.getId(), p.getName(), null);
            }
            case "task" -> {
                TaskEntity t = taskMapper.selectById(row.getTargetId());
                return t == null ? null : new FavoriteDto("task", t.getId(), t.getTitle(), t.getProjectId());
            }
            case "doc" -> {
                DocEntity d = docMapper.selectById(row.getTargetId());
                if (d == null || d.getDeletedAt() != null) {
                    return null;
                }
                String title = d.getTitle() == null || d.getTitle().isBlank() ? "未命名文档" : d.getTitle();
                return new FavoriteDto("doc", d.getId(), title, d.getKbId());
            }
            default -> {
                return null;
            }
        }
    }

    private static void requireType(String type) {
        if (type == null || !TYPES.contains(type)) {
            throw new BadRequestException("不支持的收藏类型");
        }
    }
}
