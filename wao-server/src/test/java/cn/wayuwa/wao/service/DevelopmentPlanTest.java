package cn.wayuwa.wao.service;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
class DevelopmentPlanTest {
    @Test void exactPathsCannotEscapeAndBroadSensitiveChangesRequireDecision() {
        assertThat(DevelopmentPlan.safePath("wao-web/src/views/HomeView.vue")).isTrue();
        for(String path:new String[]{"wao-web/src/../.env", "wao-web/src/x\rfoo", "/tmp/x", "deploy/a.ps1", "wao-web/src/*.vue"})
            assertThat(DevelopmentPlan.safePath(path)).isFalse();
        assertThat(DevelopmentPlan.important("wao-web/src/")).isTrue();
        assertThat(DevelopmentPlan.important("wao-server/src/main/java/cn/wayuwa/wao/service/AccountService.java")).isTrue();
        assertThat(DevelopmentPlan.important("wao-web/src/views/HomeView.vue")).isFalse();
    }
    @Test void cycleUnknownDependencyAndNonFirstRecommendationFailClosed() throws Exception {
        var json=new ObjectMapper();
        var plan=json.readTree("""
          {"goal":"真实改进","options":[{"title":"A","description":"小改动"},{"title":"B","description":"重构"},{"title":"C","description":"暂缓"}],"recommendedIndex":0,"agents":[
          {"id":"a","goal":"改进","paths":["wao-web/src/views/HomeView.vue"],"dependsOn":["b"]},
          {"id":"b","goal":"改进","paths":["wao-web/src/views/HomeView.vue"],"dependsOn":["a"]}]}
          """);
        assertThatThrownBy(()->DevelopmentPlan.validate(plan)).hasMessageContaining("循环");
        ((com.fasterxml.jackson.databind.node.ArrayNode)plan.path("agents").get(0).path("dependsOn")).removeAll();
        DevelopmentPlan.validate(plan);
        ((com.fasterxml.jackson.databind.node.ObjectNode)plan).put("recommendedIndex",1);
        assertThatThrownBy(()->DevelopmentPlan.validate(plan)).hasMessageContaining("首项");
    }
    @Test void blockedAuditNeverBecomesNoChangeSuccess() throws Exception {
        var json=new ObjectMapper();
        assertThatThrownBy(()->DevelopmentPlan.validate(json.readTree("{\"noChange\":true,\"blockedReason\":\"无法读取代码\"}"))).hasMessageContaining("未完成");
        assertThatThrownBy(()->DevelopmentPlan.validate(json.readTree("{\"noChange\":true}"))).hasMessageContaining("证据");
    }
    @Test void sandboxCommandNeverUsesBypassOrCarriesDatabaseEnvironment() {
        var cmd=DevelopmentCodexProcess.command("codex",java.nio.file.Path.of("work"),java.nio.file.Path.of("schema"),java.nio.file.Path.of("output"),true);
        assertThat(cmd).contains("read-only","--ignore-user-config","--output-schema").doesNotContain("--dangerously-bypass-approvals-and-sandbox");
        assertThat(DevelopmentWorker.safeMessage("password=abc token=def ghp_abcdef")).doesNotContain("abc","def","ghp_");
    }
    @Test void writableAgentAddsOnlyItsDedicatedOutputButReadOnlyAuditDoesNot() {
        var work=java.nio.file.Path.of("work");
        var output=java.nio.file.Path.of("state/run/output-agent/result.json").toAbsolutePath();
        var write=DevelopmentCodexProcess.command("codex",work,java.nio.file.Path.of("schema"),output,false);
        assertThat(java.util.Collections.frequency(write,"--add-dir")).isEqualTo(1);
        assertThat(write.get(write.indexOf("--add-dir")+1)).isEqualTo(output.getParent().toString());
        assertThat(DevelopmentCodexProcess.command("codex",work,java.nio.file.Path.of("schema"),output,true)).doesNotContain("--add-dir");
    }
}
