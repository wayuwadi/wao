package cn.wayuwa.wao.service;

import java.util.ArrayList;
import java.util.List;

import cn.wayuwa.wao.domain.AccountEntity;
import cn.wayuwa.wao.domain.AccountIdentityEntity;
import cn.wayuwa.wao.feishu.FeishuAppLinks;
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
 *
 * <p>这里每一条通知都属于某个项目，发之前先过 {@link ProjectNotifyGate}：关掉通知的项目
 * 一律不打扰，判断集中在入口，调用方（任务、AI 执行、项目创建）不必各自记得判一次。
 */
@Service
public class FeishuTaskNotifier {

    private final FeishuWebhookNotifier notifier;
    private final FeishuAppLinks links;
    private final SettingService settings;
    private final AccountMapper accounts;
    private final AccountIdentityMapper identities;
    private final ProjectNotifyGate notifyGate;

    public FeishuTaskNotifier(FeishuWebhookNotifier notifier, FeishuAppLinks links, SettingService settings,
            AccountMapper accounts, AccountIdentityMapper identities, ProjectNotifyGate notifyGate) {
        this.notifier = notifier;
        this.links = links;
        this.settings = settings;
        this.accounts = accounts;
        this.identities = identities;
        this.notifyGate = notifyGate;
    }

    /** 任务详情页地址：配置了飞书应用时是 AppLink（飞书内直接打开），否则是普通网页地址。 */
    public String taskUrl(String projectId, String taskId) {
        return links.taskUrl(projectId, taskId);
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
        if (muted(projectId)) {
            return false;
        }
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
        if (muted(projectId)) {
            return false;
        }
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
     * 新建任务后在群里说一声，谁建的、落在哪个项目一目了然。
     *
     * @param taskId      任务 id
     * @param projectId   项目 id
     * @param taskTitle   任务标题
     * @param projectName 项目名称
     * @param creatorName 创建人名字；推导不出来时可为空
     * @param desc        任务描述，截断后作为卡片正文
     * @return 是否真的发出去了
     */
    public boolean notifyTaskCreated(String taskId, String projectId, String taskTitle, String projectName,
            String creatorName, String desc) {
        if (muted(projectId)) {
            return false;
        }
        List<List<String>> fields = new ArrayList<>();
        fields.add(List.of("项目", blank(projectName, projectId)));
        fields.add(List.of("任务", blank(taskTitle, taskId)));
        if (StringUtils.hasText(creatorName)) {
            fields.add(List.of("创建人", creatorName));
        }
        return notifier.notifyCard(new FeishuWebhookNotifier.Card(
                "新建了任务", "blue", fields,
                excerpt(desc, "还没有描述，点开看详情。"),
                "查看任务", taskUrl(projectId, taskId)));
    }

    /**
     * 任务状态变化后在群里说一声（进入「收集中」有单独的创意收集卡片，调用方自行跳过）。
     *
     * @param taskId       任务 id
     * @param projectId    项目 id
     * @param taskTitle    任务标题
     * @param projectName  项目名称
     * @param fromStatus   原状态；新建时可为空
     * @param toStatus     新状态
     * @param operatorName 操作人名字；推导不出来时可为空
     * @return 是否真的发出去了
     */
    public boolean notifyStatusChanged(String taskId, String projectId, String taskTitle, String projectName,
            String fromStatus, String toStatus, String operatorName) {
        if (muted(projectId)) {
            return false;
        }
        List<List<String>> fields = new ArrayList<>();
        fields.add(List.of("项目", blank(projectName, projectId)));
        fields.add(List.of("任务", blank(taskTitle, taskId)));
        if (StringUtils.hasText(operatorName)) {
            fields.add(List.of("操作人", operatorName));
        }
        String body = StringUtils.hasText(fromStatus)
                ? "状态由「" + fromStatus + "」变为「" + toStatus + "」。"
                : "状态更新为「" + toStatus + "」。";
        return notifier.notifyCard(new FeishuWebhookNotifier.Card(
                "已完成".equals(toStatus) ? "任务已完成" : "任务状态更新",
                "已完成".equals(toStatus) ? "green" : "blue",
                fields, body, "查看任务", taskUrl(projectId, taskId)));
    }

    /**
     * 任务下新增了子任务后在群里说一声，多条一次合并成一张卡片。
     *
     * <p>卡片必须回答两个问题：**谁**加的、加在**谁的**任务下。原来两个都答不上来 ——
     * 标题只写「新增了 N 条子任务」，看的人只知道自己那条任务多了点东西，不知道是同伴动的手；
     * 而且没 @ 到具体的人，等于放在群里自生自灭。现在标题带上操作人，字段里标出任务创建人，
     * 正文开头 @ 该知道这件事的人。
     *
     * @param taskId         任务 id
     * @param projectId      项目 id
     * @param taskTitle      任务标题
     * @param projectName    项目名称
     * @param subTitles      本次新增的子任务标题列表
     * @param operatorName   添加子任务的人；推导不出来时可为空
     * @param creatorName    主任务创建人；历史任务没有记录时可为空
     * @param mentionOpenIds 要 @ 的飞书 open_id（已排掉操作人自己），可为空
     * @return 是否真的发出去了
     */
    public boolean notifySubtasksAdded(String taskId, String projectId, String taskTitle, String projectName,
            List<String> subTitles, String operatorName, String creatorName, List<String> mentionOpenIds) {
        if (muted(projectId)) {
            return false;
        }
        if (subTitles == null || subTitles.isEmpty()) {
            return false;
        }
        List<List<String>> fields = new ArrayList<>();
        fields.add(List.of("项目", blank(projectName, projectId)));
        fields.add(List.of("任务", blank(taskTitle, taskId)));
        if (StringUtils.hasText(operatorName)) {
            fields.add(List.of("添加人", operatorName));
        }
        if (StringUtils.hasText(creatorName) && !creatorName.equals(operatorName)) {
            fields.add(List.of("任务创建人", creatorName));
        }
        StringBuilder body = new StringBuilder();
        if (mentionOpenIds != null && !mentionOpenIds.isEmpty()) {
            for (String openId : mentionOpenIds) {
                if (StringUtils.hasText(openId)) {
                    body.append("<at id=").append(openId).append("></at> ");
                }
            }
            body.append(blank(operatorName, "有人")).append("给这条任务加了子任务，进来看看。\n\n");
        }
        for (String title : subTitles) {
            body.append("- ").append(title).append('\n');
        }
        return notifier.notifyCard(new FeishuWebhookNotifier.Card(
                blank(operatorName, "有人") + "新增了 " + subTitles.size() + " 条子任务", "blue",
                fields,
                excerpt(body.toString().trim(), "点开看详情。"),
                "查看任务", taskUrl(projectId, taskId)));
    }

    /**
     * 新建项目后在群里说一声。
     *
     * @param projectId   项目 id
     * @param projectName 项目名称
     * @param ownerName   负责人名字；推导不出来时可为空
     * @param goal        项目目标，截断后作为卡片正文
     * @return 是否真的发出去了
     */
    public boolean notifyProjectCreated(String projectId, String projectName, String ownerName, String goal) {
        if (muted(projectId)) {
            return false;
        }
        List<List<String>> fields = new ArrayList<>();
        fields.add(List.of("项目", blank(projectName, projectId)));
        if (StringUtils.hasText(ownerName)) {
            fields.add(List.of("负责人", ownerName));
        }
        return notifier.notifyCard(new FeishuWebhookNotifier.Card(
                "新建了项目", "blue", fields,
                excerpt(goal, "还没有填写目标，点开补充。"),
                "查看项目", links.open("/projects/" + projectId)));
    }

    /**
     * 新建小记后在群里说一声。
     *
     * @param kbId       小记所在知识库 id
     * @param docId      小记文档 id
     * @param title      小记标题
     * @param authorName 作者名字；推导不出来时可为空
     * @return 是否真的发出去了
     */
    public boolean notifyNoteCreated(String kbId, String docId, String title, String authorName) {
        List<List<String>> fields = new ArrayList<>();
        fields.add(List.of("小记", blank(title, docId)));
        if (StringUtils.hasText(authorName)) {
            fields.add(List.of("作者", authorName));
        }
        return notifier.notifyCard(new FeishuWebhookNotifier.Card(
                "新建了小记", "grey", fields,
                "一条新的小记已经写好，点开看看。",
                "查看小记", links.open("/kb/" + kbId + "?doc=" + docId)));
    }

    /**
     * 任务派给 AI（进入队列）后在群里说一声。
     *
     * @param taskId      任务 id
     * @param projectId   项目 id
     * @param taskTitle   任务标题
     * @param projectName 项目名称
     * @param engineLabel 执行工具名（Codex / Claude Code / Qoder）
     * @param scope       本次跑的范围描述（整个任务或某条子任务）
     * @param runnerName  指定的执行电脑名；未指定时可为空
     * @return 是否真的发出去了
     */
    public boolean notifyRunQueued(String taskId, String projectId, String taskTitle, String projectName,
            String engineLabel, String scope, String runnerName) {
        if (muted(projectId)) {
            return false;
        }
        List<List<String>> fields = new ArrayList<>();
        fields.add(List.of("项目", blank(projectName, projectId)));
        fields.add(List.of("任务", blank(taskTitle, taskId)));
        fields.add(List.of("执行工具", blank(engineLabel, "AI")));
        if (StringUtils.hasText(scope)) {
            fields.add(List.of("范围", scope));
        }
        return notifier.notifyCard(new FeishuWebhookNotifier.Card(
                "任务已派给 AI", "blue", fields,
                "已进入执行队列，等待" + (StringUtils.hasText(runnerName) ? runnerName : "在线电脑") + "领取。",
                "查看任务", taskUrl(projectId, taskId)));
    }

    /**
     * AI 开始执行（执行电脑领取任务）后在群里说一声。
     *
     * @param taskId      任务 id
     * @param projectId   项目 id
     * @param taskTitle   任务标题
     * @param projectName 项目名称
     * @param engineLabel 执行工具名
     * @param runnerName  领取任务的执行电脑名
     * @return 是否真的发出去了
     */
    public boolean notifyRunStarted(String taskId, String projectId, String taskTitle, String projectName,
            String engineLabel, String runnerName) {
        if (muted(projectId)) {
            return false;
        }
        return notifier.notifyCard(new FeishuWebhookNotifier.Card(
                "AI 开始执行", "grey",
                List.of(
                        List.of("项目", blank(projectName, projectId)),
                        List.of("任务", blank(taskTitle, taskId)),
                        List.of("执行工具", blank(engineLabel, "AI"))),
                blank(runnerName, "执行电脑") + " 已领取任务，" + blank(engineLabel, "AI") + " 正在启动。",
                "查看任务", taskUrl(projectId, taskId)));
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

    /** 这个项目关掉通知了吗；关了就整条跳过，连卡片都不组装。 */
    private boolean muted(String projectId) {
        return !notifyGate.enabled(projectId);
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
