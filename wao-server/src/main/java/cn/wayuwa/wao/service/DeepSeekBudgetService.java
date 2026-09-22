package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 在独立数据库事务内预占额度，跨节点、账号共享，网络调用期间不持有锁。 */
@Service
public class DeepSeekBudgetService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public DeepSeekBudgetService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Policy(boolean enabled, long dailyTokenLimit, boolean polishEnabled,
            boolean assistantEnabled, boolean tianrenEnabled, boolean backgroundEnabled) {
        public boolean allows(DeepSeekPurpose purpose) {
            return enabled && switch (purpose) {
                case POLISH -> polishEnabled;
                case ASSISTANT -> assistantEnabled;
                case TIANREN -> tianrenEnabled;
                case BACKGROUND -> backgroundEnabled;
                case UNKNOWN -> false;
            };
        }
    }
    public record Usage(String purpose, long budgetTokens, long actualTokens, long requestCount) {}
    public record Snapshot(Policy policy, LocalDate day, List<Usage> usage) {}
    public record Reservation(LocalDate day, DeepSeekPurpose purpose, long tokens) {}

    /** 读取当前服务端白名单；查询失败直接失败，不允许绕过。 */
    public boolean allows(DeepSeekPurpose purpose) { return policy(false).allows(purpose); }

    /** 返回北京时间当日的真实预算占用及已确认用量，不回传任何凭据。 */
    public Snapshot snapshot() {
        return transaction.execute(status -> {
            Policy current = policy(true);
            LocalDate day = day();
            return new Snapshot(current, day, jdbc.query(
                    "SELECT * FROM deepseek_daily_usage WHERE usage_day = ? ORDER BY purpose",
                    (rs, row) -> new Usage(rs.getString("purpose"), rs.getLong("budget_tokens"),
                            rs.getLong("actual_tokens"), rs.getLong("request_count")), day));
        });
    }

    /** 保存限制不清零用量；并发中的已发送请求无法撤回，后续请求即时遵守新配置。 */
    public Snapshot save(Policy value) {
        if (value.dailyTokenLimit() < 1 || value.dailyTokenLimit() > 1_000_000_000L)
            throw new BadRequestException("每日 Token 上限须在 1 到 10 亿之间");
        transaction.executeWithoutResult(status -> {
            policy(true);
            jdbc.update("""
                    UPDATE deepseek_policy SET enabled=?, daily_token_limit=?, polish_enabled=?,
                    assistant_enabled=?, tianren_enabled=?, background_enabled=?, updated_at=CURRENT_TIMESTAMP(6)
                    WHERE id=1
                    """, value.enabled(), value.dailyTokenLimit(), value.polishEnabled(),
                    value.assistantEnabled(), value.tianrenEnabled(), value.backgroundEnabled());
        });
        return snapshot();
    }

    /** 每次实际 HTTP 尝试前预占最坏输入输出预算；次数只统计、不限制。 */
    public Reservation reserve(DeepSeekPurpose purpose, long tokens) {
        if (tokens < 1) throw new BadRequestException("付费 AI 预算参数无效");
        return transaction.execute(status -> {
            Policy current = policy(true);
            if (!current.allows(purpose)) throw new BadRequestException("付费 AI 未获准用于此功能，请在 AI 设置中显式开启");
            LocalDate day = day();
            Long used = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(budget_tokens),0) FROM deepseek_daily_usage WHERE usage_day=?", Long.class, day);
            if (tokens > current.dailyTokenLimit() - (used == null ? 0 : used))
                throw new BadRequestException("付费 AI 今日剩余额度不足以安全完成本次请求，请明日重试或调整每日上限");
            jdbc.update("""
                    INSERT INTO deepseek_daily_usage(usage_day,purpose,budget_tokens,request_count) VALUES(?,?,?,1)
                    ON DUPLICATE KEY UPDATE budget_tokens=budget_tokens+VALUES(budget_tokens), request_count=request_count+1
                    """, day, purpose.name(), tokens);
            return new Reservation(day, purpose, tokens);
        });
    }

    /** 仅在收到可信用量后结算一次；超时、无用量、进程终止均保留原预占，不自动返还。 */
    public void settle(Reservation reservation, long actualTokens) {
        if (actualTokens < 0) return;
        transaction.executeWithoutResult(status -> {
            policy(true);
            jdbc.update("""
                    UPDATE deepseek_daily_usage SET budget_tokens=budget_tokens+?, actual_tokens=actual_tokens+?
                    WHERE usage_day=? AND purpose=?
                    """, actualTokens - reservation.tokens(), actualTokens, reservation.day(), reservation.purpose().name());
        });
    }

    private LocalDate day() {
        return jdbc.queryForObject("SELECT DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR)", LocalDate.class);
    }

    private Policy policy(boolean lock) {
        return jdbc.queryForObject("SELECT * FROM deepseek_policy WHERE id=1" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Policy(rs.getBoolean("enabled"), rs.getLong("daily_token_limit"),
                        rs.getBoolean("polish_enabled"), rs.getBoolean("assistant_enabled"),
                        rs.getBoolean("tianren_enabled"), rs.getBoolean("background_enabled")));
    }
}
