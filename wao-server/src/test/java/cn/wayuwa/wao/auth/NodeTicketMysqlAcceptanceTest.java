package cn.wayuwa.wao.auth;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 显式开启才访问既有MySQL；只写入及删除本次随机票据，不建立或清空数据库。 */
@EnabledIfEnvironmentVariable(named = "WAO_TEST_NODE_MYSQL", matches = "true")
class NodeTicketMysqlAcceptanceTest {
    @Test void actualMysqlHasOneWinnerAcrossIndependentConnections() throws Exception {
        var ds = new DriverManagerDataSource(System.getenv("WAO_TEST_NODE_JDBC"), System.getenv("DB_USER"), System.getenv("DB_PASS"));
        var jdbc = new JdbcTemplate(ds);
        var store = new JdbcNodeTicketStore(jdbc);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        String hash = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        String root = UUID.randomUUID().toString();
        var ticket = new NodeTicketStore.Ticket(root, "node-ticket-acceptance", "https://linux1.wayuwa.cn:9443");
        long now = System.currentTimeMillis();
        var pool = Executors.newFixedThreadPool(8);
        try {
            store.insert(hash, ticket, now, now + 60_000);
            assertNull(store.consume(hash, "https://linux2.wayuwa.cn:9443", now));
            assertNull(store.consume(hash, ticket.targetOrigin(), now + 60_000));
            var jobs = new ArrayList<Callable<NodeTicketStore.Ticket>>();
            for (int i = 0; i < 8; i++) jobs.add(() -> transaction.execute(status -> store.consume(hash, ticket.targetOrigin(), now)));
            int winners = 0;
            for (var result : pool.invokeAll(jobs)) if (result.get() != null) winners++;
            assertEquals(1, winners);
            assertNull(store.consume(hash, ticket.targetOrigin(), now));
        } finally {
            pool.shutdownNow();
            jdbc.update("DELETE FROM wao_node_session_ticket WHERE ticket_hash=? AND root_session_id=?", hash, root);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM wao_node_session_ticket WHERE ticket_hash=?", Integer.class, hash));
        }
    }
}
