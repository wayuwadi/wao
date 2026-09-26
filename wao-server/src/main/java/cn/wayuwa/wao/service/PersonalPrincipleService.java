package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.ForbiddenException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.PersonalPrincipleCheckinEntity;
import cn.wayuwa.wao.domain.PersonalPrincipleEntity;
import cn.wayuwa.wao.domain.PersonalPrincipleTargetEntity;
import cn.wayuwa.wao.dto.PersonalPrincipleDto;
import cn.wayuwa.wao.dto.SavePersonalPrincipleRequest;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.mapper.PersonalPrincipleMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 多成员准则：邀请须本人确认，观察只取本人明确提交的事实。 */
@Service
public class PersonalPrincipleService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> CATEGORIES = Set.of("RED_LINE", "MUST", "MORE", "LESS", "ALLOWED", "AVOID", "ADVICE");
    private static final Set<String> SOURCES = Set.of("MANUAL", "SCREEN_TIME");
    private static final Set<String> CHECKIN_STATES = Set.of("DONE", "NEEDS_WORK", "EXCEPTION");
    private final PersonalPrincipleMapper rules;
    private final MemberMapper members;
    private final ObjectMapper json;

    public PersonalPrincipleService(PersonalPrincipleMapper rules, MemberMapper members, ObjectMapper json) {
        this.rules = rules;
        this.members = members;
        this.json = json;
    }

    /** 当前成员的自建或受邀准则；其他人的记录永不随列表返回。 */
    public List<PersonalPrincipleDto> list(String memberId, LocalDate date) {
        LocalDate day = date == null ? LocalDate.now(ZONE) : date;
        return rules.visible(memberId).stream().map(rule -> project(rule, memberId, day)).toList();
    }

    /** 创建一条准则并对受邀人分别等待确认。 */
    @Transactional
    public PersonalPrincipleDto create(String memberId, SavePersonalPrincipleRequest request) {
        Set<String> targets = validate(request);
        LocalDateTime now = LocalDateTime.now(ZONE);
        PersonalPrincipleEntity rule = makeEntity(UUID.randomUUID().toString(), memberId, request, 1, now, now);
        rules.insert(rule);
        for (String targetId : targets) rules.insertTarget(target(rule.getId(), targetId,
                targetId.equals(memberId) ? "ACCEPTED" : "PENDING", now));
        snapshot(rule, rules.targets(rule.getId()), now);
        return project(rule, memberId, LocalDate.now(ZONE));
    }

    /** 只有发起人可修改；旧版本写入被拒绝，历史快照保留。 */
    @Transactional
    public PersonalPrincipleDto update(String ruleId, String memberId, SavePersonalPrincipleRequest request) {
        PersonalPrincipleEntity existing = require(ruleId);
        if (!memberId.equals(existing.getOwnerMemberId())) throw new ForbiddenException("只有准则发起人可编辑");
        if (request.version() == null || request.version() != existing.getVersion())
            throw new ConflictException("准则已被更新，请刷新后核对再保存");
        Set<String> targetIds = validate(request);
        LocalDateTime now = LocalDateTime.now(ZONE);
        PersonalPrincipleEntity updated = makeEntity(ruleId, memberId, request, existing.getVersion() + 1,
                existing.getCreatedAt(), now);
        if (rules.updateVersioned(updated, existing.getVersion()) != 1)
            throw new ConflictException("准则已被更新，请刷新后核对再保存");
        List<PersonalPrincipleTargetEntity> oldTargets = rules.targets(ruleId);
        for (PersonalPrincipleTargetEntity old : oldTargets) {
            if (!targetIds.contains(old.getMemberId())) rules.removeTarget(ruleId, old.getMemberId());
        }
        for (String targetId : targetIds) {
            if (oldTargets.stream().noneMatch(old -> old.getMemberId().equals(targetId)))
                rules.insertTarget(target(ruleId, targetId, targetId.equals(memberId) ? "ACCEPTED" : "PENDING", now));
        }
        snapshot(updated, rules.targets(ruleId), now);
        return project(updated, memberId, LocalDate.now(ZONE));
    }

    /** 适用成员自行接受或撤回准则；发起人不能代替别人授权。 */
    @Transactional
    public PersonalPrincipleDto consent(String ruleId, String memberId, String value) {
        if (!Set.of("ACCEPTED", "DECLINED").contains(value)) throw new BadRequestException("无效的参与状态");
        PersonalPrincipleEntity rule = require(ruleId);
        PersonalPrincipleTargetEntity target = rules.target(ruleId, memberId);
        if (target == null) throw new ForbiddenException("你不是这条准则的适用人");
        target.setConsent(value);
        target.setUpdatedAt(LocalDateTime.now(ZONE));
        rules.updateTarget(target);
        return project(rule, memberId, LocalDate.now(ZONE));
    }

    /** 只保存本人的手动事实；日期限制避免伪造遥远历史或未来结论。 */
    @Transactional
    public PersonalPrincipleDto checkin(String ruleId, String memberId, LocalDate date, String status, String note) {
        if (!CHECKIN_STATES.contains(status)) throw new BadRequestException("无效的记录状态");
        LocalDate today = LocalDate.now(ZONE);
        if (date == null || date.isAfter(today) || date.isBefore(today.minusDays(30)))
            throw new BadRequestException("只能记录最近 30 天的事实");
        PersonalPrincipleEntity rule = require(ruleId);
        PersonalPrincipleTargetEntity target = rules.target(ruleId, memberId);
        if (target == null || !"ACCEPTED".equals(target.getConsent())) throw new ForbiddenException("请先由本人接受准则");
        if (Boolean.TRUE.equals(rule.getPaused())) throw new BadRequestException("已暂停的准则不能记录");
        PersonalPrincipleCheckinEntity row = new PersonalPrincipleCheckinEntity();
        row.setPrincipleId(ruleId); row.setMemberId(memberId); row.setCheckinDate(date);
        row.setStatus(status); row.setNote(note == null ? "" : note.trim());
        row.setPrincipleVersion(rule.getVersion()); row.setUpdatedAt(LocalDateTime.now(ZONE));
        rules.saveCheckin(row);
        return project(rule, memberId, date);
    }

    /** 发起人与受邀人可查看规则版本文本，不包含任何成员记录。 */
    public List<String> revisions(String ruleId, String memberId) {
        PersonalPrincipleEntity rule = require(ruleId);
        if (!memberId.equals(rule.getOwnerMemberId()) && rules.target(ruleId, memberId) == null)
            throw new ForbiddenException("无权查看这条准则");
        return rules.revisions(ruleId);
    }

    private Set<String> validate(SavePersonalPrincipleRequest request) {
        if (!CATEGORIES.contains(request.category())) throw new BadRequestException("无效的准则类型");
        if (!SOURCES.contains(request.evidenceSource())) throw new BadRequestException("无效的证据来源");
        Set<String> targetIds = new LinkedHashSet<>(request.targetMemberIds());
        if (targetIds.isEmpty()) throw new BadRequestException("至少选择一位适用人");
        Set<String> directory = members.directory().stream().map(member -> member.getId()).collect(java.util.stream.Collectors.toSet());
        if (!directory.containsAll(targetIds)) throw new BadRequestException("适用人必须是当前成员目录中的成员");
        return targetIds;
    }

    private PersonalPrincipleEntity require(String id) {
        PersonalPrincipleEntity rule = rules.selectById(id);
        if (rule == null) throw new NotFoundException("准则不存在");
        return rule;
    }

    private PersonalPrincipleEntity makeEntity(String id, String ownerId, SavePersonalPrincipleRequest request,
            int version, LocalDateTime createdAt, LocalDateTime updatedAt) {
        PersonalPrincipleEntity rule = new PersonalPrincipleEntity();
        rule.setId(id); rule.setOwnerMemberId(ownerId); rule.setTitle(request.title().trim());
        rule.setCategory(request.category()); rule.setTriggerText(request.triggerText().trim());
        rule.setDesiredAction(request.desiredAction().trim());
        rule.setExceptionText(request.exceptionText() == null ? "" : request.exceptionText().trim());
        rule.setReminderTime(request.reminderTime()); rule.setEvidenceSource(request.evidenceSource());
        rule.setPaused(request.paused()); rule.setVersion(version);
        rule.setCreatedAt(createdAt); rule.setUpdatedAt(updatedAt);
        return rule;
    }

    private PersonalPrincipleTargetEntity target(String id, String memberId, String consent, LocalDateTime now) {
        PersonalPrincipleTargetEntity value = new PersonalPrincipleTargetEntity();
        value.setPrincipleId(id); value.setMemberId(memberId); value.setConsent(consent); value.setUpdatedAt(now);
        return value;
    }

    private void snapshot(PersonalPrincipleEntity rule, List<PersonalPrincipleTargetEntity> targets, LocalDateTime now) {
        try {
            rules.insertRevision(rule.getId(), rule.getVersion(), json.writeValueAsString(Map.of(
                    "version", rule.getVersion(), "title", rule.getTitle(), "category", rule.getCategory(),
                    "triggerText", rule.getTriggerText(), "desiredAction", rule.getDesiredAction(),
                    "exceptionText", rule.getExceptionText(), "reminderTime", rule.getReminderTime() == null ? "" : rule.getReminderTime().toString(),
                    "evidenceSource", rule.getEvidenceSource(), "paused", rule.getPaused(),
                    "targetMemberIds", targets.stream().map(PersonalPrincipleTargetEntity::getMemberId).toList())), now);
        } catch (JsonProcessingException exception) { throw new IllegalStateException("无法保存准则版本", exception); }
    }

    private PersonalPrincipleDto project(PersonalPrincipleEntity rule, String memberId, LocalDate date) {
        List<PersonalPrincipleTargetEntity> targets = rules.targets(rule.getId());
        PersonalPrincipleTargetEntity ownTarget = targets.stream().filter(item -> memberId.equals(item.getMemberId())).findFirst().orElse(null);
        String consent = ownTarget == null ? "NOT_TARGET" : ownTarget.getConsent();
        PersonalPrincipleCheckinEntity checkin = "ACCEPTED".equals(consent)
                ? rules.checkin(rule.getId(), memberId, date) : null;
        String judgment;
        String reason;
        if (Boolean.TRUE.equals(rule.getPaused())) { judgment = "PAUSED"; reason = "这条准则已暂停"; }
        else if ("NOT_TARGET".equals(consent)) { judgment = "MANAGED_ONLY"; reason = "由你发起，等待适用成员分别确认和记录"; }
        else if (!"ACCEPTED".equals(consent)) { judgment = "PENDING_CONSENT"; reason = "适用人尚未接受或已撤回"; }
        else if ("ADVICE".equals(rule.getCategory())) { judgment = "ADVICE"; reason = "建议仅供参考，不作遵守判断"; }
        else if (checkin != null) {
            judgment = switch (checkin.getStatus()) { case "DONE" -> "COMPLETED"; case "EXCEPTION" -> "EXCEPTION"; default -> "NEEDS_ATTENTION"; };
            reason = "由本人于 " + checkin.getUpdatedAt() + " 手动记录；不是设备监测结论";
        } else if ("SCREEN_TIME".equals(rule.getEvidenceSource())) {
            judgment = "WAITING_DATA"; reason = "尚未接入本人授权的屏幕使用时间来源，无法判断";
        } else {
            boolean due = date.isBefore(LocalDate.now(ZONE)) || date.equals(LocalDate.now(ZONE))
                    && rule.getReminderTime() != null && !LocalTime.now(ZONE).isBefore(rule.getReminderTime());
            judgment = due ? "RECORD_DUE" : "PENDING";
            reason = due ? "已到设定时间，等待本人记录；不据此判定违规" : "等待设定时间或本人记录";
        }
        PersonalPrincipleDto.Checkin own = checkin == null ? null : new PersonalPrincipleDto.Checkin(
                checkin.getStatus(), checkin.getNote(), checkin.getPrincipleVersion(), checkin.getUpdatedAt());
        return new PersonalPrincipleDto(rule.getId(), rule.getOwnerMemberId(), rule.getTitle(), rule.getCategory(),
                rule.getTriggerText(), rule.getDesiredAction(), rule.getExceptionText(), rule.getReminderTime(),
                rule.getEvidenceSource(), Boolean.TRUE.equals(rule.getPaused()), rule.getVersion(), rule.getUpdatedAt(),
                targets.stream().map(item -> new PersonalPrincipleDto.Target(item.getMemberId(), item.getConsent())).toList(),
                consent, date, own, judgment, reason);
    }
}
