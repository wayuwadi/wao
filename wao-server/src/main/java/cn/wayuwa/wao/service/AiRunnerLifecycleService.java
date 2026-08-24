package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.util.List;

import cn.wayuwa.wao.domain.AiRunnerNodeEntity;
import cn.wayuwa.wao.dto.AiRunnerDto;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.AiRunnerNodeMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Service;

/** 追踪 Runner 在线状态边沿；自动离线和恢复不发送群通知。 */
@Service
public class AiRunnerLifecycleService {

    static final int OFFLINE_AFTER_MINUTES = 2;
    private static final String SITE = "https://wao.wayuwa.cn/";
    private final AiRunnerNodeMapper runnerMapper;
    private final FeishuWebhookNotifier notifier;

    public AiRunnerLifecycleService(AiRunnerNodeMapper runnerMapper, FeishuWebhookNotifier notifier) {
        this.runnerMapper = runnerMapper;
        this.notifier = notifier;
    }

    /** 心跳恢复时原子地把离线状态切为在线，不发送群通知。 */
    public void markOnline(String runnerId) {
        AiRunnerNodeEntity runner = runnerMapper.selectById(runnerId);
        if (runner == null || !Boolean.FALSE.equals(runner.getOnlineState())) return;
        // VPN、休眠和日常断网都会造成正常的心跳恢复，只更新页面状态，不打扰群聊。
        runnerMapper.update(null, new UpdateWrapper<AiRunnerNodeEntity>()
                .eq("id", runnerId)
                .eq("online_state", false)
                .set("online_state", true));
    }

    /** 扫描超过两分钟没有心跳的在线节点，原子切换状态但不发送群通知。 */
    public int detectOffline(LocalDateTime now) {
        LocalDateTime cutoff = now.minusMinutes(OFFLINE_AFTER_MINUTES);
        List<AiRunnerNodeEntity> stale = runnerMapper.selectList(new QueryWrapper<AiRunnerNodeEntity>()
                .eq("online_state", true)
                .lt("last_seen_at", cutoff));
        int changed = 0;
        for (AiRunnerNodeEntity runner : stale) {
            int updated = runnerMapper.update(null, new UpdateWrapper<AiRunnerNodeEntity>()
                    .eq("id", runner.getId())
                    .eq("online_state", true)
                    .lt("last_seen_at", cutoff)
                    .set("online_state", false));
            if (updated == 1) {
                changed++;
            }
        }
        return changed;
    }

    /**
     * 管理员在后台解除一台电脑的接入后通知一次。
     *
     * <p>解除是人主动做的破坏性动作：节点记录连同令牌一起没了，那台电脑上的 Runner 会
     * 自行退出，所以不会再有离线通知补上这一条。
     *
     * @param runner 解除前的展示信息
     */
    public void notifyRemoved(AiRunnerDto runner) {
        notifier.notifyCard(new FeishuWebhookNotifier.Card(
                "AI 执行电脑已解除接入",
                "grey",
                List.of(
                        List.of("电脑", runner.name()),
                        List.of("AI 工具", runner.engines().isEmpty() ? "未上报" : String.join("、", runner.engines())),
                        List.of("解除时间", LocalDateTime.now().toString().replace('T', ' '))),
                "该电脑的节点令牌已作废，它上面的 Runner 会自动退出；需要重新接入请生成新的配对码。",
                "查看执行电脑", SITE));
    }

}
