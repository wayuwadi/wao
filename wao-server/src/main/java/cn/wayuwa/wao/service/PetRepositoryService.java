package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.domain.SkillPublisherEntity;
import cn.wayuwa.wao.mapper.SkillPublisherMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/** WAO-HUB 宠物仓库，内容不可变，公开状态由作者显式控制。 */
@Service
public class PetRepositoryService {
    private final JdbcTemplate jdbc;
    private final SkillPublisherMapper publishers;
    private final PetPackageInspector inspector;
    private final SkillRateLimiter limits;
    public PetRepositoryService(JdbcTemplate jdbc, SkillPublisherMapper publishers, PetPackageInspector inspector, SkillRateLimiter limits) {
        this.jdbc = jdbc; this.publishers = publishers; this.inspector = inspector; this.limits = limits;
    }
    public record Card(String id, String slug, String displayName, String description, int spriteVersion,
                       String source, String license, String sha256, int packageSize, boolean listed,
                       String handle, String author, String createdAt) {}
    public record Page(List<Card> items, int page, boolean hasMore) {}
    public record Binary(byte[] content, String type, String fileName, String sha256) {}
    private static final String SELECT = "SELECT p.id,p.pet_slug,p.display_name,p.description,p.sprite_version,p.source,p.license,p.sha256,p.package_size,p.listed,p.created_at,s.handle,s.display_name AS author FROM pet_package p JOIN skill_publisher s ON s.id=p.publisher_id ";

    /** 游客仅能搜索当前公开包；列表不读取二进制和内部成员身份。 */
    public Page browse(String keyword, int page) {
        int current = Math.max(1, Math.min(page, 10000));
        String query = keyword == null ? "" : keyword.trim();
        if (query.length() > 100) throw new BadRequestException("搜索词超过 100 字");
        String like = "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        List<Card> rows = jdbc.query(SELECT + "WHERE p.listed=TRUE AND (p.display_name LIKE ? ESCAPE '!' OR p.description LIKE ? ESCAPE '!' OR p.pet_slug LIKE ? ESCAPE '!') ORDER BY p.created_at DESC,p.id LIMIT 25 OFFSET ?", this::card, like, like, like, (current - 1) * 24);
        return new Page(rows.stream().limit(24).toList(), current, rows.size() > 24);
    }
    /** 列出当前作者自己的包，含私人和下架条目。 */
    public List<Card> mine(String member) {
        requireMember(member);
        var publisher = publishers.findByMember(member);
        return publisher == null ? List.of() : jdbc.query(SELECT + "WHERE p.publisher_id=? ORDER BY p.created_at DESC,p.id", this::card, publisher.getId());
    }
    /** 读取一个公开包详情，不允许通过猜测标识读取私人包。 */
    public Card detail(String id) { return find(id, null); }

    /** 校验上传内容并保存私人不可变包；同作者同内容重试返回原条目。 */
    @Transactional
    public Card create(String member, byte[] archive, String manifest, byte[] sprite, String source, String license) {
        SkillPublisherEntity publisher = requirePublisher(member);
        if (source == null || source.isBlank() || source.length() > 500) throw new BadRequestException("请填写 1–500 字素材来源");
        if (!Set.of("MIT", "Apache-2.0", "CC-BY-4.0", "CC0-1.0", "ALL-RIGHTS-RESERVED").contains(license == null ? "" : license)) throw new BadRequestException("请选择授权方式");
        var pack = archive != null ? inspector.inspect(archive) : inspector.create(manifest, sprite);
        jdbc.queryForObject("SELECT id FROM skill_publisher WHERE id=? FOR UPDATE", String.class, publisher.getId());
        var existing = jdbc.query(SELECT + "WHERE p.publisher_id=? AND p.sha256=? AND p.source=? AND p.license=?", this::card, publisher.getId(), pack.sha256(), source.trim(), license);
        if (!existing.isEmpty()) return existing.get(0);
        limits.checkUpload("pet:" + member);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO pet_package(id,publisher_id,pet_slug,display_name,description,sprite_version,sprite_type,source,license,sha256,package_size,package_content,sprite_content,listed,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,FALSE,CURRENT_TIMESTAMP)",
                id,publisher.getId(),pack.slug(),pack.name(),pack.description(),pack.version(),pack.type(),source.trim(),license,pack.sha256(),pack.archive().length,pack.archive(),pack.sprite());
        return find(id, publisher.getId());
    }
    /** 作者明确确认分享权后公开；下架会同时关闭公开图片和下载。 */
    @Transactional
    public Card visibility(String member, String id, boolean listed, boolean confirmed) {
        var publisher = requirePublisher(member);
        find(id, publisher.getId());
        if (listed && !confirmed) throw new BadRequestException("公开前请确认拥有素材分享权限");
        jdbc.update("UPDATE pet_package SET listed=? WHERE id=? AND publisher_id=?", listed, id, publisher.getId());
        return find(id, publisher.getId());
    }
    /** 读取固定内容，公开请求必须仍在上架，私人请求必须是作者。 */
    public Binary binary(String id, String member, boolean sprite, String client) {
        String publisher = member == null ? null : requirePublisher(member).getId();
        Card card = find(id, publisher);
        if (!sprite) limits.checkDownload(client, "pet:" + id);
        String where = publisher == null ? "listed=TRUE" : "publisher_id=?";
        Object[] args = publisher == null ? new Object[]{id} : new Object[]{id, publisher};
        var result = jdbc.query("SELECT " + (sprite ? "sprite_content,sprite_type" : "package_content") + " FROM pet_package WHERE id=? AND " + where,
                (rs, row) -> new Binary(rs.getBytes(1), sprite ? rs.getString(2) : "application/zip", card.slug() + ".zip", card.sha256()), args);
        if (result.isEmpty()) throw new NotFoundException("宠物不存在或未公开");
        return result.get(0);
    }
    private Card find(String id, String publisher) {
        var rows = publisher == null ? jdbc.query(SELECT + "WHERE p.id=? AND p.listed=TRUE", this::card, id)
                : jdbc.query(SELECT + "WHERE p.id=? AND p.publisher_id=?", this::card, id, publisher);
        if (rows.isEmpty()) throw new NotFoundException("宠物不存在或未公开");
        return rows.get(0);
    }
    private SkillPublisherEntity requirePublisher(String member) {
        requireMember(member);
        var result = publishers.findByMember(member);
        if (result == null) throw new BadRequestException("请先建立 WAO-HUB 公开作者身份");
        return result;
    }
    private static void requireMember(String member) { if (member == null || member.isBlank()) throw new UnauthorizedException("请先登录"); }
    private Card card(ResultSet rs, int row) throws SQLException {
        return new Card(rs.getString("id"),rs.getString("pet_slug"),rs.getString("display_name"),rs.getString("description"),rs.getInt("sprite_version"),rs.getString("source"),rs.getString("license"),rs.getString("sha256"),rs.getInt("package_size"),rs.getBoolean("listed"),rs.getString("handle"),rs.getString("author"),rs.getTimestamp("created_at").toLocalDateTime().toString());
    }
}
