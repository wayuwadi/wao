package cn.wayuwa.wao.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import biweekly.Biweekly;
import biweekly.ICalendar;
import biweekly.component.VEvent;
import biweekly.util.com.google.ical.compat.javautil.DateIterator;
import org.springframework.util.StringUtils;

/**
 * iCalendar（RFC 5545）事件解析：把 VEVENT 展开成时间窗内的具体发生（含 RRULE / RDATE /
 * EXDATE 重复规则与 RECURRENCE-ID 单次改期），供 CalDAV 同步落库。
 */
final class CaldavIcs {

    /** 单个事件在窗口内最多展开的次数，防御恶意/异常的无限重复规则。 */
    private static final int MAX_OCCURRENCES = 730;

    /** 一次具体发生。全天事件 start/end 都在 00:00，end 为「最后一天」而非排他端点。 */
    record Occurrence(String uid, String summary, String location,
            LocalDateTime start, LocalDateTime end, boolean allDay) {
    }

    private CaldavIcs() {
    }

    /** 解析一段 iCalendar 原文，返回窗口 [from, to] 内的全部发生。解析失败时返回空列表。 */
    static List<Occurrence> expand(String ics, LocalDateTime from, LocalDateTime to, ZoneId zone) {
        List<Occurrence> out = new ArrayList<>();
        List<ICalendar> calendars;
        try {
            calendars = Biweekly.parse(ics).all();
        } catch (RuntimeException ex) {
            return out;
        }
        TimeZone tz = TimeZone.getTimeZone(zone);
        Date fromDate = Date.from(from.atZone(zone).toInstant());
        Date toDate = Date.from(to.atZone(zone).toInstant());
        for (ICalendar cal : calendars) {
            // RECURRENCE-ID 覆盖件单独成 VEVENT：母事件展开时要跳过被覆盖的那次原始发生
            Map<String, Set<Long>> overridden = new HashMap<>();
            List<VEvent> masters = new ArrayList<>();
            for (VEvent event : cal.getEvents()) {
                if (event.getRecurrenceId() != null && event.getRecurrenceId().getValue() != null) {
                    overridden.computeIfAbsent(uidOf(event), k -> new HashSet<>())
                            .add(event.getRecurrenceId().getValue().getTime());
                    expandEvent(event, fromDate, toDate, zone, tz, Set.of(), out);
                } else {
                    masters.add(event);
                }
            }
            for (VEvent event : masters) {
                expandEvent(event, fromDate, toDate, zone, tz,
                        overridden.getOrDefault(uidOf(event), Set.of()), out);
            }
        }
        return out;
    }

    private static void expandEvent(VEvent event, Date from, Date to, ZoneId zone, TimeZone tz,
            Set<Long> skipStarts, List<Occurrence> out) {
        if (event.getDateStart() == null || event.getDateStart().getValue() == null) return;
        boolean allDay = !event.getDateStart().getValue().hasTime();
        long durationMillis = durationMillis(event);
        String uid = uidOf(event);
        String summary = event.getSummary() != null && StringUtils.hasText(event.getSummary().getValue())
                ? event.getSummary().getValue().trim()
                : "（无标题）";
        String location = event.getLocation() != null ? event.getLocation().getValue() : null;

        DateIterator it = event.getDateIterator(tz);
        // 先跳到窗口附近；长事件可能开始于窗口之前，回退一个事件时长保证仍能命中窗口
        it.advanceTo(new Date(from.getTime() - Math.max(durationMillis, 0)));
        int count = 0;
        while (it.hasNext() && count < MAX_OCCURRENCES) {
            Date startDate = it.next();
            if (startDate.getTime() > to.getTime()) break;
            if (skipStarts.contains(startDate.getTime())) continue;
            long endMillis = startDate.getTime() + durationMillis;
            if (endMillis < from.getTime()) continue;
            count++;
            LocalDateTime start = LocalDateTime.ofInstant(Instant.ofEpochMilli(startDate.getTime()), zone);
            LocalDateTime end;
            if (allDay) {
                // 全天事件的 DTEND 是排他的（次日 00:00），转成「最后一天」更符合展示直觉
                long lastDay = durationMillis > 86_400_000L ? endMillis - 86_400_000L : startDate.getTime();
                end = LocalDateTime.ofInstant(Instant.ofEpochMilli(lastDay), zone);
            } else {
                end = durationMillis > 0
                        ? LocalDateTime.ofInstant(Instant.ofEpochMilli(endMillis), zone)
                        : start;
            }
            out.add(new Occurrence(uid, summary, location, start, end, allDay));
        }
    }

    private static String uidOf(VEvent event) {
        return event.getUid() != null && StringUtils.hasText(event.getUid().getValue())
                ? event.getUid().getValue()
                : "no-uid";
    }

    private static long durationMillis(VEvent event) {
        if (event.getDateEnd() != null && event.getDateEnd().getValue() != null
                && event.getDateStart() != null && event.getDateStart().getValue() != null) {
            return event.getDateEnd().getValue().getTime() - event.getDateStart().getValue().getTime();
        }
        if (event.getDuration() != null && event.getDuration().getValue() != null) {
            return event.getDuration().getValue().toMillis();
        }
        return 0;
    }
}
