package cn.wayuwa.wao.auth;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 用真实SQL和独立连接验证条件消费；生产MySQL另做同样的并发验收。 */
class JdbcNodeTicketStoreTest {
    @Test void concurrentTransactionsConsumeOnlyOnceAndRespectTargetAndExpiry() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE wao_node_session_ticket (
                  ticket_hash CHAR(64) PRIMARY KEY, root_session_id CHAR(36), account_id VARCHAR(64),
                  target_origin VARCHAR(128), created_at_ms BIGINT, expires_at_ms BIGINT, consumed_at_ms BIGINT)
                """);
        var store = new JdbcNodeTicketStore(jdbc);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        String hash = "a".repeat(64);
        var ticket = new NodeTicketStore.Ticket(UUID.randomUUID().toString(), "account-one", "https://linux1.wayuwa.cn:9443");
        store.insert(hash, ticket, 1000, 61000);
        assertNull(store.consume(hash, "https://linux2.wayuwa.cn:9443", 1001));
        assertNull(store.consume(hash, ticket.targetOrigin(), 61000));
        assertEquals(1, store.recentCount(ticket.rootSessionId(), 1000));
        var pool = Executors.newFixedThreadPool(8);
        try {
            var jobs = new ArrayList<Callable<NodeTicketStore.Ticket>>();
            for (int i = 0; i < 8; i++) jobs.add(() -> transaction.execute(status -> store.consume(hash, ticket.targetOrigin(), 1001)));
            int winners = 0;
            for (var result : pool.invokeAll(jobs)) if (result.get() != null) winners++;
            assertEquals(1, winners);
            assertNull(store.consume(hash, ticket.targetOrigin(), 1002));
        } finally { pool.shutdownNow(); }
    }
}
