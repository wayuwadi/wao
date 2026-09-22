package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DeepSeekBudgetServiceTest {
    private DeepSeekBudgetService budget;
    private JdbcTemplate jdbc;
    private LocalDate day = LocalDate.of(2026, 9, 22);

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source) {
            @Override public <T> T queryForObject(String sql, Class<T> type) {
                if (sql.contains("UTC_TIMESTAMP")) return type.cast(day);
                return super.queryForObject(sql, type);
            }
        };
        jdbc.execute("CREATE TABLE deepseek_policy(id INT PRIMARY KEY,enabled BOOLEAN,daily_token_limit BIGINT,polish_enabled BOOLEAN,assistant_enabled BOOLEAN,tianren_enabled BOOLEAN,background_enabled BOOLEAN,updated_at TIMESTAMP)");
        jdbc.execute("INSERT INTO deepseek_policy VALUES(1,TRUE,10000000,TRUE,FALSE,FALSE,FALSE,CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE deepseek_daily_usage(usage_day DATE,purpose VARCHAR(32),budget_tokens BIGINT DEFAULT 0,actual_tokens BIGINT DEFAULT 0,request_count BIGINT DEFAULT 0,PRIMARY KEY(usage_day,purpose))");
        budget = new DeepSeekBudgetService(jdbc, new DataSourceTransactionManager(source));
    }

    @Test void defaultsOnlyAllowPolishAndNeverUnknown() {
        for (var purpose : DeepSeekPurpose.values()) assertThat(budget.allows(purpose)).isEqualTo(purpose == DeepSeekPurpose.POLISH);
        for (var purpose : new DeepSeekPurpose[] { DeepSeekPurpose.ASSISTANT, DeepSeekPurpose.TIANREN, DeepSeekPurpose.BACKGROUND, DeepSeekPurpose.UNKNOWN })
            assertThatThrownBy(() -> budget.reserve(purpose, 1)).hasMessageContaining("未获准");
        assertThat(budget.snapshot().usage()).isEmpty();
        assertThat(DeepSeekPurpose.parse("future-feature")).isEqualTo(DeepSeekPurpose.UNKNOWN);
        assertThat(DeepSeekPurpose.parse(null)).isEqualTo(DeepSeekPurpose.UNKNOWN);
    }

    @Test void concurrentRequestsShareAtomicGlobalBudget() throws Exception {
        budget.save(new DeepSeekBudgetService.Policy(true, 100, true, false, false, false));
        var executor = Executors.newFixedThreadPool(8);
        try {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 20; i++) futures.add(executor.submit(() -> {
                start.await();
                try { budget.reserve(DeepSeekPurpose.POLISH, 30); return true; }
                catch (cn.wayuwa.wao.common.BadRequestException e) { return false; }
            }));
            start.countDown();
            int accepted = 0;
            for (var result : futures) if (result.get(10, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(3);
            assertThat(budget.snapshot().usage().get(0).budgetTokens()).isEqualTo(90);
        } finally { executor.shutdownNow(); }
    }

    @Test void settlesActualUsageButRetainsUncertainAttemptsAndSettingsNeverResetCounters() {
        var first = budget.reserve(DeepSeekPurpose.POLISH, 1000);
        budget.reserve(DeepSeekPurpose.POLISH, 1000); // 未知失败不退款
        budget.settle(first, 150);
        var snapshot = budget.save(new DeepSeekBudgetService.Policy(false, 100, true, false, false, false));
        assertThat(snapshot.usage().get(0).budgetTokens()).isEqualTo(1150);
        assertThat(snapshot.usage().get(0).actualTokens()).isEqualTo(150);
        assertThat(snapshot.usage().get(0).requestCount()).isEqualTo(2);
        assertThatThrownBy(() -> budget.reserve(DeepSeekPurpose.POLISH, 1)).hasMessageContaining("未获准");
        budget.save(new DeepSeekBudgetService.Policy(true, 100, true, false, false, false));
        assertThatThrownBy(() -> budget.reserve(DeepSeekPurpose.POLISH, 1)).hasMessageContaining("剩余额度不足");
    }

    @Test void newDayHasFreshBudgetAndLateSettlementStaysOnOriginalDay() {
        var old = budget.reserve(DeepSeekPurpose.POLISH, 100);
        day = day.plusDays(1);
        assertThat(budget.snapshot().usage()).isEmpty();
        budget.reserve(DeepSeekPurpose.POLISH, 200);
        budget.settle(old, 50);
        assertThat(budget.snapshot().usage().get(0).budgetTokens()).isEqualTo(200);
    }
}
