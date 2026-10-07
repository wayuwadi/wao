package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class DevelopmentBrowserProcessTest {
    @TempDir Path directory;
    Path state, work, output, script;
    final AtomicReference<Process> launched = new AtomicReference<>();
    final AtomicReference<ProcessBuilder> invocation = new AtomicReference<>();
    static final String HEAD = "a".repeat(40);
    final ObjectMapper json = new ObjectMapper();
    void fixture() throws Exception {
        state = Files.createDirectories(directory.resolve("private state"));
        work = Files.createDirectories(state.resolve("run/worktree"));
        output = Files.createDirectories(state.resolve("run/verification"));
        script = state.resolve("trusted-browser.mjs"); Files.writeString(script, "// operator-installed fixture\n");
    }
    static String java() { return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(); }
    static String classpath() throws Exception { return Path.of(BridgeFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
    DevelopmentBrowserProcess start(String mode, Duration timeout) throws Exception {
        return DevelopmentBrowserProcess.start(state, "node", script.toString(), work, output, timeout, builder -> {
            invocation.set(builder);
            var process = new ProcessBuilder(java(), "-cp", classpath(), BridgeFixture.class.getName(), mode, output.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            launched.set(process); return process;
        });
    }
    ObjectNode proof() throws Exception {
        Path screenshot = output.resolve("browser-proof-1.png");
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", screenshot.toFile());
        var proof = json.createObjectNode().put("passed", true).put("testedHead", HEAD)
                .put("screenshot", screenshot.toString()).put("consoleErrorCount", 0)
                .put("screenshotSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(screenshot))));
        proof.set("checks", json.createArrayNode().add(json.createObjectNode().put("check", "visible:自主建设").put("passed", true)));
        return proof;
    }
    void summary(ObjectNode proof) throws Exception {
        var summary = json.createObjectNode().put("testedHead", HEAD).set("proofs", json.createArrayNode().add(proof));
        Files.writeString(output.resolve("fixture-summary.json"), summary.toString());
    }
    @Test void onlyVerificationStartsConfiguredBridge() {
        for (String phase : List.of("analysis", "output-agent", "delivery"))
            assertThat(DevelopmentBrowserProcess.applies(false, "trusted.mjs", Path.of(phase))).isFalse();
        assertThat(DevelopmentBrowserProcess.applies(true, "trusted.mjs", Path.of("verification"))).isFalse();
        assertThat(DevelopmentBrowserProcess.applies(false, "", Path.of("verification"))).isFalse();
        assertThat(DevelopmentBrowserProcess.applies(false, "trusted.mjs", Path.of("verification"))).isTrue();
    }
    @Test void scriptMustBePlainUtf8MjsAtStateRootNotModelWritableDirectories() throws Exception {
        fixture();
        assertThat(DevelopmentBrowserProcess.validateScript(state, script.toString())).isEqualTo(script.toRealPath());
        assertThat(DevelopmentBrowserProcess.validateScript(state, "trusted-browser.mjs")).isEqualTo(script.toRealPath());
        for (Path candidate : List.of(directory.resolve("external.mjs"), work.resolve("model.mjs"), output.resolve("model.mjs"), state.resolve("wrong.js"))) {
            Files.writeString(candidate, "// untrusted");
            assertThatThrownBy(() -> DevelopmentBrowserProcess.validateScript(state, candidate.toString())).hasMessageContaining("普通 .mjs");
        }
        assertThatThrownBy(() -> DevelopmentBrowserProcess.validateScript(state, "../external.mjs")).hasMessageContaining("普通 .mjs");
        Path folder = Files.createDirectories(state.resolve("folder.mjs"));
        assertThatThrownBy(() -> DevelopmentBrowserProcess.validateScript(state, folder.toString())).hasMessageContaining("普通 .mjs");
        Files.write(script, new byte[DevelopmentBrowserProcess.MAX_SCRIPT_BYTES + 1]);
        assertThatThrownBy(() -> DevelopmentBrowserProcess.validateScript(state, script.toString())).hasMessageContaining("100KB");
        Files.writeString(script, "a".repeat(DevelopmentBrowserProcess.MAX_SCRIPT_BYTES));
        DevelopmentBrowserProcess.validateScript(state, script.toString());
        Files.write(script, new byte[]{(byte)0xc3, 0x28});
        assertThatThrownBy(() -> DevelopmentBrowserProcess.validateScript(state, script.toString())).hasMessageContaining("UTF-8");
    }
    @Test void outsideWorkOrOutputCannotStartHostProcess() throws Exception {
        fixture(); Path outside = Files.createDirectories(directory.resolve("outside"));
        assertThatThrownBy(() -> DevelopmentBrowserProcess.start(state, "node", script.toString(), outside, output)).hasMessageContaining("state");
        assertThatThrownBy(() -> DevelopmentBrowserProcess.start(state, "node", script.toString(), work, outside)).hasMessageContaining("state");
        assertThatThrownBy(() -> DevelopmentBrowserProcess.start(state, "cmd", script.toString(), work, output)).hasMessageContaining("Node");
        assertThatThrownBy(() -> DevelopmentBrowserProcess.start(state, "node --eval arbitrary", script.toString(), work, output)).hasMessageContaining("Node");
    }
    @Test @EnabledOnOs(OS.WINDOWS)
    void directoryJunctionsCannotInjectScriptsOrEscapeBrowserOutput() throws Exception {
        fixture(); Path outside = Files.createDirectories(directory.resolve("outside"));
        Path escaped = state.resolve("escaped");
        DevelopmentEnvironment.command(state, List.of("cmd", "/c", "mklink", "/J", escaped.toString(), outside.toString()));
        assertThatThrownBy(() -> DevelopmentBrowserProcess.start(state, "node", script.toString(), escaped, output)).hasMessageContaining("state");
        assertThatThrownBy(() -> DevelopmentBrowserProcess.start(state, "node", script.toString(), work, escaped)).hasMessageContaining("state");
        Path aliasedState = directory.resolve("aliased-state");
        DevelopmentEnvironment.command(state, List.of("cmd", "/c", "mklink", "/J", aliasedState.toString(), state.toString()));
        assertThatThrownBy(() -> DevelopmentBrowserProcess.validateScript(aliasedState, "trusted-browser.mjs")).hasMessageContaining("普通 .mjs");
    }
    @Test void readyUsesOnlyTypedLoopbackContractAndFixedArgumentsWithoutLeakingEnvironmentOrOutput() throws Exception {
        fixture();
        try (var bridge = start("ready", Duration.ofSeconds(10))) {
            assertThat(bridge.instructions()).contains("http://127.0.0.1:43210", "POST /check", "visibleTexts", "clickSelectors", "GET/HEAD", "blockedReason");
            assertThat(invocation.get().command()).containsExactly("node", script.toRealPath().toString(), work.toRealPath().toString(), output.toRealPath().toString());
            assertThat(invocation.get().directory().toPath()).isEqualTo(state.toRealPath());
            assertThat(invocation.get().environment().keySet()).noneMatch(name -> name.matches("(?i).*?(DB_|FEISHU|PASSWORD|SECRET|TOKEN|NODE_OPTIONS|NODE_PATH).*"));
            assertThat(invocation.get().redirectError()).isEqualTo(ProcessBuilder.Redirect.DISCARD);
            assertThat(Files.list(output).toList()).isEmpty();
        }
        assertThat(launched.get().isAlive()).isFalse();
    }
    @Test void timeoutOrInvalidReadyStopsOnlyItsLaunchedProcessAndKeepsArtifacts() throws Exception {
        fixture(); Files.writeString(output.resolve("preserved.txt"), "previous evidence");
        assertThatThrownBy(() -> start("idle", Duration.ofMillis(150))).hasMessageContaining("未在限定时间");
        assertThat(launched.get().isAlive()).isFalse();
        assertThatThrownBy(() -> start("invalid", Duration.ofSeconds(10))).hasMessageContaining("未在限定时间");
        assertThat(launched.get().isAlive()).isFalse();
        assertThat(Files.readString(output.resolve("preserved.txt"))).isEqualTo("previous evidence");
        assertThatThrownBy(() -> start("ready", Duration.ofSeconds(61))).hasMessageContaining("60 秒");
    }
    @Test void helperExitBeforeReadyDoesNotUseNoiseAsEvidence() throws Exception {
        fixture();
        assertThatThrownBy(() -> start("exit", Duration.ofSeconds(10))).hasMessageContaining("未在限定时间");
        assertThat(launched.get().isAlive()).isFalse();
        assertThat(Files.list(output).toList()).isEmpty();
    }
    @Test void closeAfterCallerFailureKillsItsDescendantsAndLeavesUnrelatedProcessRunning() throws Exception {
        fixture();
        Process unrelated = new ProcessBuilder(java(), "-cp", classpath(), BridgeFixture.class.getName(), "idle", output.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertThatThrownBy(() -> {
                try (var bridge = start("child", Duration.ofSeconds(10))) { throw new IllegalStateException("verification failed"); }
            }).hasMessage("verification failed");
            assertThat(launched.get().isAlive()).isFalse();
            long child = Long.parseLong(Files.readString(output.resolve("child.pid")));
            var handle = ProcessHandle.of(child);
            if (handle.isPresent()) handle.get().onExit().get(5, TimeUnit.SECONDS);
            assertThat(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThat(unrelated.isAlive()).isTrue();
        } finally { unrelated.destroyForcibly(); unrelated.waitFor(5, TimeUnit.SECONDS); }
    }
    @Test void cancellationStopsLaunchedHelperAndPreservesInterrupt() throws Exception {
        fixture();
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> start("idle", Duration.ofSeconds(10))).isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        launched.get().onExit().get(5, TimeUnit.SECONDS);
        assertThat(launched.get().isAlive()).isFalse();
    }
    @Test void evidenceComesFromHelperMemoryAndMatchesFrozenHeadActualScreenshotAndChecksum() throws Exception {
        fixture(); ObjectNode proof = proof(); summary(proof);
        try (var bridge = start("summary", Duration.ofSeconds(10))) {
            Files.writeString(output.resolve("fixture-summary.json"), "{\"forged\":true}");
            var verified = bridge.verificationEvidence(HEAD, output);
            assertThat(verified.path("testedHead").asText()).isEqualTo(HEAD);
            assertThat(verified.path("proofs").size()).isEqualTo(1);
            assertThat(verified.path("proofs").get(0).path("screenshotSha256")).isEqualTo(proof.path("screenshotSha256"));
        }
    }
    @Test void failedMissingOrStaleProofCannotAuthorizeDelivery() throws Exception {
        fixture();
        for (String invalid : List.of("failed", "head", "checks-empty", "checks-false", "checks-string", "console")) {
            ObjectNode proof = proof();
            switch (invalid) {
                case "failed" -> proof.put("passed", false);
                case "head" -> proof.put("testedHead", "b".repeat(40));
                case "checks-empty" -> proof.set("checks", json.createArrayNode());
                case "checks-false" -> proof.set("checks", json.createArrayNode().add(json.createObjectNode().put("check", "visible").put("passed", false)));
                case "checks-string" -> proof.set("checks", json.createArrayNode().add(json.createObjectNode().put("check", "visible").put("passed", "true")));
                case "console" -> proof.put("consoleErrorCount", 1);
            }
            summary(proof);
            try (var bridge = start("summary", Duration.ofSeconds(10))) {
                assertThatThrownBy(() -> bridge.verificationEvidence(HEAD, output)).isInstanceOf(IllegalStateException.class);
            }
        }
        Files.writeString(output.resolve("fixture-summary.json"), "{\"testedHead\":\"" + HEAD + "\",\"proofs\":[]}");
        try (var bridge = start("summary", Duration.ofSeconds(10))) {
            assertThatThrownBy(() -> bridge.verificationEvidence(HEAD, output)).hasMessageContaining("缺少真实浏览器");
        }
    }
    @Test void screenshotOutsideOutputMissingOrTamperedFailsVerificationAndPreservesFiles() throws Exception {
        fixture();
        for (String invalid : List.of("outside", "missing", "tampered")) {
            ObjectNode proof = proof();
            if (invalid.equals("outside")) {
                Path outside = directory.resolve("outside.png"); Files.copy(output.resolve("browser-proof-1.png"), outside);
                proof.put("screenshot", outside.toString());
            } else if (invalid.equals("missing")) Files.delete(output.resolve("browser-proof-1.png"));
            else Files.writeString(output.resolve("browser-proof-1.png"), "tampered");
            summary(proof);
            try (var bridge = start("summary", Duration.ofSeconds(10))) {
                assertThatThrownBy(() -> bridge.verificationEvidence(HEAD, output)).hasMessageContaining("浏览器截图");
            }
        }
        assertThat(Files.readString(output.resolve("browser-proof-1.png"))).isEqualTo("tampered");
    }
    @Test void helperSummaryHeadMustAlsoMatchFrozenCommit() throws Exception {
        fixture(); summary(proof());
        ObjectNode wrong = (ObjectNode)json.readTree(Files.readString(output.resolve("fixture-summary.json")));
        wrong.put("testedHead", "b".repeat(40)); Files.writeString(output.resolve("fixture-summary.json"), wrong.toString());
        try (var bridge = start("summary", Duration.ofSeconds(10))) {
            assertThatThrownBy(() -> bridge.verificationEvidence(HEAD, output)).hasMessageContaining("冻结提交");
        }
    }

    /** 自包含普通 Java 子进程：测试真实 stdout/超时/进程树，不依赖 CI 安装 Node 或访问账号。 */
    public static class BridgeFixture {
        private static String executable() { return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(); }
        private static String location() throws Exception { return Path.of(BridgeFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
        public static void main(String[] args) throws Exception {
            System.out.println("noise token=do-not-persist");
            if (args[0].equals("exit")) return;
            if (args[0].equals("child")) {
                Process child = new ProcessBuilder(executable(), "-cp", location(), BridgeFixture.class.getName(), "idle", args[1])
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                Files.writeString(Path.of(args[1], "child.pid"), Long.toString(child.pid()));
            }
            if (args[0].equals("ready") || args[0].equals("child")) {
                System.out.println("x".repeat(100_000));
                System.out.println("ACCEPTANCE_BRIDGE_PORT=43210");
            }
            if (args[0].equals("invalid")) System.out.println("ACCEPTANCE_BRIDGE_PORT=99999");
            if (args[0].equals("summary")) {
                byte[] evidence = Files.readAllBytes(Path.of(args[1], "fixture-summary.json"));
                var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/summary", exchange -> {
                    exchange.sendResponseHeaders(200, evidence.length);
                    try (var stream = exchange.getResponseBody()) { stream.write(evidence); }
                });
                server.start(); System.out.println("ACCEPTANCE_BRIDGE_PORT=" + server.getAddress().getPort());
            }
            for (;;) Thread.sleep(1000);
        }
    }
}
