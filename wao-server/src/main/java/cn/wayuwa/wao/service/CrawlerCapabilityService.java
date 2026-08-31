package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.CrawlerCapabilityEntity;
import cn.wayuwa.wao.dto.CrawlerCapabilityDto;
import cn.wayuwa.wao.dto.SaveCrawlerCapabilityRequest;
import cn.wayuwa.wao.mapper.CrawlerCapabilityMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 管理爬虫源码资产；本服务只保存与编目，不执行任意代码。 */
@Service
public class CrawlerCapabilityService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final CrawlerCapabilityMapper mapper;

    public CrawlerCapabilityService(CrawlerCapabilityMapper mapper) {
        this.mapper = mapper;
    }

    /** 按最近更新时间列出全部爬虫能力。 */
    public List<CrawlerCapabilityDto> list() {
        return mapper.selectList(new QueryWrapper<CrawlerCapabilityEntity>().orderByDesc("updated_at"))
                .stream().map(this::toDto).toList();
    }

    /** 新建一项爬虫能力；新建人由服务端会话确定。 */
    public CrawlerCapabilityDto create(SaveCrawlerCapabilityRequest request, String memberId) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        CrawlerCapabilityEntity entity = new CrawlerCapabilityEntity();
        entity.setId("cc" + UUID.randomUUID().toString().replace("-", "").substring(0, 30));
        apply(entity, request);
        entity.setRevision(1);
        entity.setCreatedBy(memberId);
        entity.setUpdatedBy(memberId);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        return toDto(entity);
    }

    /** 更新一项爬虫能力；旧 revision 保存会返回冲突，避免覆盖其他成员的更新。 */
    public CrawlerCapabilityDto update(String id, SaveCrawlerCapabilityRequest request, String memberId) {
        CrawlerCapabilityEntity current = require(id);
        if (request.revision() == null || !request.revision().equals(current.getRevision())) {
            throw new ConflictException("该能力已被其他成员更新，请刷新后再编辑");
        }
        CrawlerCapabilityEntity next = new CrawlerCapabilityEntity();
        apply(next, request);
        LocalDateTime now = LocalDateTime.now(ZONE);
        int updated = mapper.update(null, new UpdateWrapper<CrawlerCapabilityEntity>()
                .eq("id", id)
                .eq("revision", request.revision())
                .set("name", next.getName())
                .set("description", next.getDescription())
                .set("language", next.getLanguage())
                .set("source_code", next.getSourceCode())
                .set("enabled", next.getEnabled())
                .set("revision", request.revision() + 1)
                .set("updated_by", memberId)
                .set("updated_at", now));
        if (updated == 0) throw new ConflictException("该能力已被其他成员更新，请刷新后再编辑");
        return toDto(require(id));
    }

    /** 删除一项爬虫能力；只有尚未被其他成员更新的版本才会删除。 */
    public void delete(String id, int revision) {
        require(id);
        int deleted = mapper.delete(new QueryWrapper<CrawlerCapabilityEntity>()
                .eq("id", id)
                .eq("revision", revision));
        if (deleted == 0) throw new ConflictException("该能力已被其他成员更新，请刷新后再删除");
    }

    private CrawlerCapabilityEntity require(String id) {
        CrawlerCapabilityEntity entity = mapper.selectById(id);
        if (entity == null) throw new NotFoundException("爬虫能力不存在：" + id);
        return entity;
    }

    private static void apply(CrawlerCapabilityEntity entity, SaveCrawlerCapabilityRequest request) {
        entity.setName(request.name().trim());
        entity.setDescription(request.description() == null ? "" : request.description().trim());
        entity.setLanguage(request.language().trim().toUpperCase(Locale.ROOT));
        entity.setSourceCode(request.sourceCode());
        entity.setEnabled(request.enabled());
    }

    private CrawlerCapabilityDto toDto(CrawlerCapabilityEntity entity) {
        return new CrawlerCapabilityDto(entity.getId(), entity.getName(), entity.getDescription(),
                entity.getLanguage(), entity.getSourceCode(), Boolean.TRUE.equals(entity.getEnabled()),
                entity.getRevision(), entity.getCreatedBy(), entity.getUpdatedBy(), entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
