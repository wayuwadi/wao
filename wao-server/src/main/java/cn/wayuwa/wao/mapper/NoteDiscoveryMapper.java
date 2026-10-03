package cn.wayuwa.wao.mapper;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 发现状态 SQL 持久化；所有调用均显式带成员或已持有的批次身份。 */
@Repository
public class NoteDiscoveryMapper {
    private final JdbcTemplate jdbc;
    public NoteDiscoveryMapper(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** 执行参数化查询。 */
    public List<Map<String,Object>> rows(String sql,Object... args) { return jdbc.queryForList(sql,args); }
    /** 查询唯一状态或返回空；不以异常隐藏连接失败。 */
    public Map<String,Object> one(String sql,Object... args) { var rows=rows(sql,args); return rows.isEmpty()?null:rows.get(0); }
    /** 执行参数化条件更新，返回实际匹配行数。 */
    public int update(String sql,Object... args) { return jdbc.update(sql,args); }
    /** 为本人建立默认未开启的状态，不覆盖既有偏好。 */
    public void ensure(String member) { update("INSERT IGNORE INTO note_discovery_schedule(member_id) VALUES(?)",member); }
    /** 读取并锁定成员调度状态。 */
    public Map<String,Object> lock(String member) { ensure(member); return one("SELECT * FROM note_discovery_schedule WHERE member_id=? FOR UPDATE",member); }
    /** 非阻塞的跨实例租约条件领取。 */
    public int claim(String member,String token,int seconds) {
        return update("UPDATE note_discovery_schedule SET lease_token=?,lease_epoch=lease_epoch+1,lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)) WHERE member_id=? AND enabled=1 AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP(6))",token,seconds,member);
    }
    /** 仅当前租约持有人能够续期。 */
    public int renew(String member,String token,long epoch,int seconds) {
        return update("UPDATE note_discovery_schedule SET lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)) WHERE member_id=? AND lease_token=? AND lease_epoch=? AND lease_until>=CURRENT_TIMESTAMP(6)",seconds,member,token,epoch);
    }
    /** 最小化实时来源范围，不读取其他成员、自动文档、删除或归档内容。 */
    public boolean eligible(String member,String doc) {
        return one("SELECT d.id FROM doc d JOIN kb k ON k.id=d.kb_id WHERE d.id=? AND d.author_id=? AND k.notes=1 AND k.deleted_at IS NULL AND d.deleted_at IS NULL AND d.archived_at IS NULL AND COALESCE(d.auto,0)=0",doc,member)!=null;
    }
    /** 安全读取数据库数字字段。 */
    public static long number(Map<String,Object> row,String key) { Object v=row==null?null:row.get(key); return v instanceof Number n?n.longValue():0; }
    /** 安全读取数据库布尔字段。 */
    public static boolean flag(Map<String,Object> row,String key) { Object v=row==null?null:row.get(key); return Boolean.TRUE.equals(v)||v instanceof Number n&&n.intValue()!=0; }
    /** 安全读取可空字符串。 */
    public static String string(Map<String,Object> row,String key) { Object v=row==null?null:row.get(key); return v==null?null:v.toString(); }
}
