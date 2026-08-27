package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.SharedChatSyncRunEntity;
import cn.wayuwa.wao.dto.SharedChatSyncRunDto;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuChatMember;
import cn.wayuwa.wao.mapper.SharedChatSyncRunMapper;
import cn.wayuwa.wao.mapper.SharedChatSyncStateMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 共享聊天批量同步：按有界时间窗拉真实消息，并记录夜间/手动核对的存储增量。 */
@Service
public class SharedChatSynchronizationService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final SettingService settings;
    private final FeishuOpenApiClient feishu;
    private final SharedChatFeishuBridge bridge;
    private final SharedChatSyncRunMapper runs;
    private final SharedChatSyncStateMapper state;
    private final String leaseOwner = UUID.randomUUID().toString().replace("-", "");

    public SharedChatSynchronizationService(SettingService settings, FeishuOpenApiClient feishu,
            SharedChatFeishuBridge bridge, SharedChatSyncRunMapper runs, SharedChatSyncStateMapper state) {
        this.settings = settings;
        this.feishu = feishu;
        this.bridge = bridge;
        this.runs = runs;
        this.state = state;
    }

    /** 同步一个秒级窗口；实时轮询和编辑回看共用，调用方决定是否持久化运行记录。 */
    public BatchResult synchronize(long startEpochSeconds, long endEpochSeconds) {
        Target target = target();
        return synchronize(target, startEpochSeconds, endEpochSeconds);
    }

    private BatchResult synchronize(Target target, long startEpochSeconds, long endEpochSeconds) {
        String token = feishu.tenantAccessToken(target.appId(), target.secret());
        Map<String, String> names = new LinkedHashMap<>();
        for (FeishuChatMember member : feishu.listChatMembers(token, target.chatId())) {
            names.put(member.openId(), member.name());
        }
        int scanned = 0;
        int inserted = 0;
        int updated = 0;
        long bytes = 0;
        for (var message : feishu.listMessages(token, target.chatId(), startEpochSeconds, endEpochSeconds)) {
            scanned++;
            var result = bridge.importMessage(token, target.chatId(), target.chatName(), message, names);
            inserted += result.inserted();
            updated += result.updated();
            bytes += result.bytesAdded();
        }
        return new BatchResult(scanned, inserted, updated, bytes);
    }

    /** 手动或夜间核对某个上海自然日，并把成功或失败终态落库。 */
    public SharedChatSyncRunDto synchronizeDay(LocalDate day, String triggerType) {
        if (day == null) throw new IllegalArgumentException("同步日期不能为空");
        Target target = target();
        long start = day.atStartOfDay(SHANGHAI).toEpochSecond();
        long end = day.plusDays(1).atStartOfDay(SHANGHAI).toEpochSecond() - 1;
        state.prepare("daily", target.chatId(), end);
        LocalDateTime leaseStartedAt = LocalDateTime.now(SHANGHAI);
        if (state.claim("daily", leaseOwner, leaseStartedAt, leaseStartedAt.plusHours(1)) != 1) {
            throw new BadRequestException("已有聊天核对正在运行，请稍后查看结果");
        }
        SharedChatSyncRunEntity run;
        try {
            run = startRun(day, triggerType, target);
        } catch (RuntimeException exception) {
            state.release("daily", leaseOwner);
            throw exception;
        }
        try {
            BatchResult result = synchronize(target, start, end);
            run.setStatus("SUCCEEDED");
            run.setMessagesScanned(result.scanned());
            run.setMessagesInserted(result.inserted());
            run.setMessagesUpdated(result.updated());
            run.setContentBytesAdded(result.bytesAdded());
            run.setFinishedAt(LocalDateTime.now(SHANGHAI));
            runs.updateById(run);
            state.complete("daily", leaseOwner, end);
            return dto(run);
        } catch (RuntimeException exception) {
            state.release("daily", leaseOwner);
            run.setStatus("FAILED");
            run.setErrorMessage(bounded(exception.getMessage()));
            run.setFinishedAt(LocalDateTime.now(SHANGHAI));
            runs.updateById(run);
            throw exception;
        }
    }

    /** 最近的夜间与手动核对运行，按开始时间倒序。 */
    public List<SharedChatSyncRunDto> listRuns(int requestedLimit) {
        int limit = Math.max(1, Math.min(30, requestedLimit));
        return runs.selectList(new LambdaQueryWrapper<SharedChatSyncRunEntity>()
                        .orderByDesc(SharedChatSyncRunEntity::getStartedAt).last("LIMIT " + limit))
                .stream().map(SharedChatSynchronizationService::dto).toList();
    }

    private SharedChatSyncRunEntity startRun(LocalDate day, String triggerType, Target target) {
        SharedChatSyncRunEntity run = new SharedChatSyncRunEntity();
        run.setId(UUID.randomUUID().toString().replace("-", ""));
        run.setTriggerType("NIGHTLY".equals(triggerType) ? "NIGHTLY" : "MANUAL");
        run.setChatId(target.chatId());
        run.setChatName(target.chatName());
        run.setSyncDay(day);
        run.setStatus("RUNNING");
        run.setMessagesScanned(0);
        run.setMessagesInserted(0);
        run.setMessagesUpdated(0);
        run.setContentBytesAdded(0L);
        run.setStartedAt(LocalDateTime.now(SHANGHAI));
        runs.insert(run);
        return run;
    }

    private Target target() {
        String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
        String secret = settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
        String chatId = settings.get(SettingService.SHARED_CHAT_CHAT_ID, "");
        String chatName = settings.get(SettingService.SHARED_CHAT_CHAT_NAME, "飞书群聊");
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(secret)) {
            throw new BadRequestException("飞书自建应用尚未配置");
        }
        if (!StringUtils.hasText(chatId)) throw new BadRequestException("共享聊天尚未选择飞书群");
        return new Target(appId, secret, chatId, chatName);
    }

    private static SharedChatSyncRunDto dto(SharedChatSyncRunEntity run) {
        return new SharedChatSyncRunDto(run.getId(), run.getTriggerType(), run.getChatName(), run.getSyncDay(),
                run.getStatus(), value(run.getMessagesScanned()), value(run.getMessagesInserted()),
                value(run.getMessagesUpdated()), run.getContentBytesAdded() == null ? 0 : run.getContentBytesAdded(),
                run.getErrorMessage(), run.getStartedAt(), run.getFinishedAt());
    }

    private static int value(Integer value) { return value == null ? 0 : value; }
    private static String bounded(String value) {
        if (!StringUtils.hasText(value)) return "同步失败，飞书未返回可展示原因";
        String normalized = value.strip();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    public record BatchResult(int scanned, int inserted, int updated, long bytesAdded) { }
    private record Target(String appId, String secret, String chatId, String chatName) { }
}
