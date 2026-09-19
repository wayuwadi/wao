package cn.wayuwa.wao.performance;

import cn.wayuwa.wao.mapper.AiQuotaSnapshotMapper;
import cn.wayuwa.wao.mapper.AiRunnerNodeMapper;
import cn.wayuwa.wao.mapper.AiTaskRunMapper;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import cn.wayuwa.wao.service.DashboardService;
import cn.wayuwa.wao.service.KnowledgeService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 显式启用、单线程、复用既有数据库；无 Spring 启动、迁移或后台任务。 */
@EnabledIfEnvironmentVariable(named = "WAO_PERFORMANCE_BENCHMARK", matches = "true")
class PerformanceBaselineReadOnlyTest {
    @Test
    void captureCurrentServiceBaselineWithoutWrites() throws Exception {
        String phase = "配置";
        try {
            String host = required("MYSQL_HOST");
            String port = System.getenv().getOrDefault("MYSQL_PORT", "3306");
            String database = required("DB_NAME");
            if (!host.matches("[A-Za-z0-9.:-]+") || !port.matches("[0-9]{1,5}") || !database.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("连接参数格式无效");
            }
            String docId = System.getenv().getOrDefault("WAO_PERFORMANCE_DOC_ID", "");
            if (!docId.isBlank() && !docId.matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException("文档 ID 无效");
            int repetitions = bounded("WAO_PERFORMANCE_SAMPLES", 5, 3, 20);
            int warmups = bounded("WAO_PERFORMANCE_WARMUPS", 2, 0, 5);
            var json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            var counter = new ReadOnlyPerformanceSupport.QueryCounter();
            var config = new MybatisConfiguration();
            phase = "MyBatis配置";
            config.setEnvironment(new Environment("benchmark", new JdbcTransactionFactory(), new UnpooledDataSource()));
            config.setMapUnderscoreToCamelCase(true);
            config.setLocalCacheScope(LocalCacheScope.STATEMENT);
            config.setCacheEnabled(false);
            config.setDefaultStatementTimeout(10);
            config.setLogImpl(NoLoggingImpl.class);
            GlobalConfigUtils.setGlobalConfig(config, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()).setBanner(false));
            config.addInterceptor(counter);
            phase = "注册Mapper";
            for (Class<?> mapper : List.of(AiTaskRunMapper.class, TaskMapper.class, AiRunnerNodeMapper.class,
                    AiQuotaSnapshotMapper.class, DocMapper.class)) config.addMapper(mapper);
            phase = "创建MyBatis工厂";
            var factory = new MybatisSqlSessionFactoryBuilder().build(config);
            var credentials = new Properties();
            credentials.setProperty("user", required("DB_USER"));
            credentials.setProperty("password", required("DB_PASS"));
            String address = host.contains(":") ? "[" + host + "]" : host;
            String url = "jdbc:mysql://" + address + ":" + port + "/" + database
                    + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                    + "&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=15000";
            phase = "建立只读事务";
            var report = new LinkedHashMap<String, Object>();
            report.put("schemaVersion", 1);
            report.put("startedAt", Instant.now().toString());
            report.put("revision", System.getProperty("wao.benchmark.revision", "unknown"));
            report.put("workingTree", System.getProperty("wao.benchmark.workingTree", "unknown"));
            report.put("warmups", warmups);
            report.put("repetitions", repetitions);
            report.put("javaVersion", System.getProperty("java.version"));
            report.put("measurement", "local-service-and-database-roundtrip");
            report.put("transaction", "REPEATABLE READ / READ ONLY / consistent snapshot");
            report.put("note", "非 HTTP 压测；样本中位数不是线上 P95；返回行数不是数据库扫描行数；时间包括本机和数据库往返。");
            var scenarios = new ArrayList<ReadOnlyPerformanceSupport.Scenario>();
            try (var connection = DriverManager.getConnection(url, credentials)) {
                connection.setReadOnly(true);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(10);
                    statement.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY");
                    try (var result = statement.executeQuery("SELECT @@session.transaction_read_only")) {
                        if (!result.next() || result.getInt(1) != 1) throw new IllegalStateException("只读事务未生效");
                    }
                }
                try (var session = factory.openSession(connection)) {
                    report.put("dataShape", dataShape(connection));
                    var dashboard = new DashboardService(session.getMapper(AiTaskRunMapper.class), session.getMapper(TaskMapper.class),
                            session.getMapper(AiRunnerNodeMapper.class), session.getMapper(AiQuotaSnapshotMapper.class));
                    var knowledge = new KnowledgeService(null, null, null, session.getMapper(DocMapper.class), null, null, json, null, null);
                    phase = "dashboard-summary";
                    scenarios.add(ReadOnlyPerformanceSupport.measure(phase, dashboard::summary, counter, json, warmups, repetitions));
                    phase = "ai-trend-30d";
                    scenarios.add(ReadOnlyPerformanceSupport.measure(phase, () -> dashboard.aiRunTrend(30), counter, json, warmups, repetitions));
                    phase = "task-trend-7d";
                    scenarios.add(ReadOnlyPerformanceSupport.measure(phase, () -> dashboard.taskTrend(7), counter, json, warmups, repetitions));
                    phase = "engine-usage";
                    scenarios.add(ReadOnlyPerformanceSupport.measure(phase, dashboard::engineUsage, counter, json, warmups, repetitions));
                    phase = "usage-insights-30d";
                    scenarios.add(ReadOnlyPerformanceSupport.measure(phase, () -> dashboard.usageInsights(30), counter, json, warmups, repetitions));
                    if (!docId.isBlank()) {
                        phase = "document-read";
                        scenarios.add(ReadOnlyPerformanceSupport.measure(phase, () -> knowledge.getDoc(docId), counter, json, warmups, repetitions));
                        phase = "document-revision";
                        scenarios.add(ReadOnlyPerformanceSupport.measure(phase, () -> knowledge.getDocRevision(docId), counter, json, warmups, repetitions));
                    }
                    connection.rollback();
                }
            }
            report.put("scenarios", scenarios);
            report.put("skipped", docId.isBlank() ? List.of("document-read", "document-revision") : List.of());
            report.put("finishedAt", Instant.now().toString());
            phase = "写入本机报告";
            Path output = Path.of("target/performance", "baseline-" + System.currentTimeMillis() + ".json");
            Files.createDirectories(output.getParent());
            json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
            System.out.println("只读性能报告：" + output);
            for (var scenario : scenarios) System.out.printf("%s median=%.2fms queries=%d returnedRows=%d stable=%s%n",
                    scenario.id(), scenario.medianMs(), scenario.samples().get(0).queries(),
                    scenario.samples().get(0).returnedRows(), scenario.outputsStable());
        } catch (Exception error) {
            // JDBC/MyBatis 原始异常可能含连接地址、SQL 参数或正文。失败阶段足够定位，不把原异常写入测试报告。
            Throwable root = error;
            for (int depth = 0; root.getCause() != null && depth < 8; depth++) root = root.getCause();
            String location = root.getStackTrace().length == 0 ? "unknown" : root.getStackTrace()[0].toString();
            throw new IllegalStateException("只读基准失败，阶段：" + phase + "；类型：" + root.getClass().getSimpleName()
                    + "；位置：" + location + "。未输出原始异常、SQL 或连接信息。");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少变量 " + name);
        return value;
    }

    private static int bounded(String name, int fallback, int min, int max) {
        int value = Integer.parseInt(System.getenv().getOrDefault(name, Integer.toString(fallback)));
        if (value < min || value > max) throw new IllegalArgumentException("采样参数超出范围");
        return value;
    }

    private static Map<String, Long> dataShape(Connection connection) throws Exception {
        var shape = new LinkedHashMap<String, Long>();
        // 仅输出聚合行数；不记录文档 ID、正文、任务标题或成员。
        try (var query = connection.createStatement()) {
            query.setQueryTimeout(10);
            for (String table : List.of("ai_task_run", "task", "doc")) {
                try (var rows = query.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    rows.next(); shape.put(table, rows.getLong(1));
                }
            }
        }
        return shape;
    }
}
