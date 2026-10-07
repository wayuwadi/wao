package cn.wayuwa.wao.acceptance;
import cn.wayuwa.wao.WaoServerApplication;
import cn.wayuwa.wao.dto.CreateWorkspaceMcpTokenRequest;
import cn.wayuwa.wao.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit test-classpath acceptance; temporary scopes and a marked unlisted fixture, no production login endpoint. */
public final class PetCliMysqlAcceptance {
    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("WAO_PET_CLI_MYSQL_ACCEPTANCE")) || args.length != 3)
            throw new IllegalArgumentException("Requires explicit opt-in, Node script, atlas and evidence directory");
        if (!"127.0.0.1".equals(System.getenv("MYSQL_HOST")) || !"wao_local".equals(System.getenv("DB_NAME")))
            throw new IllegalArgumentException("Only existing loopback public runtime database allowed");
        Path script=Path.of(args[0]), atlas=Path.of(args[1]), evidence=Path.of(args[2]);
        Files.createDirectories(evidence);
        var app=new SpringApplication(WaoServerApplication.class);
        app.setAdditionalProfiles("local-cloud");
        try(var context=app.run("--server.address=127.0.0.1","--server.port=18088","--spring.flyway.enabled=false",
                "--wao.scheduling.enabled=false","--wao.chat-review.processing-enabled=false","--wao.ai.local-notes.enabled=false",
                "--wao.codex-quota.enabled=false","--wao.ai.discoveries.enabled=false","--wao.development.worker-enabled=false",
                "--wao.ai.read-only.cleanup-enabled=false","--wao.tianren.notification-scheduler-enabled=false",
                "--feishu.login.enabled=false","--logging.level.root=ERROR","--server.servlet.session.cookie.name=WAO_PET_TEST_SESSION")) {
            var jdbc=context.getBean(JdbcTemplate.class);
            String member=System.getenv("WAO_PET_ACCEPTANCE_MEMBER_ID");
            if(member==null || jdbc.queryForObject("SELECT COUNT(*) FROM account a JOIN member m ON m.id=a.member_id WHERE a.member_id=? AND a.status='ACTIVE' AND m.name='wage'",Integer.class,member)!=1)
                throw new IllegalStateException("Verified wage member required");
            var json=context.getBean(ObjectMapper.class);
            String slug="pet-cli-acceptance-"+UUID.randomUUID().toString().substring(0,8);
            var manifest=json.createObjectNode().put("id",slug).put("displayName","[CLI验收专用] Pet 上传与下架")
                    .put("description","明确标记的CLI真实链路验收，最终保持私人，不用于分享。")
                    .put("spritesheetPath","spritesheet.png").put("spriteVersionNumber",2).put("kind","animal");
            var pack=context.getBean(PetPackageInspector.class).create(manifest.toString(),Files.readAllBytes(atlas));
            Path fixture=evidence.resolve("fixture-pet.zip");Files.write(fixture,pack.archive());
            var tokens=context.getBean(WorkspaceMcpTokenService.class);
            var granted=tokens.create(member,new CreateWorkspaceMcpTokenRequest("Pet CLI temporary acceptance",null,null,
                    List.of("list_my_pets","upload_pet","share_pet"),null,null,"CLI"));
            try {
                var payload=new LinkedHashMap<String,Object>();payload.put("url","http://127.0.0.1:18088/api/mcp/workspace");
                payload.put("token",granted.secret());payload.put("fixture",fixture.toString());payload.put("evidenceDir",evidence.toString());
                var builder=new ProcessBuilder("node",script.toString());
                var saved=Map.copyOf(builder.environment());builder.environment().clear();
                for(String key:List.of("PATH","Path","SystemRoot","TEMP","TMP","USERPROFILE"))if(saved.containsKey(key))builder.environment().put(key,saved.get(key));
                var child=builder.start();try(var input=child.getOutputStream()){input.write(json.writeValueAsBytes(payload));}
                var output=new Thread(()->relay(child.getInputStream(),granted.secret()));
                var errors=new Thread(()->relay(child.getErrorStream(),granted.secret()));output.start();errors.start();
                if(!child.waitFor(150,TimeUnit.SECONDS)){child.destroyForcibly();throw new IllegalStateException("CLI acceptance timed out");}
                output.join();errors.join();if(child.exitValue()!=0)throw new IllegalStateException("CLI acceptance failed");
                var summary=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Files.readString(evidence.resolve("summary.json")));
                if(jdbc.queryForObject("SELECT COUNT(*) FROM pet_package p JOIN skill_publisher s ON s.id=p.publisher_id WHERE p.id=? AND s.member_id=? AND p.listed=FALSE",Integer.class,summary.path("petId").asText(),member)!=1)
                    throw new IllegalStateException("Fixture persistence/ownership/private status failed");
                summary.put("mysqlPersistenceVerified",true);Files.writeString(evidence.resolve("summary.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
            } finally {tokens.revoke(member,granted.token().id());if(tokens.authenticate(granted.secret())!=null)throw new IllegalStateException("Temporary token revocation failed");}
            System.out.println("Real MySQL Pet CLI fixture verified, unlisted; temporary token revoked.");
        }
    }
    private static void relay(java.io.InputStream stream,String secret){
        try(var r=new java.io.BufferedReader(new java.io.InputStreamReader(stream,StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)System.out.println(line.replace(secret,"[REDACTED]"));}
        catch(Exception e){throw new RuntimeException("Output relay failed",e);}
    }
}
