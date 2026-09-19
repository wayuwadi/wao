package cn.wayuwa.wao.acceptance;

import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.service.KnowledgeService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 仅清理明确报告登记的近期验收文档；默认只读预检，显式 apply 才调用既有软删除。 */
@EnabledIfEnvironmentVariable(named = "WAO_DRAFT_CLEANUP", matches = "true")
class DraftAcceptanceCleanupTest {
    static final String PREFIX = "WAO 验收 · 草稿回归 · ";
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    record Target(String id, String title, String kbId, Instant startedAt) { }

    static List<Target> validateReport(JsonNode report, Instant now) {
        String runId = report.path("runId").asText();
        String title = PREFIX + runId;
        String kbPath = report.path("kbPath").asText();
        Instant started = Instant.parse(report.path("startedAt").asText());
        JsonNode docs = report.path("documents");
        if (!runId.matches("[A-Za-z0-9-]{1,100}") || !report.path("title").asText().equals(title)
                || !kbPath.matches("/kb/[A-Za-z0-9_-]+") || !docs.isArray() || docs.isEmpty() || docs.size() > 2
                || started.isBefore(now.minusSeconds(24 * 3600)) || started.isAfter(now.plusSeconds(60))) {
            throw new IllegalArgumentException("报告身份或时间范围不满足清理条件");
        }
        var targets = new ArrayList<Target>();
        var ids = new HashSet<String>();
        for (int index = 0; index < docs.size(); index++) {
            JsonNode doc = docs.get(index);
            String id = doc.path("id").asText();
            String expected = title + (index == 1 ? "（冲突副本）" : "");
            if (!id.matches("[A-Za-z0-9_-]{1,32}") || !ids.add(id) || !doc.path("title").asText().equals(expected)) {
                throw new IllegalArgumentException("文档 ID 或标题不满足清理条件");
            }
            targets.add(new Target(id, expected, kbPath.substring(4), started));
        }
        return targets;
    }

    static void validateDocument(Target target, DocEntity doc, Instant now) {
        LocalDateTime earliest = LocalDateTime.ofInstant(target.startedAt().minusSeconds(30), ZONE);
        LocalDateTime latest = LocalDateTime.ofInstant(now.plusSeconds(60), ZONE);
        if (doc == null || !target.id().equals(doc.getId()) || !target.title().equals(doc.getTitle())
                || !target.kbId().equals(doc.getKbId()) || doc.getCreatedAt() == null
                || doc.getCreatedAt().isBefore(earliest) || doc.getCreatedAt().isAfter(latest)
                || Boolean.TRUE.equals(doc.getAuto()) || doc.getAutoSyncScope() != null) {
            throw new IllegalArgumentException("实际文档与本轮验收记录不一致，停止全部清理");
        }
    }

    @Test
    void cleanOnlyVerifiedAcceptanceDocuments() throws Exception {
        try {
            Path reportFile = Path.of(required("WAO_DRAFT_CLEANUP_REPORT")).toRealPath();
            Path repository = Path.of("..").toRealPath();
            if (reportFile.startsWith(repository)) throw new IllegalArgumentException("报告必须位于仓库外");
            var json = new ObjectMapper();
            Instant now = Instant.now();
            var targets = validateReport(json.readTree(reportFile.toFile()), now);
            boolean apply = "true".equals(System.getenv("WAO_DRAFT_CLEANUP_APPLY"));
            String host = required("MYSQL_HOST"), database = required("DB_NAME");
            String port = System.getenv().getOrDefault("MYSQL_PORT", "3306");
            if (!host.matches("[A-Za-z0-9.:-]+") || !database.matches("[A-Za-z0-9_-]+") || !port.matches("[0-9]{1,5}")) {
                throw new IllegalArgumentException("连接参数格式无效");
            }
            var config = new MybatisConfiguration();
            config.setEnvironment(new Environment("acceptance-cleanup", new JdbcTransactionFactory(), new UnpooledDataSource()));
            config.setMapUnderscoreToCamelCase(true);
            config.setLocalCacheScope(LocalCacheScope.STATEMENT);
            config.setCacheEnabled(false);
            config.setDefaultStatementTimeout(10);
            config.setLogImpl(NoLoggingImpl.class);
            GlobalConfigUtils.setGlobalConfig(config, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()).setBanner(false));
            config.addMapper(DocMapper.class);
            var factory = new MybatisSqlSessionFactoryBuilder().build(config);
            var credentials = new Properties();
            credentials.setProperty("user", required("DB_USER"));
            credentials.setProperty("password", required("DB_PASS"));
            String address = host.contains(":") ? "[" + host + "]" : host;
            String url = "jdbc:mysql://" + address + ":" + port + "/" + database
                    + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                    + "&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=15000";
            int eligible = 0;
            try (var connection = DriverManager.getConnection(url, credentials)) {
                connection.setReadOnly(!apply);
                connection.setAutoCommit(false);
                try (var session = factory.openSession(connection)) {
                    // apply 时先按稳定顺序锁住精确行，全部验证完成之后才允许任何软删除。
                    if (apply) for (var target : targets.stream().sorted(java.util.Comparator.comparing(Target::id)).toList()) {
                        try (var lock = connection.prepareStatement("SELECT id FROM doc WHERE id = ? FOR UPDATE")) {
                            lock.setQueryTimeout(10); lock.setString(1, target.id());
                            try (var rows = lock.executeQuery()) { if (!rows.next()) throw new IllegalArgumentException("验收文档缺失"); }
                        }
                    }
                    DocMapper mapper = session.getMapper(DocMapper.class);
                    var documents = new ArrayList<DocEntity>();
                    for (var target : targets) {
                        DocEntity doc = mapper.selectById(target.id());
                        validateDocument(target, doc, now);
                        documents.add(doc);
                        if (doc.getDeletedAt() == null) eligible++;
                    }
                    if (apply) {
                        var service = new KnowledgeService(null, null, null, mapper, null, null, json, null, null);
                        for (var doc : documents) if (doc.getDeletedAt() == null) service.deleteDoc(doc.getId(), null);
                        for (var target : targets) if (mapper.selectById(target.id()).getDeletedAt() == null) {
                            throw new IllegalStateException("软删除未生效，回滚本次清理");
                        }
                        connection.commit();
                    } else connection.rollback();
                } catch (Exception error) { connection.rollback(); throw error; }
            }
            System.out.printf("acceptance-cleanup mode=%s verified=%d eligible=%d alreadyDeleted=%d%n",
                    apply ? "apply-soft-delete" : "read-only-preview", targets.size(), eligible, targets.size() - eligible);
        } catch (Exception error) {
            // 不把 JDBC/MyBatis 的原始异常、SQL、正文、连接地址或凭据写入测试报告。
            throw new IllegalStateException("验收文档清理未完成；类型：" + error.getClass().getSimpleName()
                    + "。请核对报告身份、24 小时时限和本地环境；未输出私有数据。");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少必需环境变量");
        return value;
    }
}
