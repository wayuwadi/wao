package cn.wayuwa.wao.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import cn.wayuwa.wao.domain.AttendanceConnectorEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 使用真实 SQL 验证恢复同步能清除旧错误，而不会清除缓存会话。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:attendanceconnector;DB_CLOSE_DELAY=-1;MODE=MySQL")
class AttendanceConnectorMapperIntegrationTest {
    @Autowired private AttendanceConnectorMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    @Test void successfulSyncClearsPreviousLoginErrorAndPreservesSession() {
        jdbc.execute("""
                CREATE TABLE attendance_connector (
                    member_id VARCHAR(32) PRIMARY KEY, provider VARCHAR(16), username VARCHAR(160),
                    vault_item_id VARCHAR(32), enabled BOOLEAN, remote_employee_id VARCHAR(64),
                    session_ciphertext BLOB, session_nonce VARBINARY(12), status VARCHAR(24),
                    last_synced_at TIMESTAMP, last_error VARCHAR(512), history_synced_from DATE,
                    history_backfill_claim_token VARCHAR(36), history_backfill_claimed_at TIMESTAMP,
                    created_at TIMESTAMP, updated_at TIMESTAMP
                )
                """);
        AttendanceConnectorEntity value = new AttendanceConnectorEntity();
        value.setMemberId("member-1");
        value.setStatus("NEEDS_CONFIRMATION");
        value.setLastError("Moka 检测到其他会话");
        value.setSessionCiphertext(new byte[]{1,2,3});
        value.setSessionNonce(new byte[]{4,5,6});
        mapper.insert(value);

        value.setStatus("OK");
        value.setLastError(null);
        mapper.updateById(value);

        AttendanceConnectorEntity stored = mapper.selectById("member-1");
        assertThat(stored.getStatus()).isEqualTo("OK");
        assertThat(stored.getLastError()).isNull();
        assertThat(stored.getSessionCiphertext()).containsExactly(1,2,3);
        assertThat(stored.getSessionNonce()).containsExactly(4,5,6);
    }
}
