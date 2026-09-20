package cn.wayuwa.wao.auth;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 通过MySQL条件更新保证跨应用节点只能成功兑换一次。 */
@Repository
@ConditionalOnProperty(name = "wao.edge.session-enabled", havingValue = "true")
public class JdbcNodeTicketStore implements NodeTicketStore {
    private final JdbcTemplate jdbc;

    public JdbcNodeTicketStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** {@inheritDoc} */
    @Override
    public void insert(String hash, Ticket ticket, long createdAt, long expiresAt) {
        jdbc.update("""
                INSERT INTO wao_node_session_ticket
                  (ticket_hash, root_session_id, account_id, target_origin, created_at_ms, expires_at_ms)
                VALUES (?, ?, ?, ?, ?, ?)
                """, hash, ticket.rootSessionId(), ticket.accountId(), ticket.targetOrigin(), createdAt, expiresAt);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public Ticket consume(String hash, String targetOrigin, long now) {
        List<Ticket> tickets = jdbc.query("""
                SELECT root_session_id, account_id, target_origin FROM wao_node_session_ticket
                WHERE ticket_hash=? AND target_origin=? AND expires_at_ms>? AND consumed_at_ms IS NULL
                """, (rs, index) -> new Ticket(rs.getString(1), rs.getString(2), rs.getString(3)), hash, targetOrigin, now);
        if (tickets.isEmpty()) return null;
        int changed = jdbc.update("""
                UPDATE wao_node_session_ticket SET consumed_at_ms=?
                WHERE ticket_hash=? AND target_origin=? AND expires_at_ms>? AND consumed_at_ms IS NULL
                """, now, hash, targetOrigin, now);
        return changed == 1 ? tickets.get(0) : null;
    }

    /** {@inheritDoc} */
    @Override
    public int recentCount(String rootSessionId, long since) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM wao_node_session_ticket WHERE root_session_id=? AND created_at_ms>=?
                """, Integer.class, rootSessionId, since);
        return count == null ? 0 : count;
    }

    /** 分批清理已过期一天的票据，避免无界删除影响业务事务。 */
    @Scheduled(fixedDelay = 300_000)
    public void removeExpired() {
        jdbc.update("DELETE FROM wao_node_session_ticket WHERE expires_at_ms<? LIMIT 500",
                System.currentTimeMillis() - 86_400_000);
    }
}
