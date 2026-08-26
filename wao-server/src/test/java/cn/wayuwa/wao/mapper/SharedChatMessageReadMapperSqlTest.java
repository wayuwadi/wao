package cn.wayuwa.wao.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import cn.wayuwa.wao.domain.SharedChatMessageReadEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/** 验证共享聊天已读批量写入会生成一条可绑定全部行的动态 SQL。 */
class SharedChatMessageReadMapperSqlTest {

    @Test
    void batchInsertBuildsOneValuesTuplePerReadFact() {
        Configuration mybatis = new Configuration();
        mybatis.addMapper(SharedChatMessageReadMapper.class);

        BoundSql bound = mybatis.getMappedStatement(
                        SharedChatMessageReadMapper.class.getName() + ".insertBatchIgnore")
                .getBoundSql(Map.of("rows", List.of(row("r1", "m1"), row("r2", "m2"))));
        String sql = bound.getSql().replaceAll("\\s+", " ").trim();

        assertThat(sql).startsWith("INSERT IGNORE INTO shared_chat_message_read")
                .contains("VALUES (?, ?, ?, ?, ?, ?) , (?, ?, ?, ?, ?, ?)");
        assertThat(bound.getParameterMappings()).hasSize(12);
    }

    private static SharedChatMessageReadEntity row(String id, String messageId) {
        SharedChatMessageReadEntity row = new SharedChatMessageReadEntity();
        row.setId(id);
        row.setMessageId(messageId);
        row.setReaderKey("member:wg");
        row.setMemberId("wg");
        row.setSource("WAO");
        row.setReadAt(LocalDateTime.of(2026, 8, 27, 6, 30));
        return row;
    }
}
