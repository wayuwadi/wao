package cn.wayuwa.wao.service;

import java.util.ArrayList;
import java.util.List;

import cn.wayuwa.wao.domain.AccountEntity;
import cn.wayuwa.wao.domain.AccountIdentityEntity;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 任务相关的飞书群通知：AI 跑完了、任务开始收集创意了，都在群里说一声并附跳转按钮。
 *
 * <p>为什么要有这层：验收和补充创意都是「必须有人立刻看到」的动作，等人主动打开网站发现
 * 就晚了。通知卡片上的按钮直接落到那条任务，手机上点一下就能验收或补充。
 *
 * <p>@ 谁由「设置 → 飞书」里勾的协作成员决定，并且永远把操作人自己排除掉 —— 自己刚做完的事
 * 不需要再 @ 自己一遍。一个人都没勾时就只发通知不 @ 人（对应需求里的「或者是不艾特」）。
 */
@Service
public class FeishuTaskNotifier {

    /** 线上站点地址；卡片按钮要跳的是公网地址，不是容器内部地址。 */
    private static final String SITE = "https://wao.wayuwa.cn";

    private final FeishuWebhookNotifier notifier;
    private final SettingService settings;
    private final AccountMapper accounts;
    private final AccountIdentityMapper identities;

    public FeishuTaskNotifier(FeishuWebhookNotifier notifier, SettingService settings,
            AccountMapper accounts, AccountIdentityMapper identities) {
        this.notifier = notifier;
        this.settings = settings;
        this.accounts = accounts;
        this.identities = identities;
    }

    /** 任务详情页地址（卡片按钮和分享给同伴的链接都用它）。 */
    public static String taskUrl(String projectId, String taskId) {
        return SITE + "/projects/" + projectId + "?task=" + taskId;
    }

    /**
     * AI 跑完一次任务后在群里说一声。
     *
     * @param taskId      任务 id
     * @param projectId   项目 id
     * @param taskTitle   任务标题
     * @param projectName 项目名称
     * @param engineLabel 执行工具名（Codex / Claude Code）
     * @param scope       本次跑的范围描述（整个任务或某条子任务）
     * @param succeeded   是否成功
     * @param statusUnreported AI 结束了会话但没报告任务状态，成败需要人判断；这时不能笼统说「失败」
     * @param summary     结果摘要，会截断后放进卡片正文
     * @return 是否真的发出去了
     */
    public boolean notifyRunFinished(String taskId, String projectId, String taskTitle, String projectName,
            String engineLabel, String scope, boolean succeeded, boolean statusUnreported, String summary) {
        List<List<String>> fields = new ArrayList<>();
        fields.add(List.of("项目", blank(projectName, projectId)));
        fields.add(List.of("任务", blank(taskTitle, taskId)));
        fields.add(List.of("执行工具", blank(engineLabel, "AI")));
        if (StringUtils.hasText(scope)) {
            fields.add(List.of("范围", scope));
        }
        if (succeeded) {
            return notifier.notifyCard(new FeishuWebhookNotifier.Card(
                    "AI 已跑完，等你验收", "green", fields,
                    excerpt(summary, "AI 已完成执行，任务已转入「待验证」。"),
                    "去验收任务", taskUrl(projectId, taskId)));
        }
        if (statusUnreported) {
            return notifier.notifyCard(new FeishuWebhookNotifier.Card(
                    "AI 已结束，但没说清成没成", "orange", fields,
                    excerpt(summary, "AI 结束了会话但没报告任务状态，请根据它的原始答复确认结果。"),
                    "去确认结果", taskUrl(projectId, taskId)));
        }
        return notifier.notifyCard(new FeishuWebhookNotifier.Card(
                "AI 执行未完成", "red", fields,
                excerpt(summary, "请打开任务查看失败原因。"),
                "查看失败原因", taskUrl(projectId, taskId)));
    }

    /**
     * 任务进入「收集中」时把它分享到群里，请同伴一起补充。
     *
     * @param taskId          任务 id
     * @param projectId       项目 id
     * @param taskTitle       任务标题
     * @param projectName     项目名称
     * @param desc            当前描述，作为卡片正文的引子
     * @param operatorOpenId  操作人的飞书 open_id；用于把自己从 @ 名单里排掉，可为空
     * @return 是否真的发出去了
     */
    public boolean notifyCollecting(String taskId, String projectId, String taskTitle, String projectName,
            String desc, String operatorOpenId) {
        List<String> mentions = mentionTargets(operatorOpenId);
        StringBuilder body = new StringBuilder();
        if (!mentions.isEmpty()) {
            for (String openId : mentions) {
                body.append("<at id=").append(openId).append("></at> ");
            }
            body.append("这条任务正在收集创意，进来一起补充吧。\n\n");
        }
        body.append(excerpt(desc, "还没有描述，点开直接写。"));
        return notifier.notifyCard(new FeishuWebhookNotifier.Card(
                "任务开始收集创意",
                "orange",
                List.of(
                        List.of("项目", blank(projectName, projectId)),
                        List.of("任务", blank(taskTitle, taskId))),
                body.toString(),
                "进来一起补充",
                taskUrl(projectId, taskId)));
    }

    /** 该 @ 的人：配置里勾的协作成员减去操作人自己。 */
    private List<String> mentionTargets(String operatorOpenId) {
        List<String> out = new ArrayList<>();
        for (String raw : settings.get(SettingService.QUICK_COLLECT_MENTIONS, "").split(",")) {
            String openId = raw.trim();
            if (StringUtils.hasText(openId) && !openId.equals(operatorOpenId) && !out.contains(openId)) {
                out.add(openId);
            }
        }
        return out;
    }

    /**
     * 查一个成员绑定的飞书 open_id。
     *
     * @param memberId 成员 id
     * @return open_id；没绑过飞书时返回 null
     */
    public String feishuOpenId(String memberId) {
        if (!StringUtils.hasText(memberId)) {
            return null;
        }
        AccountEntity account = accounts.findByMemberId(memberId);
        if (account == null) {
            return null;
        }
        AccountIdentityEntity identity = identities.findByAccountProvider(account.getId(), "feishu");
        return identity == null ? null : identity.getProviderSubject();
    }

    /** 摘要正文：卡片放不下长文，超过 600 字截断并提示点按钮看全文。 */
    private static String excerpt(String text, String fallback) {
        if (!StringUtils.hasText(text)) {
            return fallback;
        }
        String trimmed = text.trim();
        return trimmed.length() <= 600 ? trimmed : trimmed.substring(0, 600) + "……（点按钮看全文）";
    }

    private static String blank(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}
