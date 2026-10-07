package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** 显式选择才运行的真实本机 CLI 合约验收；不消耗普通单元测试的模型额度。 */
@EnabledIfSystemProperty(named="wao.development.processSmoke",matches="true")
class DevelopmentCodexProcessSmokeTest {
    @TempDir Path directory;
    @Test void managedCliReadsFixtureInReadOnlySandboxAndReturnsStructuredEvidence() throws Exception {
        Path work=directory.resolve("work");Files.createDirectories(work);
        DevelopmentWorker.command(work,java.util.List.of("git","init"));
        Files.writeString(work.resolve("probe.txt"),"fixture-verified-421");
        Path schema=directory.resolve("schema.json");
        try(var input=getClass().getResourceAsStream("/development-result.schema.json")){Files.write(schema,input.readAllBytes());}
        var result=DevelopmentCodexProcess.run(System.getProperty("wao.development.executable","codex"),work,directory.resolve("output"),schema,
            "这是本机只读执行器合约验收。用shell读取当前目录probe.txt，在validation中记录其精确内容。不得改文件、不联网、不读取其他目录。返回noChange=true。所有不适用字段为空字符串、空数组或false；recommendedIndex=0。",true,Duration.ofMinutes(4),id->{});
        assertThat(result.sessionId()).matches("[0-9a-f-]{36}");
        assertThat(result.answer().path("noChange").asBoolean()).isTrue();
        assertThat(result.answer().path("validation").toString()).as("实际合约结果：%s",result.answer()).contains("fixture-verified-421");
        assertThat(result.successfulCommands()).isPositive();
        assertThat(Files.readString(work.resolve("probe.txt"))).isEqualTo("fixture-verified-421");
        var written=DevelopmentCodexProcess.run(System.getProperty("wao.development.executable","codex"),work,directory.resolve("write-output"),schema,
            "这是本机workspace-write合约验收。只把probe.txt内容改成fixture-written-422，读取确认后在validation中记录精确内容。不得联网、不读取其他目录、不commit。noChange=false；所有不适用字段为空字符串、空数组或false；recommendedIndex=0。",false,Duration.ofMinutes(4),id->{});
        assertThat(written.answer().path("blockedReason").asText()).isBlank();
        assertThat(Files.readString(work.resolve("probe.txt"))).isEqualTo("fixture-written-422");
    }
}
