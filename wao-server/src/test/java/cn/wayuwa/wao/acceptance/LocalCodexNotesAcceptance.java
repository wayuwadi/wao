package cn.wayuwa.wao.acceptance;

import cn.wayuwa.wao.WaoServerApplication;
import cn.wayuwa.wao.domain.AiAssistantRunEntity;
import cn.wayuwa.wao.mapper.*;
import cn.wayuwa.wao.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 只在 test classpath 启动的真实浏览器验收入口；不打包进生产应用。 */
public final class LocalCodexNotesAcceptance {
    private static final ThreadLocal<Boolean> IN_SCOPE = ThreadLocal.withInitial(() -> false);
    private static final String CAPABILITY = "acceptance-local-codex";

    /** 启动独立回环服务，沿用 local-cloud 连接；配置文件只保存验收标识。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("参数：验收配置 JSON 的绝对路径");
        Path configPath = Path.of(args[0]).toAbsolutePath();
        Config config = new ObjectMapper().readValue(Files.readString(configPath), Config.class);
        config.validate();
        Files.createDirectories(Path.of(config.evidenceDir()));
        var lockChannel = java.nio.channels.FileChannel.open(Path.of(config.evidenceDir(), "acceptance.lock"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
        var lock = lockChannel.tryLock();
        if (lock == null) { lockChannel.close(); throw new IllegalStateException("同一验收目录已有运行进程"); }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { lock.release(); lockChannel.close(); } catch (Exception ignored) { }
        }));
        SpringApplication app = new SpringApplication(WaoServerApplication.class, AcceptanceConfiguration.class);
        app.setAdditionalProfiles("local-cloud");
        app.setDefaultProperties(Map.of("server.port", config.port(), "server.address", "127.0.0.1"));
        var context = app.run("--wao.scheduling.enabled=false", "--spring.flyway.enabled=false",
                "--server.address=127.0.0.1", "--server.port=" + config.port());
        ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
        Runtime.getRuntime().addShutdownHook(new Thread(worker::shutdownNow));
        worker.scheduleWithFixedDelay(() -> tick(context, configPath, config), 2, 2, TimeUnit.SECONDS);
    }

    record Config(int port, String memberId, String titlePrefix, String codex, String evidenceDir,
                  List<String> docIds, String mode, int timeoutSeconds, int delaySeconds) {
        void validate() {
            if (port < 1024 || port > 65535 || memberId == null || memberId.isBlank()
                    || titlePrefix == null || !titlePrefix.startsWith("WAO-CODEX-ACCEPTANCE-")
                    || titlePrefix.length() < 30 || codex == null || !Path.of(codex).isAbsolute()
                    || evidenceDir == null || !Path.of(evidenceDir).isAbsolute()
                    || docIds == null || timeoutSeconds < 1 || timeoutSeconds > 300
                    || delaySeconds < 0 || delaySeconds > 120
                    || !List.of("normal", "fail", "timeout", "hold", "recover").contains(mode))
                throw new IllegalArgumentException("验收配置无效，必须限定成员、专用标题前缀、文档 ID 和本机 CLI");
        }
    }

    private static void tick(ApplicationContext context, Path configPath, Config initial) {
        try {
            Config config = new ObjectMapper().readValue(Files.readString(configPath), Config.class);
            config.validate();
            if (!config.memberId().equals(initial.memberId()) || !config.titlePrefix().equals(initial.titlePrefix())
                    || !config.evidenceDir().equals(initial.evidenceDir()) || !config.codex().equals(initial.codex()))
                throw new IllegalArgumentException("运行中不能改变成员、前缀、CLI 或证据目录");
            if ("hold".equals(config.mode())) return;
            for (String id : config.docIds()) {
                var doc = context.getBean(DocMapper.class).selectById(id);
                if (doc == null || doc.getDeletedAt() != null || !config.memberId().equals(doc.getAuthorId())
                        || doc.getTitle() == null || !doc.getTitle().startsWith(config.titlePrefix())) continue;
                var flowMapper = context.getBean(NoteAiWorkflowMapper.class);
                var flow = flowMapper.listOwned(config.memberId()).stream().filter(w -> id.equals(w.docId())).findFirst().orElse(null);
                if (flow == null) continue;
                if (!"SKIPPED".equals(flow.titleStatus()) || !"SKIPPED".equals(flow.imageStatus()))
                    throw new IllegalArgumentException("当前验收只允许正文润色，标题与配图应关闭");
                var workflows = context.getBean(NoteAiWorkflowService.class);
                IN_SCOPE.set(true);
                try { workflows.advance(id); }
                catch (RuntimeException e) { workflows.failAdvance(id, e); }
                finally { IN_SCOPE.remove(); }
                flow = flowMapper.listOwned(config.memberId()).stream().filter(w -> id.equals(w.docId())).findFirst().orElseThrow();
                if (flow.polishJobId() == null) continue;
                var job = context.getBean(TextPolishJobMapper.class).findOwned(flow.polishJobId(), config.memberId());
                var mapper = context.getBean(AiAssistantRunMapper.class);
                var run = mapper.selectById(job.aiRunId());
                if (run == null || !CAPABILITY.equals(run.getAiCapabilityId())) continue;
                if ("provider_running".equals(run.getStatus())) {
                    // 仅由操作人确认旧验收进程停止后执行 recover；不抢占其他存活进程。
                    if ("recover".equals(config.mode())) {
                        mapper.completeProviderFailed(run.getId(), run.getClaimToken(),
                                "本机验收执行已中断，请重试；原文已保留");
                        evidence(config, id, run.getId(), "interrupted", 0);
                    }
                    continue;
                }
                if ("recover".equals(config.mode())) continue;
                if (!"secure_queued".equals(run.getStatus())) continue;
                String claim = UUID.randomUUID().toString();
                if (mapper.claimQueued(run.getId(), null, claim, "secure_queued", "provider_running") != 1) continue;
                long started = System.nanoTime();
                evidence(config, id, run.getId(), "running", 0);
                try {
                    if (config.delaySeconds() > 0) Thread.sleep(config.delaySeconds() * 1000L);
                    if ("fail".equals(config.mode())) throw new IllegalStateException("隔离验收：模拟执行出口失败，请重试；原文已保留");
                    String result = LocalCodexProcess.execute(config.codex(), run.getPromptText(),
                            Path.of(config.evidenceDir(), run.getId()),
                            "timeout".equals(config.mode()) ? Duration.ofMillis(1) : Duration.ofSeconds(config.timeoutSeconds()));
                    int changed = mapper.completeProviderSucceeded(run.getId(), claim, result);
                    evidence(config, id, run.getId(), changed == 1 ? "succeeded" : "discarded", elapsed(started));
                } catch (Exception e) {
                    mapper.completeProviderFailed(run.getId(), claim, e.getMessage());
                    evidence(config, id, run.getId(), "failed", elapsed(started));
                }
            }
        } catch (Exception e) {
            // 不输出配置或正文；完整 CLI 日志只写私有验收目录。
            System.err.println("本机小记验收未推进：" + e.getClass().getSimpleName());
        }
    }

    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }

    private static synchronized void evidence(Config config, String docId, String runId, String status, long millis) throws Exception {
        Path directory = Path.of(config.evidenceDir());
        Files.createDirectories(directory);
        String json = new ObjectMapper().writeValueAsString(Map.of("time", java.time.Instant.now().toString(),
                "docId", docId, "runId", runId, "status", status, "durationMs", millis,
                "model", "gpt-6-astra", "mode", config.mode()));
        Files.writeString(directory.resolve("runs.jsonl"), json + "\n", java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    @TestConfiguration
    static class AcceptanceConfiguration {
        @Bean @Primary
        LocalAssistant localAcceptanceAssistant(ApplicationContext c) { return new LocalAssistant(c); }
    }

    static class LocalAssistant extends AiAssistantService {
        private final AiAssistantRunMapper runs;
        LocalAssistant(ApplicationContext c) {
            super(c.getBean(AiAssistantRunMapper.class), c.getBean(AiRunnerNodeMapper.class), c.getBean(AiRunService.class),
                    c.getBean(AiAccountService.class), c.getBean(DeepSeekApiClient.class), c.getBean(AiAssistantConversationService.class),
                    c.getBean(AiAssistantAssetService.class), c.getBean(RunnerScriptService.class), c.getBean(IdeaResearchService.class),
                    c.getBean(ReferenceImageService.class), Duration.ofMinutes(60), Duration.ofMinutes(30));
            runs = c.getBean(AiAssistantRunMapper.class);
        }

        @Override protected AiAssistantRunEntity enqueueReadOnlyJob(String member, String prompt, String activeMessage,
                boolean durable, String capability, DeepSeekPurpose purpose, String model, boolean concurrent) {
            if (!IN_SCOPE.get() || purpose != DeepSeekPurpose.POLISH)
                return super.enqueueReadOnlyJob(member, prompt, activeMessage, durable, capability, purpose, model, concurrent);
            var run = new AiAssistantRunEntity();
            run.setId("ac" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            run.setMemberId(member); run.setPromptText(prompt); run.setAiCapabilityId(CAPABILITY);
            run.setRequestedModel("gpt-6-astra"); run.setUsagePurpose("POLISH");
            run.setWebSearchEnabled(false); run.setStatus("secure_queued"); runs.insertReadOnlyQueued(run);
            return run;
        }

        /** 本机验收不运行共享队列的全局超时扫描。 */
        @Override public int expireReadOnlyJobs() { return 0; }

        @Override public String polishQueueMessage() { return "本机 Codex 验收排队中"; }
    }
}
