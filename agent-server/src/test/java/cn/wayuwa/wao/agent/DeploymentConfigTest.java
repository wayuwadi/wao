package cn.wayuwa.wao.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeploymentConfigTest {

    @Test
    void productionUsesWaoSchemaSharedSessionAndIndependentFlywayHistory() throws IOException {
        String common = read("agent-server/src/main/resources/application.yml");
        String prod = read("agent-server/src/main/resources/application-prod.yml");
        String migration = read("agent-server/src/main/resources/db/migration/V1__initial_schema.sql");

        assertThat(common)
                .contains("name: WAO_SESSION")
                .contains("initialize-schema: never");
        assertThat(prod)
                .contains("${DB_NAME:wao}")
                .contains("table: flyway_agent_schema_history")
                .contains("baseline-version: 0")
                .contains("local-account-id: \"\"");
        assertThat(migration)
                .contains("CREATE TABLE agent_workspace")
                .contains("REFERENCES account (id)")
                .doesNotContain("CREATE TABLE account", "CREATE TABLE SPRING_SESSION");
    }

    @Test
    void flywayMigrationVersionsAreUnique() throws IOException {
        Path directory = repositoryPath("agent-server/src/main/resources/db/migration");
        List<String> versions;
        try (var files = Files.list(directory)) {
            versions = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("V[^_]+__.+\\.sql"))
                    .map(name -> name.substring(1, name.indexOf("__")))
                    .toList();
        }
        assertThat(versions).doesNotHaveDuplicates();
    }

    @Test
    void masterComposeSupportsExistingMysqlOnLinuxHost() throws IOException {
        String masterCompose = read("deploy/docker-compose-master.yml");

        assertThat(masterCompose)
                .contains("MYSQL_HOST: ${MYSQL_HOST:-mysql-master}")
                .contains("host.docker.internal:host-gateway");
    }

    @Test
    void unifiedJenkinsPipelineBuildsAndDeploysAgentWithWao() throws IOException {
        String pipeline = read("Jenkinsfile");
        String changePlan = read("deploy/jenkins/ci-change-plan.sh");
        String backendBuild = read("deploy/jenkins/ci-backend-test.sh");
        String frontendBuild = read("deploy/jenkins/ci-frontend-build.sh");
        String rollout = read("deploy/jenkins/ci-deploy.sh");
        String verification = read("deploy/jenkins/ci-production-verify.sh");

        assertThat(backendBuild)
                .contains("-v \"$cache:/tmp/m2\"")
                .contains("WAO_BACKEND_PROJECTS:-all")
                .contains("-pl \"$maven_projects\" -am")
                .contains("set -- \"$@\" test");
        assertThat(frontendBuild)
                .contains("wao-web|agent-web")
                .contains("run_npm \"$project\" run test:unit -- --run")
                .contains("run_npm \"$project\" run build");
        assertThat(changePlan)
                .contains("WAO_DEPLOY_REQUIRED=$deploy_required")
                .contains("force_full_validation")
                .contains("未登记路径一律回退全量");
        assertThat(pipeline)
                .contains("Parallel tests and frontend builds")
                .contains("env.WAO_DEPLOY_REQUIRED == 'true'")
                .contains("ci-frontend-build.sh wao-web")
                .contains("ci-frontend-build.sh agent-web");
        assertThat(rollout)
                .contains("agent-web/dist/.")
                .contains("/var/www/wao-agent/dist");
        assertThat(verification)
                .contains("/api/agent/health");
    }

    /**
     * 本地 H2 与生产 Flyway 是两套独立脚本，容易改一边忘另一边；这里锁死两边的 agent_ 表集合一致。
     */
    @Test
    void localSchemaMirrorsEveryAgentTableInFlywayMigrations() throws IOException {
        Path migrations = repositoryPath("agent-server/src/main/resources/db/migration");
        StringBuilder combined = new StringBuilder();
        try (var files = Files.list(migrations)) {
            for (Path file : files.sorted().toList()) combined.append(Files.readString(file));
        }

        assertThat(agentTables(read("agent-server/src/main/resources/db/local/schema.sql")))
                .containsAll(agentTables(combined.toString()));
    }

    @Test
    void bothNodesKeepMediaOnTheirOwnVolumeAndShareTheCredentialKey() throws IOException {
        String master = read("deploy/docker-compose-master.yml");
        String slave = read("deploy/docker-compose-slave.yml");

        for (String compose : List.of(master, slave)) {
            assertThat(compose)
                    .contains("AGENT_MEDIA_DIR: /var/lib/wao-agent/media")
                    .contains("wao-agent-media:/var/lib/wao-agent/media")
                    // 两台节点必须用同一把密钥，否则另一台解不开已落库的平台令牌。
                    // 必须带默认值：节点 .env 还没有这个变量时 compose 不该注入空串并让容器起不来。
                    .contains("AGENT_CREDENTIAL_KEY: ${AGENT_CREDENTIAL_KEY:-}");
        }
        assertThat(master).contains("AGENT_NODE_ID: ${AGENT_NODE_ID:-master}");
        assertThat(slave).contains("AGENT_NODE_ID: ${AGENT_NODE_ID:-slave}");
        assertThat(master).contains("name: wao-agent-master_wao-agent-media");
        assertThat(slave).contains("name: wao-agent-slave_wao-agent-media");
    }

    @Test
    void repositoryShipsNoRealPlatformCredentials() throws IOException {
        String common = read("agent-server/src/main/resources/application.yml");
        String master = read("deploy/docker-compose-master.yml");

        assertThat(master)
                .contains("DOUYIN_CLIENT_KEY: ${DOUYIN_CLIENT_KEY:-}")
                .contains("DOUYIN_CLIENT_SECRET: ${DOUYIN_CLIENT_SECRET:-}")
                .contains("AGENT_AI_API_KEY: ${AGENT_AI_API_KEY:-}");
        assertThat(common)
                .contains("${DOUYIN_CLIENT_SECRET:}")
                .contains("${AGENT_AI_API_KEY:}")
                .contains("${AGENT_CREDENTIAL_KEY:}");
    }

    @Test
    void retiredVideoApiIsNotExposedOrConfigured() throws IOException {
        String common = read("agent-server/src/main/resources/application.yml");
        String master = read("deploy/docker-compose-master.yml");
        String slave = read("deploy/docker-compose-slave.yml");
        String pipeline = read("Jenkinsfile");
        String rollout = read("deploy/jenkins/ci-deploy.sh");
        String nginx = read("deploy/nginx/wao.wayuwa.cn.conf.template");

        assertThat(List.of(common, master, slave, pipeline, rollout))
                .allSatisfy(config -> assertThat(config).doesNotContain("AGENT_VIDEO_"));
        assertThat(nginx)
                .doesNotContain("/api/agent/videos", "linux-vpn-master-video");
    }

    private static List<String> agentTables(String sql) {
        var matcher = java.util.regex.Pattern
                .compile("CREATE TABLE (?:IF NOT EXISTS )?(agent_\\w+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(sql);
        List<String> tables = new java.util.ArrayList<>();
        while (matcher.find()) tables.add(matcher.group(1).toLowerCase(java.util.Locale.ROOT));
        return tables;
    }

    private static String read(String relative) throws IOException {
        return Files.readString(repositoryPath(relative));
    }

    private static Path repositoryPath(String relative) {
        Path path = Path.of(relative);
        return Files.exists(path) ? path : Path.of("..").resolve(relative);
    }
}
