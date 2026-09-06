package cn.wayuwa.wao.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class PendingRhythmFocusMapperSqlTest {
    @Test
    void updateRequiresSameMemberDayAndByteExactOriginalValue() {
        var config = new Configuration();
        config.addMapper(MemberPreferenceMapper.class);
        var bound = config.getMappedStatement(MemberPreferenceMapper.class.getName() + ".compareAndSet")
                .getBoundSql(Map.of("memberId", "m1", "key", "day", "expected", "before", "value", "after"));
        assertThat(bound.getSql()).contains("member_id = ?", "pref_key = ?",
                "CAST(pref_value AS BINARY) = CAST(? AS BINARY)");
        assertThat(bound.getParameterMappings()).extracting(value -> value.getProperty())
                .containsExactly("value", "memberId", "key", "expected");
    }
}
