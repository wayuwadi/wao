package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.AiAssistantRunEntity;
import cn.wayuwa.wao.mapper.AiAssistantRunMapper;
import cn.wayuwa.wao.mapper.NoteAiWorkflowMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PreDestroy;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** 小记、算卦和业务助手共用的本机执行出口，不依赖 Runner。默认关闭，按主机显式启用。 */
@Service
public class LocalNoteAiService {
    @Autowired(required = false)
    private ObjectProvider<TianrenVisualService> tianrenVisuals;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private org.springframework.beans.factory.ObjectProvider<NoteAiManagedService> managedNotes;
    private static final Logger log = LoggerFactory.getLogger(LocalNoteAiService.class);
    private final AiAssistantRunMapper runs;
    private final ImageService images;
    private final NoteAiWorkflowMapper workflows;
    private final ObjectProvider<NoteAiWorkflowService> workflowService;
    private final Environment environment;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wao-local-note-ai"); t.setDaemon(true); return t;
    });
    private volatile boolean ready;
    private String unavailable = "本机小记 AI 尚未启用，请在服务主机启用已登录的 Codex";
    private Path directory;
    private String capability;
    private FileChannel lockChannel;
    private FileLock lock;

    @Autowired
    private LocalCodexConversationCompletion conversationCompletion;

    @Autowired
    private ReferenceImageService referenceImages;

    @Autowired(required = false)
    private NoteAiSettingsService noteSettings;

    @Autowired(required = false)
    private LocalCodexModelService localModels;

    @Autowired
    private ObjectProvider<NoteDiscoveryService> discoveries;

    public LocalNoteAiService(AiAssistantRunMapper runs, ImageService images, NoteAiWorkflowMapper workflows,
            ObjectProvider<NoteAiWorkflowService> workflowService, Environment environment) {
        this.runs = runs; this.images = images; this.workflows = workflows;
        this.workflowService = workflowService; this.environment = environment;
    }

    /** 只在管理员显式配置本机执行时替代小记的 API 路径。 */
    public boolean enabled() { return environment.getProperty("wao.ai.local-notes.enabled", Boolean.class, false); }

    /** 真实进程目录锁已建立后才报告可用，不以配置布尔值冒充启动成功。 */
    public boolean ready() { return ready; }

    /** 启动后抢占本机目录锁，回收本机中断任务；不碰其他主机、API 或旧 Runner 队列。 */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled()) return;
        try {
            directory = Path.of(environment.getProperty("wao.ai.local-notes.directory",
                    Path.of(System.getProperty("user.home"), ".wao", "local-notes").toString())).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            directory = directory.toRealPath();
            lockChannel = FileChannel.open(directory.resolve("executor.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            if (lock == null) throw new IllegalStateException("目录已被占用");
            capability = instanceCapability(directory);
            runs.recoverLocalNoteRuns(capability);
            ready = true;
            worker.scheduleWithFixedDelay(this::tick, 1, 2, TimeUnit.SECONDS);
        } catch (Exception exception) {
            unavailable = "本机小记 AI 启动失败，请检查执行目录、重复服务和数据库连接后重启";
            log.warn("本机小记 AI 未启动：{}", exception.getClass().getSimpleName());
        }
    }

    static String instanceCapability(Path directory) throws Exception {
        Path identity = directory.resolve("instance-id");
        if (!Files.exists(identity)) Files.writeString(identity, UUID.randomUUID().toString(), StandardOpenOption.CREATE_NEW);
        String id = UUID.fromString(Files.readString(identity).trim()).toString().replace("-", "");
        return "local-notes-" + id.substring(0, 20);
    }

    /** 未准备好的本机服务立即给出恢复提示，不回退到额外付费 API。 */
    public void requireAvailable() { if (!ready) throw new BadRequestException(unavailable); }

    /** 返回本主机稳定队列标识；不对浏览器开放节点注册或领取能力。 */
    public String capabilityId() { requireAvailable(); return capability; }

    /** 使用已有持久队列保存小记输入，模型调用由本机 worker 完成。 */
    public String enqueue(String memberId, String prompt, String purpose, String model) {
        requireCapacity();
        return enqueueRun(memberId, prompt, purpose, model, reasoningEffort(memberId, model));
    }

    /** 其他私有功能使用自己的模型与深度快照，不继承小记偏好。 */
    public String enqueueWithEffort(String memberId, String prompt, String purpose, String model, String effort) {
        requireCapacity();
        String selected = localModels == null ? model : localModels.validate(model);
        String depth = effort == null ? "medium" : effort;
        if (localModels != null) depth = localModels.validateEffort(selected, depth);
        return enqueueRun(memberId, prompt, purpose, selected, depth);
    }

    private String enqueueRun(String memberId, String prompt, String purpose, String model, String effort) {
        AiAssistantRunEntity run = new AiAssistantRunEntity();
        run.setId("ac" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        run.setMemberId(memberId); run.setAiCapabilityId(capability); run.setUsagePurpose(purpose);
        run.setRequestedModel(model); run.setReasoningEffort(effort);
        run.setPromptText(prompt); run.setWebSearchEnabled(false); run.setStatus("secure_queued");
        runs.insertReadOnlyQueued(run);
        return run.getId();
    }

    /** 入队时读取成员设置并校验模型支持的档位，固定到本次任务。 */
    public String reasoningEffort(String memberId, String model) {
        String effort = noteSettings == null ? "medium" : noteSettings.get(memberId).settings().reasoningEffort();
        if (effort == null || effort.isBlank()) effort = "medium";
        return localModels == null ? effort : localModels.validateEffort(model, effort);
    }

    /** 本机业务共用有界队列；会话型业务自行在事务中保存成员消息。 */
    public void requireCapacity() {
        requireAvailable();
        if (runs.selectCount(new LambdaQueryWrapper<AiAssistantRunEntity>()
                .eq(AiAssistantRunEntity::getAiCapabilityId, capability)
                .in(AiAssistantRunEntity::getStatus, "secure_queued", "provider_running")) >= 100)
            throw new BadRequestException("本机 AI 队列已满；原文已保留，请稍后重试");
    }

    private void tick() {
        if (!ready) return;
        try {
            advanceWorkflows();
            if (discoveries != null) discoveries.getObject().tick();
            runs.expireLocalNoteQueue(capability);
            if (tianrenVisuals != null) tianrenVisuals.getObject().tick(capability);
            for (var run : runs.selectLocalNoteQueued(capability)) {
                if (Thread.currentThread().isInterrupted()) return;
                if (process(run)) break;
            }
        } catch (Exception exception) {
            // 不输出原始 CLI 输出、提示词、私有正文或数据库连接信息。
            log.warn("本机小记 AI 推进暂时失败：{}", exception.getClass().getSimpleName());
        }
    }

    private void advanceWorkflows() {
        String scope = environment.getProperty("wao.ai.local-notes.document-ids", "").trim();
        List<String> ids = scope.isEmpty() ? workflows.pendingDocIds(30)
                : Arrays.stream(scope.split(",")).map(String::trim).filter(id -> !id.isEmpty()).toList();
        for (String id : ids) {
            if (Thread.currentThread().isInterrupted()) return;
            try { workflowService.getObject().advance(id); }
            catch (RuntimeException exception) {
                log.warn("本机小记工作流推进失败：{}", exception.getClass().getSimpleName());
                try { workflowService.getObject().failAdvance(id, exception); }
                catch (RuntimeException saveError) {
                    log.warn("本机小记失败状态保存暂不可用：{}", saveError.getClass().getSimpleName());
                }
            }
        }
    }

    boolean process(AiAssistantRunEntity run) {
        String claim = UUID.randomUUID().toString().replace("-", "");
        if (!capability.equals(run.getAiCapabilityId())
                || runs.claimQueued(run.getId(), null, claim, "secure_queued", "provider_running") != 1) return false;
        String orphanImageId = null;
        long started = System.nanoTime();
        LocalNoteCodexProcess.Result result = null;
        String discoveryError = null;
        try {
            if (discoveries != null && !discoveries.getObject().beforeExecution(run)) {
                runs.completeProviderFailed(run.getId(), claim, "发现已暂停、材料更新或预算延后");
                return true;
            }
        if(managedNotes != null && !managedNotes.getObject().mayStart(run.getId())) {
            runs.completeProviderFailed(run.getId(),claim,"自动阶段已关闭或来源更新；未执行模型，原内容保留");
            return true;
        }
            String model = run.getRequestedModel() == null ? "gpt-6-astra" : run.getRequestedModel();
            long seconds = environment.getProperty("wao.ai.local-notes.timeout-seconds", Long.class, 300L);
            if (seconds < 1 || seconds > 900) throw new IllegalStateException("本机 AI 超时配置必须为 1–900 秒");
            result = execute(run, model, Duration.ofSeconds(seconds));
            if (TianrenVisualService.IMAGE_PURPOSE.equals(run.getUsagePurpose())) {
                if (tianrenVisuals == null || result.png() == null) throw new IllegalStateException("本机未返回真实卦象配图");
                tianrenVisuals.getObject().completeImage(run, claim, result.png());
                return true;
            }
            String response = result.text();
            String storedImageId = null;
            if (result.png() != null) {
                var stored = images.store(result.png(), "image/png", "文章总览图.png", "文章总览图 · " + run.getId());
                storedImageId = stored.id(); response = stored.url();
            }
            // 只有明确返回 CAS 失败才清理孤儿；数据库提交结果未知时不能删除可能已被引用的图片。
            int completed = run.getConversationId() == null
                    ? runs.completeProviderSucceeded(run.getId(), claim, response)
                    : conversationCompletion.complete(run, claim, response);
            if (completed == 0) orphanImageId = storedImageId;
        } catch (Exception exception) {
            discoveryError = "CALL_FAILED";
            log.warn("本机小记 AI 执行失败：{}", exception.getClass().getSimpleName());
            String error = exception instanceof IllegalStateException ? exception.getMessage()
                    : "本机 AI 执行中断或结果无效；原文已保留，请检查本机 Codex 后点击重试";
            runs.completeProviderFailed(run.getId(), claim, error);
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            if (discoveries != null) {
                try { discoveries.getObject().executionFinished(run, result,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), discoveryError); }
                catch (RuntimeException error) { log.warn("发现调用状态保存失败：{}", error.getClass().getSimpleName()); }
            }
            if (orphanImageId != null) images.delete(orphanImageId);
        }
        return true;
    }

    LocalNoteCodexProcess.Result execute(AiAssistantRunEntity run, String model, Duration timeout) throws Exception {
        List<ReferenceImageService.Content> references = List.of();
        if (run.getReferenceImageIds() != null && !run.getReferenceImageIds().isBlank()) {
            var ids = List.of(run.getReferenceImageIds().split(","));
            if (ids.size() > 4 || ids.size() != ids.stream().distinct().count())
                throw new IllegalStateException("参考图片数量无效；请重新选择后重试");
            referenceImages.requireOwned(run.getMemberId(), ids);
            references = ids.stream().map(id -> referenceImages.raw(run.getMemberId(), id)).toList();
        }
        String effort = run.getReasoningEffort();
        String purpose = run.getUsagePurpose();
        if (effort == null && ("POLISH".equals(purpose) || "BACKGROUND".equals(purpose)
                || "ARTICLE_IMAGE".equals(purpose) || NoteDiscoveryService.GENERATE.equals(purpose)
                || NoteDiscoveryService.REVIEW.equals(purpose)))
            effort = "medium";
        return LocalNoteCodexProcess.execute(environment.getProperty("wao.ai.local-notes.executable", "codex"),
                model, effort,
                run.getPromptText(), directory, timeout,
                AiAssistantService.ARTICLE_IMAGE_PURPOSE.equals(run.getUsagePurpose())
                        || TianrenVisualService.IMAGE_PURPOSE.equals(run.getUsagePurpose()), references);
    }

    /** 仅停止本实例创建的进程和 worker，保留未领取的持久队列。 */
    @PreDestroy
    public void stop() throws Exception {
        ready = false; worker.shutdownNow();
        worker.awaitTermination(10, TimeUnit.SECONDS);
        if (lock != null && lock.isValid()) lock.release();
        if (lockChannel != null) lockChannel.close();
    }
}
