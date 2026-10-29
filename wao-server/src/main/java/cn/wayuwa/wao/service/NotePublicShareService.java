package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.dto.SharedDocDto;
import cn.wayuwa.wao.domain.ImageBlobEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** 作者明确授权的限时文档/小记快照；沿用已有存储和链接，令牌只返回一次。 */
@Service
public class NotePublicShareService {
    private final JdbcTemplate db;
    private final ShareService shares;
    private final ImageService images;
    private final ObjectMapper json;
    private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    @org.springframework.beans.factory.annotation.Value("${wao.share.base-url:https://wao.wayuwa.cn}")
    private String baseUrl="https://wao.wayuwa.cn";
    private static final Pattern IMAGE=Pattern.compile("(?<![A-Za-z0-9_.:%/-])/api/images/([A-Za-z0-9_-]{1,64})/(?:raw|preview)");
    public record Entry(String id,Instant createdAt,Instant expiresAt) {}
    public record Created(String id,String token,Instant createdAt,Instant expiresAt) {}
    public record PublicNote(SharedDocDto doc,Instant expiresAt) {}
    private record Snapshot(String json,Instant expiresAt) {}

    @Autowired
    public NotePublicShareService(JdbcTemplate db,ShareService shares,ImageService images,ObjectMapper json) {
        this(db,shares,images,json,Clock.systemUTC());
    }
    NotePublicShareService(JdbcTemplate db,ShareService shares,ImageService images,ObjectMapper json,Clock clock) {
        this.db=db;this.shares=shares;this.images=images;this.json=json;this.clock=clock;
    }

    /** 返回作者的当前有效链接元数据，查询不会创建公开权限或返回令牌。 */
    public List<Entry> list(String member,String doc) {
        requireOwner(member,doc);
        return db.query("SELECT id,created_at,expires_at FROM note_public_share WHERE doc_id=? AND owner_id=? AND revoked_at IS NULL AND expires_at>? ORDER BY created_at DESC",
                (r,n)->new Entry(r.getString(1),instant(r.getObject(2,LocalDateTime.class)),instant(r.getObject(3,LocalDateTime.class))),doc,member,timestamp(clock.instant()));
    }

    /** 创建已保存阅读版本的限时分享；本地草稿和未来修改不进入快照。 */
    @Transactional
    public Created create(String member,String doc,int hours) {
        if(!Set.of(1,24,168).contains(hours)) throw new BadRequestException("有效期请选择 1 小时、1 天或 7 天");
        requireOwner(member,doc);
        // 与正文更新互斥，避免生成混合版本，并串行检查活动链接数量。
        db.queryForObject("SELECT id FROM doc WHERE id=? FOR UPDATE",String.class,doc);
        if(list(member,doc).size()>=20) throw new BadRequestException("请先撤销不再使用的分享链接");
        var snapshot=shares.documentSnapshot(doc,member);
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        String token="ns_"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String id=UUID.randomUUID().toString().replace("-","");
        Instant now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),expires=now.plus(Duration.ofHours(hours));
        db.update("INSERT INTO note_public_share(id,doc_id,owner_id,token_hash,snapshot_json,created_at,expires_at) VALUES (?,?,?,?,?,?,?)",
                id,doc,member,hash(token),encode(snapshot),timestamp(now),timestamp(expires));
        return new Created(id,token,now,expires);
    }

    /** 撤销指定链接；只允许源小记作者操作，重复撤销安全。 */
    public void revoke(String member,String doc,String id) {
        requireOwner(member,doc);
        db.update("UPDATE note_public_share SET revoked_at=COALESCE(revoked_at,?) WHERE id=? AND doc_id=? AND owner_id=?",timestamp(clock.instant()),id,doc,member);
    }

    /** 每次公开读取验证权限，再将快照图片改写为同一限时权限的地址。 */
    public PublicNote read(String token) {
        Snapshot snapshot=resolve(token);
        String mapped=IMAGE.matcher(localImages(snapshot.json())).replaceAll(m->java.util.regex.Matcher.quoteReplacement("/api/share/notes/"+token+"/images/"+m.group(1)+"/raw"));
        try { return new PublicNote(json.readValue(mapped,SharedDocDto.class),snapshot.expiresAt()); }
        catch(Exception e) { throw new IllegalStateException("分享快照无法读取",e); }
    }

    /** 只允许读取同一有效分享快照内出现的配图，不接受任意图床 ID。 */
    public ImageBlobEntity image(String token,String imageId) {
        var matcher=IMAGE.matcher(localImages(resolve(token).json()));
        while(matcher.find()) if(matcher.group(1).equals(imageId)) return images.raw(imageId);
        throw missing();
    }

    private Snapshot resolve(String token) {
        if(token==null || !token.matches("ns_[A-Za-z0-9_-]{43}")) throw missing();
        var rows=db.query("SELECT s.snapshot_json,s.expires_at FROM note_public_share s JOIN doc d ON d.id=s.doc_id AND d.author_id=s.owner_id AND d.deleted_at IS NULL WHERE s.token_hash=? AND s.revoked_at IS NULL AND s.expires_at>?",
                (r,n)->new Snapshot(r.getString(1),instant(r.getObject(2,LocalDateTime.class))),hash(token),timestamp(clock.instant()));
        if(rows.isEmpty()) throw missing();
        return rows.get(0);
    }
    private void requireOwner(String member,String doc) {
        if(member==null) throw new UnauthorizedException("请先登录");
        Integer count=db.queryForObject("SELECT COUNT(*) FROM doc WHERE id=? AND author_id=? AND deleted_at IS NULL",Integer.class,doc,member);
        if(count==null || count==0) throw new ForbiddenException("只有文档或小记作者可以管理分享");
    }
    private String encode(Object value) { try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("无法保存分享快照",e);} }
    private String localImages(String value) {
        // 外站 URL 中碰巧出现同一 API 路径不能兑换本站图片访问权限。
        if(baseUrl==null || baseUrl.isBlank())return value;
        String origin=baseUrl.replaceAll("/+$","");
        return value.replace(origin+"/api/images/","/api/images/");
    }
    private static String hash(String token) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);} }
    // DATETIME 以 UTC 字面值存储，不依赖数据库或本机默认时区。
    private static LocalDateTime timestamp(Instant value) { return LocalDateTime.ofInstant(value,ZoneOffset.UTC); }
    private static Instant instant(LocalDateTime value) { return value.toInstant(ZoneOffset.UTC); }
    private static NotFoundException missing() { return new NotFoundException("分享链接无效或已失效"); }
}
