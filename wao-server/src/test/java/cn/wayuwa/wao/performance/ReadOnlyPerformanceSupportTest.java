package cn.wayuwa.wao.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

class ReadOnlyPerformanceSupportTest {
    interface ProbeMapper {
        @Select("SELECT 1 UNION ALL SELECT 2") List<Integer> rows();
        @Insert("INSERT INTO must_not_exist VALUES (1)") int forbiddenWrite();
    }

    @Test
    void countsActualReadsWithoutSessionCacheAndRejectsWritesBeforeDatabase() {
        var source = new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:readonly-benchmark-probe", "sa", "");
        var config = new Configuration(new Environment("probe", new JdbcTransactionFactory(), source));
        config.setCacheEnabled(false);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        var counter = new ReadOnlyPerformanceSupport.QueryCounter();
        config.addInterceptor(counter);
        config.addMapper(ProbeMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(config).openSession()) {
            var mapper = session.getMapper(ProbeMapper.class);
            assertThat(mapper.rows()).containsExactly(1, 2);
            assertThat(mapper.rows()).containsExactly(1, 2);
            assertThat(counter.queries).isEqualTo(2);
            assertThat(counter.returnedRows).isEqualTo(4);
            assertThatThrownBy(mapper::forbiddenWrite).hasRootCauseMessage("只读基准拒绝非 SELECT 操作");
            assertThat(counter.queries).isEqualTo(2);
        }
    }

    @Test
    void medianHandlesEvenOddAndUnsortedSamples() {
        assertThat(ReadOnlyPerformanceSupport.median(List.of(30.0, 10.0, 20.0))).isEqualTo(20.0);
        assertThat(ReadOnlyPerformanceSupport.median(List.of(40.0, 10.0, 30.0, 20.0))).isEqualTo(25.0);
        assertThatThrownBy(() -> ReadOnlyPerformanceSupport.median(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportKeepsCountsAndLengthsWithoutSerializingPrivateResults() throws Exception {
        var json = new ObjectMapper();
        var counter = new ReadOnlyPerformanceSupport.QueryCounter();
        var calls = new AtomicInteger();
        var scenario = ReadOnlyPerformanceSupport.measure("probe", () -> {
            calls.incrementAndGet(); counter.queries++; counter.returnedRows += 2;
            return Map.of("private-field", "private-content");
        }, counter, json, 2, 5);
        assertThat(calls).hasValue(7);
        assertThat(scenario.samples()).hasSize(5).allSatisfy(sample -> {
            assertThat(sample.queries()).isEqualTo(1);
            assertThat(sample.returnedRows()).isEqualTo(2);
            assertThat(sample.resultBytes()).isPositive();
        });
        assertThat(scenario.outputsStable()).isTrue();
        assertThat(json.writeValueAsString(scenario)).doesNotContain("private-field", "private-content");
    }

    @Test
    void equalByteLengthsDoNotHideDifferentOutputs() throws Exception {
        var calls = new AtomicInteger();
        var scenario = ReadOnlyPerformanceSupport.measure("changing", () -> calls.incrementAndGet() % 2 == 0 ? "aa" : "bb",
                new ReadOnlyPerformanceSupport.QueryCounter(), new ObjectMapper(), 0, 3);
        assertThat(scenario.samples()).extracting(ReadOnlyPerformanceSupport.Sample::resultBytes).containsOnly(4);
        assertThat(scenario.outputsStable()).isFalse();
    }

    @Test
    void rejectsUnboundedOrTooFewSamples() {
        for (int count : new int[] { 0, 2, 21 }) {
            assertThatThrownBy(() -> ReadOnlyPerformanceSupport.measure("probe", () -> null,
                    new ReadOnlyPerformanceSupport.QueryCounter(), new ObjectMapper(), 0, count))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
