package cn.wayuwa.wao.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/** 只读基准的统计与执行边界；位于测试源码，不进入应用产物。 */
final class ReadOnlyPerformanceSupport {
    private ReadOnlyPerformanceSupport() { }

    record Sample(double elapsedMs, int queries, long returnedRows, int resultBytes) { }
    record Scenario(String id, List<Sample> samples, double medianMs, double minMs, double maxMs,
                    boolean outputsStable) { }

    static double median(List<Double> values) {
        if (values.isEmpty()) throw new IllegalArgumentException("样本不能为空");
        var sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0 ? (sorted.get(middle - 1) + sorted.get(middle)) / 2 : sorted.get(middle);
    }

    static Scenario measure(String id, Supplier<?> call, QueryCounter counter, ObjectMapper json,
                            int warmups, int repetitions) throws Exception {
        if (warmups < 0 || warmups > 5 || repetitions < 3 || repetitions > 20) {
            throw new IllegalArgumentException("预热范围为 0—5，采样范围为 3—20");
        }
        for (int i = 0; i < warmups; i++) call.get();
        var samples = new ArrayList<Sample>();
        byte[] firstHash = null;
        boolean stable = true;
        for (int i = 0; i < repetitions; i++) {
            counter.reset();
            long started = System.nanoTime();
            Object result = call.get();
            double elapsed = (System.nanoTime() - started) / 1_000_000.0;
            // JSON 序列化不计入服务方法耗时；只保存字节数，不把正文或内容哈希写入报告。
            byte[] bytes = json.writeValueAsString(result).getBytes(StandardCharsets.UTF_8);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            if (firstHash == null) firstHash = hash;
            else stable &= Arrays.equals(firstHash, hash);
            samples.add(new Sample(elapsed, counter.queries, counter.returnedRows, bytes.length));
        }
        List<Double> times = samples.stream().map(Sample::elapsedMs).toList();
        return new Scenario(id, samples, median(times), times.stream().mapToDouble(Double::doubleValue).min().orElseThrow(),
                times.stream().mapToDouble(Double::doubleValue).max().orElseThrow(), stable);
    }

    @Intercepts({
        @Signature(type = Executor.class, method = "query", args = { MappedStatement.class, Object.class,
                RowBounds.class, ResultHandler.class }),
        @Signature(type = Executor.class, method = "update", args = { MappedStatement.class, Object.class })
    })
    static final class QueryCounter implements Interceptor {
        int queries;
        long returnedRows;

        void reset() { queries = 0; returnedRows = 0; }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            if (statement.getSqlCommandType() != SqlCommandType.SELECT) {
                throw new IllegalStateException("只读基准拒绝非 SELECT 操作");
            }
            queries++;
            try {
                Object result = invocation.proceed();
                if (result instanceof List<?> rows) returnedRows += rows.size();
                return result;
            } catch (InvocationTargetException error) {
                throw error.getTargetException();
            }
        }
    }
}
