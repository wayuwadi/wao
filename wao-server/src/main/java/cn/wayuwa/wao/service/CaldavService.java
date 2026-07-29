package cn.wayuwa.wao.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.domain.CaldavAccountEntity;
import cn.wayuwa.wao.domain.CaldavCalendarEntity;
import cn.wayuwa.wao.domain.CaldavEventEntity;
import cn.wayuwa.wao.dto.CaldavAccountDto;
import cn.wayuwa.wao.dto.CaldavCalendarDto;
import cn.wayuwa.wao.dto.CaldavEventDto;
import cn.wayuwa.wao.dto.CreateCaldavAccountRequest;
import cn.wayuwa.wao.dto.PatchCaldavCalendarRequest;
import cn.wayuwa.wao.mapper.CaldavAccountMapper;
import cn.wayuwa.wao.mapper.CaldavCalendarMapper;
import cn.wayuwa.wao.mapper.CaldavEventMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * CalDAV 域服务：账户的增删与校验、日历订阅开关、事件的定时/手动单向同步与合并查询。
 *
 * <p>凭据用 {@link VaultCryptoService} AES-256-GCM 加密落库，明文只在同步时按需解密。
 * 事件是服务器数据的只读副本：每次同步按日历「先清后灌」整体重建，天然幂等，两个节点
 * 各跑各的调度也不会产生脏数据。
 */
@Service
public class CaldavService {

    private static final Logger log = LoggerFactory.getLogger(CaldavService.class);
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    /** 同步窗口：过去 90 天到未来 400 天，覆盖「看去年安排」和「排明年计划」两类高频动作。 */
    private static final int PAST_DAYS = 90;
    private static final int FUTURE_DAYS = 400;

    /** 服务器没给颜色时按序补齐，取自设计稿的 CalDAV 色板。 */
    private static final String[] FALLBACK_COLORS = {"#0E8C8C", "#C05D8E", "#B9821F", "#4358D6", "#2C8A5B"};

    private final CaldavAccountMapper accountMapper;
    private final CaldavCalendarMapper calendarMapper;
    private final CaldavEventMapper eventMapper;
    private final CaldavClient client;
    private final VaultCryptoService crypto;
    private final AtomicLong seq = new AtomicLong();

    public CaldavService(CaldavAccountMapper accountMapper, CaldavCalendarMapper calendarMapper,
            CaldavEventMapper eventMapper, CaldavClient client, VaultCryptoService crypto) {
        this.accountMapper = accountMapper;
        this.calendarMapper = calendarMapper;
        this.eventMapper = eventMapper;
        this.client = client;
        this.crypto = crypto;
    }

    /**
     * 列出当前成员的全部 CalDAV 账户及其日历。
     *
     * @param memberId 当前成员 id
     * @return 账户列表，从不包含凭据
     */
    public List<CaldavAccountDto> listAccounts(String memberId) {
        return accountMapper.selectList(new LambdaQueryWrapper<CaldavAccountEntity>()
                        .eq(CaldavAccountEntity::getMemberId, memberId)
                        .orderByAsc(CaldavAccountEntity::getCreatedAt))
                .stream().map(this::toDto).toList();
    }

    /**
     * 添加 CalDAV 账户：先真实连上服务器完成日历发现，成功才落库，随后立即做首次同步。
     *
     * @param req      服务器地址与凭据
     * @param memberId 当前成员 id
     * @return 新账户（含发现的日历与首次同步状态）
     */
    public CaldavAccountDto addAccount(CreateCaldavAccountRequest req, String memberId) {
        String serverUrl = client.normalizeServerUrl(req.serverUrl());
        String username = req.username().trim();
        List<CaldavClient.RemoteCalendar> remotes = client.discoverCalendars(serverUrl, username, req.password());

        CaldavAccountEntity account = new CaldavAccountEntity();
        account.setId("cda" + System.currentTimeMillis());
        account.setMemberId(memberId);
        account.setName(StringUtils.hasText(req.name()) ? req.name().trim() : username);
        account.setServerUrl(serverUrl);
        account.setUsername(username);
        VaultCryptoService.Encrypted enc = crypto.encrypt(req.password());
        account.setPasswordCiphertext(enc.ciphertext());
        account.setPasswordNonce(enc.nonce());
        account.setStatus("ok");
        account.setCreatedAt(LocalDateTime.now());
        accountMapper.insert(account);

        int ord = 0;
        for (CaldavClient.RemoteCalendar remote : remotes) {
            CaldavCalendarEntity cal = new CaldavCalendarEntity();
            cal.setId("cdc" + System.currentTimeMillis() + Long.toString(seq.incrementAndGet(), 36));
            cal.setAccountId(account.getId());
            cal.setHref(remote.href());
            cal.setName(remote.name());
            cal.setColor(StringUtils.hasText(remote.color())
                    ? remote.color()
                    : FALLBACK_COLORS[ord % FALLBACK_COLORS.length]);
            cal.setEnabled(true);
            cal.setOrd(ord++);
            calendarMapper.insert(cal);
        }
        syncAccount(account);
        return toDto(account);
    }

    /**
     * 删除账户及其日历与事件副本。
     *
     * @param id       账户 id
     * @param memberId 当前成员 id
     */
    public void deleteAccount(String id, String memberId) {
        CaldavAccountEntity account = owned(id, memberId);
        List<CaldavCalendarEntity> calendars = calendarsOf(account.getId());
        for (CaldavCalendarEntity cal : calendars) {
            eventMapper.delete(new LambdaQueryWrapper<CaldavEventEntity>()
                    .eq(CaldavEventEntity::getCalendarId, cal.getId()));
            calendarMapper.deleteById(cal.getId());
        }
        accountMapper.deleteById(id);
    }

    /**
     * 手动触发一次同步。
     *
     * @param id       账户 id
     * @param memberId 当前成员 id
     * @return 同步后的账户状态
     */
    public CaldavAccountDto syncNow(String id, String memberId) {
        CaldavAccountEntity account = owned(id, memberId);
        syncAccount(account);
        return toDto(account);
    }

    /**
     * 启用 / 停用某个订阅日历。停用即从合并视图消失，事件副本一并清掉。
     *
     * @param calendarId 日历 id
     * @param req        目标状态
     * @param memberId   当前成员 id
     * @return 更新后的日历
     */
    public CaldavCalendarDto toggleCalendar(String calendarId, PatchCaldavCalendarRequest req, String memberId) {
        CaldavCalendarEntity cal = calendarMapper.selectById(calendarId);
        if (cal == null) throw new NotFoundException("日历不存在");
        CaldavAccountEntity account = owned(cal.getAccountId(), memberId);
        if (req.enabled() == null) throw new BadRequestException("缺少 enabled 字段");
        cal.setEnabled(req.enabled());
        calendarMapper.updateById(cal);
        if (req.enabled()) {
            syncAccount(account);
        } else {
            eventMapper.delete(new LambdaQueryWrapper<CaldavEventEntity>()
                    .eq(CaldavEventEntity::getCalendarId, cal.getId()));
        }
        return new CaldavCalendarDto(cal.getId(), cal.getName(), cal.getColor(), Boolean.TRUE.equals(cal.getEnabled()));
    }

    /**
     * 合并查询当前成员启用日历在给定日期区间内的事件。
     *
     * @param memberId 当前成员 id
     * @param from     起始日期（含）
     * @param to       结束日期（含）
     * @return 事件列表，按开始时间升序
     */
    public List<CaldavEventDto> events(String memberId, LocalDate from, LocalDate to) {
        List<CaldavEventDto> out = new ArrayList<>();
        for (CaldavAccountEntity account : accountMapper.selectList(
                new LambdaQueryWrapper<CaldavAccountEntity>().eq(CaldavAccountEntity::getMemberId, memberId))) {
            for (CaldavCalendarEntity cal : calendarsOf(account.getId())) {
                if (!Boolean.TRUE.equals(cal.getEnabled())) continue;
                List<CaldavEventEntity> events = eventMapper.selectList(
                        new LambdaQueryWrapper<CaldavEventEntity>()
                                .eq(CaldavEventEntity::getCalendarId, cal.getId())
                                .ge(CaldavEventEntity::getStartAt, from.atStartOfDay())
                                .lt(CaldavEventEntity::getStartAt, to.plusDays(1).atStartOfDay())
                                .orderByAsc(CaldavEventEntity::getStartAt));
                for (CaldavEventEntity e : events) {
                    out.add(new CaldavEventDto(e.getId(), cal.getId(), cal.getName(), cal.getColor(),
                            e.getSummary(), e.getLocation(),
                            e.getStartAt() == null ? null : MINUTE.format(e.getStartAt()),
                            e.getEndAt() == null ? null : MINUTE.format(e.getEndAt()),
                            Boolean.TRUE.equals(e.getAllDay())));
                }
            }
        }
        out.sort((a, b) -> a.start().compareTo(b.start()));
        return out;
    }

    /** 定时任务入口：同步所有账户。单账户失败只记录状态，不影响其他账户。 */
    public void syncAll() {
        for (CaldavAccountEntity account : accountMapper.selectList(null)) {
            syncAccount(account);
        }
    }

    /** 同步一个账户：对每个启用日历 REPORT 时间窗内事件并整体重建副本。 */
    private void syncAccount(CaldavAccountEntity account) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime from = LocalDate.now().minusDays(PAST_DAYS).atStartOfDay();
        LocalDateTime to = LocalDate.now().plusDays(FUTURE_DAYS).atStartOfDay();
        try {
            String password = crypto.decrypt(account.getPasswordCiphertext(), account.getPasswordNonce());
            for (CaldavCalendarEntity cal : calendarsOf(account.getId())) {
                if (!Boolean.TRUE.equals(cal.getEnabled())) continue;
                List<String> payloads = client.fetchCalendarData(cal.getHref(), account.getUsername(), password,
                        from.atZone(zone), to.atZone(zone));
                List<CaldavEventEntity> fresh = new ArrayList<>();
                for (String ics : payloads) {
                    for (CaldavIcs.Occurrence occ : CaldavIcs.expand(ics, from, to, zone)) {
                        CaldavEventEntity e = new CaldavEventEntity();
                        e.setId("cde" + Long.toString(System.currentTimeMillis(), 36)
                                + Long.toString(seq.incrementAndGet(), 36));
                        e.setCalendarId(cal.getId());
                        e.setUid(truncate(occ.uid(), 255));
                        e.setSummary(truncate(occ.summary(), 512));
                        e.setLocation(truncate(occ.location(), 512));
                        e.setStartAt(occ.start());
                        e.setEndAt(occ.end());
                        e.setAllDay(occ.allDay());
                        fresh.add(e);
                    }
                }
                eventMapper.delete(new LambdaQueryWrapper<CaldavEventEntity>()
                        .eq(CaldavEventEntity::getCalendarId, cal.getId()));
                for (CaldavEventEntity e : fresh) {
                    eventMapper.insert(e);
                }
            }
            account.setStatus("ok");
            account.setLastSyncAt(LocalDateTime.now());
            account.setSyncError(null);
        } catch (Exception ex) {
            log.warn("CalDAV 同步失败：account={} {}", account.getId(), ex.getMessage());
            account.setStatus("error");
            account.setSyncError(truncate(ex.getMessage() == null ? "同步失败" : ex.getMessage(), 512));
        }
        accountMapper.updateById(account);
    }

    private CaldavAccountEntity owned(String id, String memberId) {
        CaldavAccountEntity account = accountMapper.selectById(id);
        if (account == null || !account.getMemberId().equals(memberId)) {
            throw new NotFoundException("CalDAV 账户不存在");
        }
        return account;
    }

    private List<CaldavCalendarEntity> calendarsOf(String accountId) {
        return calendarMapper.selectList(new LambdaQueryWrapper<CaldavCalendarEntity>()
                .eq(CaldavCalendarEntity::getAccountId, accountId)
                .orderByAsc(CaldavCalendarEntity::getOrd));
    }

    private CaldavAccountDto toDto(CaldavAccountEntity account) {
        List<CaldavCalendarDto> calendars = calendarsOf(account.getId()).stream()
                .map(c -> new CaldavCalendarDto(c.getId(), c.getName(), c.getColor(),
                        Boolean.TRUE.equals(c.getEnabled())))
                .toList();
        return new CaldavAccountDto(account.getId(), account.getName(), account.getServerUrl(),
                account.getUsername(), account.getStatus(),
                account.getLastSyncAt() == null ? null : MINUTE.format(account.getLastSyncAt()),
                account.getSyncError(), calendars);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
