package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.ActionCheckinEntity;
import cn.wayuwa.wao.domain.ActionDayNoteEntity;
import cn.wayuwa.wao.domain.ActionPlanEntity;
import cn.wayuwa.wao.domain.ActionPlanItemEntity;
import cn.wayuwa.wao.domain.ActionReminderDeliveryEntity;
import cn.wayuwa.wao.dto.ActionPlanDto;
import cn.wayuwa.wao.dto.ActionPlanItemDto;
import cn.wayuwa.wao.dto.ActionPlanItemInput;
import cn.wayuwa.wao.dto.SaveActionPlanRequest;
import cn.wayuwa.wao.feishu.FeishuAppLinks;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import cn.wayuwa.wao.mapper.ActionCheckinMapper;
import cn.wayuwa.wao.mapper.ActionDayNoteMapper;
import cn.wayuwa.wao.mapper.ActionPlanItemMapper;
import cn.wayuwa.wao.mapper.ActionPlanMapper;
import cn.wayuwa.wao.mapper.ActionReminderDeliveryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 长期行动计划、每日打卡、事实复盘和个人飞书提醒。 */
@Service
public class ActionPlanService {
    private static final ZoneId WAO_ZONE = ZoneId.of("Asia/Shanghai");
    private static final TypeReference<List<Integer>> WEEKDAYS = new TypeReference<>() { };
    private final ActionPlanMapper plans;
    private final ActionPlanItemMapper items;
    private final ActionCheckinMapper checkins;
    private final ActionDayNoteMapper notes;
    private final ActionReminderDeliveryMapper reminders;
    private final AccountMapper accounts;
    private final AccountIdentityMapper identities;
    private final SettingService settings;
    private final FeishuOpenApiClient feishu;
    private final ObjectMapper json;

    public ActionPlanService(ActionPlanMapper plans, ActionPlanItemMapper items, ActionCheckinMapper checkins,
            ActionDayNoteMapper notes, ActionReminderDeliveryMapper reminders, AccountMapper accounts,
            AccountIdentityMapper identities, SettingService settings, FeishuOpenApiClient feishu,
            ObjectMapper json) {
        this.plans = plans;
        this.items = items;
        this.checkins = checkins;
        this.notes = notes;
        this.reminders = reminders;
        this.accounts = accounts;
        this.identities = identities;
        this.settings = settings;
        this.feishu = feishu;
        this.json = json;
    }

    /** 返回当前成员的全部计划，并按选中日期投影打卡状态。 */
    public List<ActionPlanDto> list(String memberId, LocalDate selectedDate) {
        requireMember(memberId);
        LocalDate date = selectedDate == null ? LocalDate.now(WAO_ZONE) : selectedDate;
        return plans.selectList(new LambdaQueryWrapper<ActionPlanEntity>()
                        .eq(ActionPlanEntity::getMemberId, memberId)
                        .orderByAsc(ActionPlanEntity::getStatus)
                        .orderByDesc(ActionPlanEntity::getUpdatedAt))
                .stream().map(plan -> view(plan, date)).toList();
    }

    /** 新建当前成员私有计划。 */
    @Transactional
    public ActionPlanDto create(String memberId, SaveActionPlanRequest request) {
        requireMember(memberId);
        validate(request);
        LocalDateTime now = LocalDateTime.now(WAO_ZONE);
        ActionPlanEntity plan = new ActionPlanEntity();
        plan.setId(id("ap"));
        plan.setMemberId(memberId);
        plan.setStatus("ACTIVE");
        plan.setCreatedAt(now);
        apply(plan, request, now);
        plans.insert(plan);
        replaceItems(plan, request.items(), now);
        return view(plan, LocalDate.now(WAO_ZONE));
    }

    /** 调整计划；已经产生的历史打卡和日复盘不会被覆盖。 */
    @Transactional
    public ActionPlanDto update(String planId, String memberId, SaveActionPlanRequest request) {
        validate(request);
        ActionPlanEntity plan = requirePlan(planId, memberId);
        LocalDateTime now = LocalDateTime.now(WAO_ZONE);
        apply(plan, request, now);
        plans.updateById(plan);
        replaceItems(plan, request.items(), now);
        return view(plan, LocalDate.now(WAO_ZONE));
    }

    /** 标记计划已完成或重新开始，不删除历史。 */
    public ActionPlanDto setCompleted(String planId, String memberId, boolean completed) {
        ActionPlanEntity plan = requirePlan(planId, memberId);
        LocalDateTime now = LocalDateTime.now(WAO_ZONE);
        plan.setStatus(completed ? "COMPLETED" : "ACTIVE");
        plan.setCompletedAt(completed ? now : null);
        plan.setUpdatedAt(now);
        plans.updateById(plan);
        return view(plan, LocalDate.now(WAO_ZONE));
    }

    /** 切换某日一条行动的真实完成记录；未安排的日期不能打卡。 */
    @Transactional
    public ActionPlanDto toggleCheckin(String planId, String itemId, String memberId, LocalDate date) {
        ActionPlanEntity plan = requirePlan(planId, memberId);
        LocalDate selected = date == null ? LocalDate.now(WAO_ZONE) : date;
        if (selected.isAfter(LocalDate.now(WAO_ZONE))) throw new BadRequestException("不能提前打卡未来行动");
        ActionPlanItemEntity item = items.selectById(itemId);
        if (item == null || !planId.equals(item.getPlanId()) || !scheduled(item, selected)) {
            throw new BadRequestException("这一天没有安排该行动");
        }
        ActionCheckinEntity existing = findCheckin(itemId, selected);
        if (existing == null) {
            ActionCheckinEntity row = new ActionCheckinEntity();
            row.setId(id("ac")); row.setPlanId(planId); row.setItemId(itemId); row.setCheckinDate(selected);
            row.setCompletedAt(LocalDateTime.now(WAO_ZONE)); checkins.insert(row);
        } else {
            checkins.deleteById(existing.getId());
        }
        return view(plan, selected);
    }

    /** 保存或清空某一天的事实复盘。 */
    @Transactional
    public ActionPlanDto saveNote(String planId, String memberId, LocalDate date, String content) {
        ActionPlanEntity plan = requirePlan(planId, memberId);
        LocalDate selected = date == null ? LocalDate.now(WAO_ZONE) : date;
        if (selected.isAfter(LocalDate.now(WAO_ZONE))) throw new BadRequestException("不能填写未来复盘");
        ActionDayNoteEntity existing = findNote(planId, selected);
        String safe = content == null ? "" : content.trim();
        if (safe.isEmpty()) {
            if (existing != null) notes.deleteById(existing.getId());
        } else if (existing == null) {
            ActionDayNoteEntity row = new ActionDayNoteEntity();
            row.setId(id("an")); row.setPlanId(planId); row.setNoteDate(selected); row.setContent(safe);
            row.setCreatedAt(LocalDateTime.now(WAO_ZONE)); row.setUpdatedAt(row.getCreatedAt()); notes.insert(row);
        } else {
            existing.setContent(safe); existing.setUpdatedAt(LocalDateTime.now(WAO_ZONE)); notes.updateById(existing);
        }
        return view(plan, selected);
    }

    /** 发送当前到点且当天确有行动的个人飞书提醒；投递失败会保存错误并在五分钟后重试。 */
    public void sendDueReminders(LocalDateTime now) {
        LocalDate date = now.toLocalDate();
        plans.selectList(new LambdaQueryWrapper<ActionPlanEntity>()
                        .eq(ActionPlanEntity::getStatus, "ACTIVE")
                        .eq(ActionPlanEntity::getReminderEnabled, true)
                        .le(ActionPlanEntity::getReminderTime, now.toLocalTime()))
                .stream().filter(plan -> !date.isBefore(plan.getStartDate()) && !date.isAfter(plan.getTargetDate()))
                .filter(plan -> reminderReady(plan, date, now))
                .forEach(plan -> deliver(plan, date, now));
    }

    private void deliver(ActionPlanEntity plan, LocalDate date, LocalDateTime now) {
        List<ActionPlanItemEntity> today = activeItems(plan.getId()).stream().filter(item -> scheduled(item, date)).toList();
        if (today.isEmpty()) return;
        ActionReminderDeliveryEntity delivery = findDelivery(plan.getId(), date);
        if (delivery == null) {
            delivery = new ActionReminderDeliveryEntity();
            delivery.setId(id("ar")); delivery.setPlanId(plan.getId()); delivery.setReminderDate(date);
            delivery.setAttempts(1); delivery.setAttemptedAt(now); delivery.setStatus("PROCESSING");
            try {
                reminders.insert(delivery);
            } catch (DuplicateKeyException ignored) {
                return; // 另一应用节点已经抢到今天这次投递。
            }
        } else {
            if ("SENT".equals(delivery.getStatus()) || delivery.getAttempts() >= 6
                    || !delivery.getAttemptedAt().isBefore(now.minusMinutes(5))) return;
            int nextAttempts = delivery.getAttempts() + 1;
            if (reminders.claimRetry(delivery.getId(), delivery.getAttemptedAt(), now, nextAttempts) == 0) return;
            delivery.setAttempts(nextAttempts); delivery.setAttemptedAt(now); delivery.setStatus("PROCESSING");
        }
        try {
            var account = accounts.findByMemberId(plan.getMemberId());
            var identity = account == null ? null : identities.findByAccountProvider(account.getId(), "feishu");
            String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
            String secret = settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
            if (identity == null || !StringUtils.hasText(appId) || !StringUtils.hasText(secret)) {
                throw new IllegalStateException(identity == null ? "当前账号尚未绑定飞书" : "飞书自建应用尚未配置");
            }
            Set<String> done = checkins.selectList(new LambdaQueryWrapper<ActionCheckinEntity>()
                            .eq(ActionCheckinEntity::getPlanId, plan.getId())
                            .eq(ActionCheckinEntity::getCheckinDate, date)).stream()
                    .map(ActionCheckinEntity::getItemId).collect(java.util.stream.Collectors.toSet());
            String body = today.stream().map(item -> (done.contains(item.getId()) ? "✅ " : "□ ") + item.getTitle())
                    .collect(java.util.stream.Collectors.joining("\n"));
            feishu.sendDirectCard(feishu.tenantAccessToken(appId, secret), identity.getProviderSubject(),
                    "今日行动 · " + plan.getTitle(), body, FeishuAppLinks.SITE + "/action-plans");
            delivery.setStatus("SENT"); delivery.setSentAt(now); delivery.setErrorMessage(null);
        } catch (RuntimeException exception) {
            delivery.setStatus("FAILED");
            String message = exception.getMessage() == null ? "提醒发送失败" : exception.getMessage();
            delivery.setErrorMessage(message.substring(0, Math.min(message.length(), 512)));
        }
        reminders.updateById(delivery);
    }

    private boolean reminderReady(ActionPlanEntity plan, LocalDate date, LocalDateTime now) {
        ActionReminderDeliveryEntity row = findDelivery(plan.getId(), date);
        if (row == null) return true;
        if ("SENT".equals(row.getStatus())) return false;
        return row.getAttempts() < 6 && row.getAttemptedAt().isBefore(now.minusMinutes(5));
    }

    private ActionPlanDto view(ActionPlanEntity plan, LocalDate selectedDate) {
        List<ActionPlanItemEntity> all = allItems(plan.getId());
        Set<String> completed = checkins.selectList(new LambdaQueryWrapper<ActionCheckinEntity>()
                        .eq(ActionCheckinEntity::getPlanId, plan.getId())
                        .eq(ActionCheckinEntity::getCheckinDate, selectedDate)).stream()
                .map(ActionCheckinEntity::getItemId).collect(java.util.stream.Collectors.toSet());
        boolean inPlanRange = !selectedDate.isBefore(plan.getStartDate()) && !selectedDate.isAfter(plan.getTargetDate());
        List<ActionPlanItemDto> projected = all.stream().map(item -> new ActionPlanItemDto(
                item.getId(), item.getTitle(), readWeekdays(item), Boolean.TRUE.equals(item.getActive()),
                inPlanRange && scheduled(item, selectedDate),
                completed.contains(item.getId()))).toList();
        int scheduledCount = (int) projected.stream().filter(ActionPlanItemDto::scheduled).count();
        int completedCount = (int) projected.stream().filter(item -> item.scheduled() && item.completed()).count();
        ActionDayNoteEntity note = findNote(plan.getId(), selectedDate);
        Map<LocalDate, Set<String>> checkinDays = new HashMap<>();
        checkins.selectList(new LambdaQueryWrapper<ActionCheckinEntity>().eq(ActionCheckinEntity::getPlanId, plan.getId()))
                .forEach(row -> checkinDays.computeIfAbsent(row.getCheckinDate(), ignored -> new HashSet<>()).add(row.getItemId()));
        int completedDays = 0;
        for (LocalDate day = plan.getStartDate(); !day.isAfter(LocalDate.now(WAO_ZONE)) && !day.isAfter(plan.getTargetDate()); day = day.plusDays(1)) {
            Set<String> expected = scheduledIds(all, day);
            if (!expected.isEmpty() && checkinDays.getOrDefault(day, Set.of()).containsAll(expected)) completedDays++;
        }
        int streak = 0;
        for (LocalDate day = LocalDate.now(WAO_ZONE); !day.isBefore(plan.getStartDate()); day = day.minusDays(1)) {
            Set<String> expected = scheduledIds(all, day);
            if (expected.isEmpty()) continue;
            if (!checkinDays.getOrDefault(day, Set.of()).containsAll(expected)) break;
            streak++;
        }
        ActionReminderDeliveryEntity delivery = findDelivery(plan.getId(), LocalDate.now(WAO_ZONE));
        String reminderState = !Boolean.TRUE.equals(plan.getReminderEnabled()) ? "OFF"
                : delivery == null ? "WAITING" : delivery.getStatus();
        return new ActionPlanDto(plan.getId(), plan.getTitle(), plan.getVision(), plan.getStartDate(),
                plan.getTargetDate(), plan.getReminderTime(), Boolean.TRUE.equals(plan.getReminderEnabled()),
                plan.getStatus(), selectedDate, projected, note == null ? "" : note.getContent(), scheduledCount,
                completedCount, streak, completedDays, reminderState);
    }

    private void replaceItems(ActionPlanEntity plan, List<ActionPlanItemInput> requested, LocalDateTime now) {
        Map<String, ActionPlanItemEntity> existing = activeItems(plan.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(ActionPlanItemEntity::getId, item -> item));
        LocalDate changeDate = LocalDate.now(WAO_ZONE);
        Set<String> historicalItemIds = checkins.selectList(new LambdaQueryWrapper<ActionCheckinEntity>()
                        .eq(ActionCheckinEntity::getPlanId, plan.getId())
                        .lt(ActionCheckinEntity::getCheckinDate, changeDate)).stream()
                .map(ActionCheckinEntity::getItemId).collect(java.util.stream.Collectors.toSet());
        Set<String> kept = new HashSet<>();
        int ord = 0;
        for (ActionPlanItemInput input : requested) {
            ActionPlanItemEntity item = input.id() == null ? null : existing.get(input.id());
            String nextTitle = input.title().trim();
            String nextWeekdays = writeWeekdays(input.weekdays());
            boolean changed = item != null
                    && (!item.getTitle().equals(nextTitle) || !item.getWeekdaysJson().equals(nextWeekdays));
            if (changed) deleteCurrentCheckins(item.getId(), changeDate);
            if (changed && historicalItemIds.contains(item.getId())) {
                item.setActive(false); item.setActiveUntil(changeDate.minusDays(1));
                item.setUpdatedAt(now); items.updateById(item);
                item = null; // 有历史时新建版本，旧 id 继续解释过去打卡。
            }
            if (item == null) {
                item = new ActionPlanItemEntity(); item.setId(id("ai")); item.setPlanId(plan.getId());
                item.setActiveFrom(changeDate); item.setCreatedAt(now);
            }
            item.setTitle(nextTitle); item.setWeekdaysJson(nextWeekdays); item.setOrd(ord++);
            item.setActive(true); item.setActiveUntil(null); item.setUpdatedAt(now); kept.add(item.getId());
            if (items.selectById(item.getId()) == null) items.insert(item); else items.updateById(item);
        }
        existing.values().stream().filter(item -> !kept.contains(item.getId())).forEach(item -> {
            deleteCurrentCheckins(item.getId(), changeDate);
            item.setActive(false); item.setActiveUntil(changeDate.minusDays(1)); item.setUpdatedAt(now);
            items.updateById(item);
        });
    }

    private void apply(ActionPlanEntity plan, SaveActionPlanRequest request, LocalDateTime now) {
        plan.setTitle(request.title().trim()); plan.setVision(request.vision() == null ? "" : request.vision().trim());
        plan.setStartDate(request.startDate()); plan.setTargetDate(request.targetDate());
        plan.setReminderTime(request.reminderTime()); plan.setReminderEnabled(request.reminderEnabled()); plan.setUpdatedAt(now);
    }

    private void validate(SaveActionPlanRequest request) {
        if (request.startDate() == null || request.targetDate() == null) throw new BadRequestException("请填写开始和目标日期");
        if (request.targetDate().isBefore(request.startDate())) throw new BadRequestException("目标日期不能早于开始日期");
        if (request.reminderEnabled() && request.reminderTime() == null) throw new BadRequestException("开启提醒后请选择提醒时间");
        request.items().forEach(input -> {
            if (input.weekdays().stream().anyMatch(day -> day == null || day < 1 || day > 7)
                    || new HashSet<>(input.weekdays()).size() != input.weekdays().size()) {
                throw new BadRequestException("行动星期只能选择周一到周日且不能重复");
            }
        });
    }

    private ActionPlanEntity requirePlan(String planId, String memberId) {
        ActionPlanEntity plan = plans.selectById(planId);
        if (plan == null || !plan.getMemberId().equals(memberId)) throw new NotFoundException("行动计划不存在");
        return plan;
    }

    private List<ActionPlanItemEntity> activeItems(String planId) {
        return items.selectList(new LambdaQueryWrapper<ActionPlanItemEntity>()
                .eq(ActionPlanItemEntity::getPlanId, planId).eq(ActionPlanItemEntity::getActive, true)
                .orderByAsc(ActionPlanItemEntity::getOrd));
    }

    private List<ActionPlanItemEntity> allItems(String planId) {
        return items.selectList(new LambdaQueryWrapper<ActionPlanItemEntity>()
                .eq(ActionPlanItemEntity::getPlanId, planId).orderByAsc(ActionPlanItemEntity::getCreatedAt)
                .orderByAsc(ActionPlanItemEntity::getOrd));
    }

    private Set<String> scheduledIds(List<ActionPlanItemEntity> all, LocalDate date) {
        return all.stream().filter(item -> scheduled(item, date)).map(ActionPlanItemEntity::getId)
                .collect(java.util.stream.Collectors.toSet());
    }

    private boolean scheduled(ActionPlanItemEntity item, LocalDate date) {
        boolean inVersion = !date.isBefore(item.getActiveFrom())
                && (item.getActiveUntil() == null || !date.isAfter(item.getActiveUntil()));
        return inVersion && readWeekdays(item).contains(date.getDayOfWeek().getValue());
    }

    private void deleteCurrentCheckins(String itemId, LocalDate from) {
        checkins.selectList(new LambdaQueryWrapper<ActionCheckinEntity>()
                        .eq(ActionCheckinEntity::getItemId, itemId)
                        .ge(ActionCheckinEntity::getCheckinDate, from))
                .forEach(row -> checkins.deleteById(row.getId()));
    }

    private List<Integer> readWeekdays(ActionPlanItemEntity item) {
        try { return json.readValue(item.getWeekdaysJson(), WEEKDAYS); }
        catch (Exception exception) { throw new IllegalStateException("行动星期规则无法读取", exception); }
    }

    private String writeWeekdays(List<Integer> weekdays) {
        try { return json.writeValueAsString(weekdays.stream().sorted().toList()); }
        catch (Exception exception) { throw new IllegalStateException("行动星期规则无法保存", exception); }
    }

    private ActionCheckinEntity findCheckin(String itemId, LocalDate date) {
        return checkins.selectOne(new LambdaQueryWrapper<ActionCheckinEntity>()
                .eq(ActionCheckinEntity::getItemId, itemId).eq(ActionCheckinEntity::getCheckinDate, date).last("LIMIT 1"));
    }

    private ActionDayNoteEntity findNote(String planId, LocalDate date) {
        return notes.selectOne(new LambdaQueryWrapper<ActionDayNoteEntity>()
                .eq(ActionDayNoteEntity::getPlanId, planId).eq(ActionDayNoteEntity::getNoteDate, date).last("LIMIT 1"));
    }

    private ActionReminderDeliveryEntity findDelivery(String planId, LocalDate date) {
        return reminders.selectOne(new LambdaQueryWrapper<ActionReminderDeliveryEntity>()
                .eq(ActionReminderDeliveryEntity::getPlanId, planId)
                .eq(ActionReminderDeliveryEntity::getReminderDate, date).last("LIMIT 1"));
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 22);
    }

    private static void requireMember(String memberId) {
        if (!StringUtils.hasText(memberId)) throw new BadRequestException("无法识别当前成员");
    }
}
