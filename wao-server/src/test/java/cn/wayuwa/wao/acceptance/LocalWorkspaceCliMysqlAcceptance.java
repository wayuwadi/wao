package cn.wayuwa.wao.acceptance;

import cn.wayuwa.wao.WaoServerApplication;
import cn.wayuwa.wao.auth.AuthSessionManager;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.CreateWorkspaceMcpTokenRequest;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.service.WorkspaceCapabilityPolicy;
import cn.wayuwa.wao.service.WorkspaceMcpTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import jakarta.servlet.http.HttpServletRequest;

/** 显式启用的真实本机 MySQL + HTTP + Node CLI 验收，绝不打包进生产应用。 */
public final class LocalWorkspaceCliMysqlAcceptance {
    private static volatile String fixtureMember;
    private LocalWorkspaceCliMysqlAcceptance() { }

    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("WAO_TEST_WORKSPACE_CLI_MYSQL")))
            throw new IllegalArgumentException("需显式设置 WAO_TEST_WORKSPACE_CLI_MYSQL=true");
        if (args.length != 2) throw new IllegalArgumentException("参数为 Node 验收脚本及证据目录的绝对路径");
        Path script = Path.of(args[0]), evidence = Path.of(args[1]);
        if (!script.isAbsolute() || !Files.isRegularFile(script) || !evidence.isAbsolute())
            throw new IllegalArgumentException("验收脚本及证据目录必须为绝对路径");
        String host = env("MYSQL_HOST"), db = env("DB_NAME"), port = System.getenv().getOrDefault("MYSQL_PORT", "3306");
        validateLocalHost(host, port);
        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
        String member;
        try (var connection = DriverManager.getConnection(jdbcUrl, env("DB_USER"), env("DB_PASS"))) {
            try (var query = connection.createStatement(); var result = query.executeQuery("SELECT DATABASE(), @@read_only + @@super_read_only")) {
                if (!result.next() || !db.equals(result.getString(1)) || result.getInt(2) != 0)
                    throw new IllegalStateException("本机数据库必须匹配配置且可写");
            }
            String requested = System.getenv("WAO_ACCEPTANCE_MEMBER_ID");
            String sql = "SELECT m.id, m.name FROM account a JOIN member m ON m.id=a.member_id WHERE a.status='ACTIVE' AND "
                    + (requested != null && !requested.isBlank() ? "m.id=?" : "(m.name='wage' OR m.id='wg')")
                    + " ORDER BY (m.name='wage') DESC LIMIT 1";
            try (var query = connection.prepareStatement(sql)) {
                if (requested != null && !requested.isBlank()) query.setString(1, requested);
                try (var result = query.executeQuery()) {
                    if (!result.next()) throw new IllegalStateException("未找到有效的真实 wage/wg 成员；可显式传 WAO_ACCEPTANCE_MEMBER_ID");
                    member = result.getString(1);
                    System.out.println("本机 CLI 验收成员：" + member + " / " + result.getString(2));
                }
            }
        }
        Files.createDirectories(evidence);
        fixtureMember = member;
        int hold = Integer.parseInt(System.getenv().getOrDefault("WAO_ACCEPTANCE_HOLD_SECONDS", "0"));
        if (hold < 0 || hold > 600) throw new IllegalArgumentException("保留时间须为 0–600 秒");
        boolean browserLogin = "true".equals(System.getenv("WAO_ACCEPTANCE_BROWSER_LOGIN"));
        var app = new SpringApplication(WaoServerApplication.class, NoExternalNotifications.class);
        app.setAdditionalProfiles("local-cloud");
        try (var context = app.run("--server.address=127.0.0.1", "--server.port=18084",
                "--spring.datasource.url=" + jdbcUrl, "--spring.flyway.enabled=true",
                "--wao.scheduling.enabled=false", "--wao.chat-review.processing-enabled=false",
                "--wao.codex-quota.enabled=false", "--wao.ai.discoveries.enabled=false",
                "--wao.ai.read-only.cleanup-enabled=false", "--wao.tianren.notification-scheduler-enabled=false",
                "--feishu.login.enabled=false", "--logging.level.root=ERROR",
                "--server.servlet.session.cookie.name=WAO_CLI_ACCEPTANCE_SESSION",
                "--wao.acceptance.browser-login.enabled=" + browserLogin)) {
            var tokens = context.getBean(WorkspaceMcpTokenService.class);
            String prefix = "[MCP CLI 本地验收] " + UUID.randomUUID().toString().substring(0, 8);
            var issued = tokens.create(member, new CreateWorkspaceMcpTokenRequest(prefix, null, null,
                    WorkspaceCapabilityPolicy.ALL.stream().filter(p -> !"publish_html".equals(p)).toList(), null, null, "CLI"));
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("url", "http://127.0.0.1:18084/api/mcp/workspace");
                payload.put("token", issued.secret()); payload.put("memberId", member);
                payload.put("prefix", prefix); payload.put("evidenceDir", evidence.toString());
                String node = System.getenv().getOrDefault("WAO_ACCEPTANCE_NODE", "node");
                var builder = new ProcessBuilder(node, script.toString());
                // Node 只获得必要运行环境，数据库凭据不传给子进程；令牌仅通过 stdin 内存传递。
                var inherited = Map.copyOf(builder.environment()); builder.environment().clear();
                for (String name : new String[]{"PATH", "Path", "SystemRoot", "SYSTEMROOT", "TEMP", "TMP", "USERPROFILE", "HOME"})
                    if (inherited.containsKey(name)) builder.environment().put(name, inherited.get(name));
                Process child = builder.start();
                try (var input = child.getOutputStream()) {
                    input.write(context.getBean(ObjectMapper.class).writeValueAsBytes(payload));
                }
                var stdout = new Thread(() -> relay(child.getInputStream(), issued.secret(), false));
                var stderr = new Thread(() -> relay(child.getErrorStream(), issued.secret(), true));
                stdout.start(); stderr.start();
                if (!child.waitFor(240, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new IllegalStateException("验收超过 240 秒，已停止 Node 子进程"); }
                stdout.join(); stderr.join();
                if (child.exitValue() != 0) throw new IllegalStateException("真实 CLI 验收失败，退出码 " + child.exitValue());
                var summary = context.getBean(ObjectMapper.class).readTree(Files.readString(evidence.resolve("summary.json")));
                var jdbc = context.getBean(JdbcTemplate.class);
                for (String field : new String[]{"noteId", "secondNoteId", "dailyNoteId"}) {
                    String id = summary.path("fixtures").path(field).asText();
                    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM doc WHERE id=? AND author_id=? AND title LIKE ?", Integer.class, id, member, prefix + "%");
                    if (count == null || count != 1) throw new IllegalStateException("真实 MySQL fixture 回读失败：" + field);
                    Integer queued = jdbc.queryForObject("SELECT COUNT(*) FROM note_ai_workflow WHERE doc_id=? AND (title_status IN ('PENDING','RUNNING') OR image_status IN ('PENDING','RUNNING') OR polish_status IN ('PENDING','RUNNING') OR title_job_id IS NOT NULL OR image_run_id IS NOT NULL OR polish_job_id IS NOT NULL)", Integer.class, id);
                    if (queued == null || queued != 0) throw new IllegalStateException("MCP 新小记不应产生 AI 入队：" + field);
                }
                ((com.fasterxml.jackson.databind.node.ObjectNode) summary).put("mysqlPersistenceVerified", true).put("mysqlNoAiQueueVerified", true);
                Files.writeString(evidence.resolve("summary.json"), context.getBean(ObjectMapper.class).writerWithDefaultPrettyPrinter().writeValueAsString(summary));
                System.out.println("真实 MySQL 文档落库及无 AI 入队复核通过。");
            } finally {
                tokens.revoke(member, issued.token().id());
                if (tokens.authenticate(issued.secret()) != null) throw new IllegalStateException("临时令牌撤销未生效");
                Path report = evidence.resolve("summary.json");
                if (Files.isRegularFile(report)) {
                    var value = (com.fasterxml.jackson.databind.node.ObjectNode) context.getBean(ObjectMapper.class).readTree(Files.readString(report));
                    value.put("temporaryTokenRevoked", true);
                    Files.writeString(report, context.getBean(ObjectMapper.class).writerWithDefaultPrettyPrinter().writeValueAsString(value));
                }
                System.out.println("临时验收令牌已撤销；标注 fixture 保留，不删除用户或验收记录。");
            }
            if (hold > 0) {
                System.out.println("浏览器回读窗口已开始：" + hold + " 秒；后端 127.0.0.1:18084；test-only 登录 " + (browserLogin ? "已开启" : "未开启"));
                Thread.sleep(hold * 1000L);
            }
        }
    }

    static void validateLocalHost(String host, String port) {
        if (!java.util.List.of("127.0.0.1", "localhost").contains(host) || !port.matches("[0-9]{1,5}")
                || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535)
            throw new IllegalArgumentException("验收只允许明确的本机 MySQL 地址和有效端口");
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少环境变量：" + name);
        return value;
    }

    private static void relay(java.io.InputStream input, String secret, boolean error) {
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line; while ((line = reader.readLine()) != null) {
                String safe = line.replace(secret, "[REDACTED]");
                if (error) System.err.println(safe); else System.out.println(safe);
            }
        } catch (Exception e) { System.err.println("验收输出读取中断：" + e.getClass().getSimpleName()); }
    }

    @TestConfiguration
    static class NoExternalNotifications {
        /** 仅封锁外部通知出口；业务服务、权限、事务和数据库均为真实实例。 */
        @Bean @Primary FeishuWebhookNotifier acceptanceFeishuSink() {
            return org.mockito.Mockito.mock(FeishuWebhookNotifier.class);
        }
    }

    /** 只在本 test classpath、显式 opt-in 与回环端口启用，为固定真实成员建立短期验收会话。 */
    @RestController
    @ConditionalOnProperty(name="wao.acceptance.browser-login.enabled", havingValue="true")
    static class BrowserFixtureLogin {
        private final AuthSessionManager sessions;
        private final JdbcTemplate jdbc;
        BrowserFixtureLogin(AuthSessionManager sessions, JdbcTemplate jdbc) { this.sessions = sessions; this.jdbc = jdbc; }

        private boolean local(HttpServletRequest request) {
            return "true".equals(System.getenv("WAO_TEST_WORKSPACE_CLI_MYSQL")) && fixtureMember != null
                    && request.getLocalPort() == 18084
                    && java.util.List.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1").contains(request.getRemoteAddr());
        }

        @GetMapping("/api/auth/acceptance-cli-login")
        ResponseEntity<String> loginPage(HttpServletRequest request) {
            if (!local(request)) throw new UnauthorizedException("该本机验收会话入口不可用");
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body("""
                    <!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>本机 CLI 验收登录</title>
                    <body><h1>本机 CLI 验收登录</h1><p>仅登录本次选定的真实验收成员，使用独立短期 Cookie。</p>
                    <form method="post" action="/api/auth/acceptance-cli-login"><button type="submit">登录验收成员</button></form>
                    </body></html>
                    """);
        }

        @PostMapping("/api/auth/acceptance-cli-login")
        ApiResponse<Map<String, String>> login(HttpServletRequest request) {
            String origin = request.getHeader("Origin");
            if (!local(request) || origin == null || !java.util.List.of("http://127.0.0.1:5235", "http://localhost:5235").contains(origin))
                throw new UnauthorizedException("该本机验收会话入口不可用");
            var account = jdbc.queryForList("SELECT a.id, m.name FROM account a JOIN member m ON m.id=a.member_id WHERE a.member_id=? AND a.status='ACTIVE'", fixtureMember);
            if (account.size() != 1) throw new UnauthorizedException("验收成员账号不可用");
            sessions.login(request, account.get(0).get("id").toString());
            request.getSession().setMaxInactiveInterval(900);
            return ApiResponse.ok(Map.of("memberId", fixtureMember, "name", account.get(0).get("name").toString()));
        }
    }
}
