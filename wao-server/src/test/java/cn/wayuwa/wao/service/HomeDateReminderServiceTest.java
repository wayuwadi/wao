package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.FamilyImportantDateEntity;
import cn.wayuwa.wao.mapper.FamilyImportantDateMapper;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HomeDateReminderServiceTest {
    private final FamilyImportantDateMapper dates = mock(FamilyImportantDateMapper.class);
    private final MemberPreferenceMapper preferences = mock(MemberPreferenceMapper.class);
    private final ConcurrentHashMap<String,String> stored = new ConcurrentHashMap<>();
    private final HolidayService holidays = new HolidayService();
    private Clock clock = Clock.fixed(Instant.parse("2026-10-06T03:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private HomeDateReminderService service() { return new HomeDateReminderService(dates, preferences, holidays, new ObjectMapper(), clock); }

    @BeforeEach void storage() {
        when(dates.selectList(any())).thenReturn(List.of());
        when(preferences.find(anyString(),anyString())).thenAnswer(call -> stored.get(call.getArgument(0)+"/"+call.getArgument(1)));
        when(preferences.insertIfAbsent(anyString(),anyString(),anyString())).thenAnswer(call ->
                stored.putIfAbsent(call.getArgument(0)+"/"+call.getArgument(1),call.getArgument(2))==null?1:0);
        when(preferences.compareAndSet(anyString(),anyString(),anyString(),anyString())).thenAnswer(call ->
                stored.replace(call.getArgument(0)+"/"+call.getArgument(1),call.getArgument(2),call.getArgument(3))?1:0);
    }
    private FamilyImportantDateEntity item(String id, String member, String date) {
        var item = new FamilyImportantDateEntity(); item.setId(id); item.setMemberId(member); item.setName("我的生日");
        item.setEventDate(LocalDate.parse(date)); item.setCalendarType("SOLAR"); item.setReminderDaysBefore(0);
        item.setReminderEnabled(false); item.setHomeReminderEnabled(true); return item;
    }

    @Test void explicitBirthdayAndPrivacyWithDeterministicPriority() {
        var generic=item("generic","wg","1990-10-06");generic.setNote("秘密备注");
        var self=item("self","wg","1991-10-06");self.setEventKind("BIRTHDAY");self.setBirthdaySubject("SELF");
        var other=item("other","different-member","1991-10-06");
        when(dates.selectList(any())).thenReturn(List.of(self,other,generic));
        var result=service().queue("wg");
        assertThat(result.notices()).hasSize(2);
        assertThat(result.notices().get(0).kind()).isEqualTo("IMPORTANT");
        assertThat(result.notices().get(0).title()).contains("今天记得");
        assertThat(result.notices().get(1).title()).contains("生日快乐");
        assertThat(result.toString()).doesNotContain("秘密备注","different-member","1991");
        verify(preferences,never()).insertIfAbsent(anyString(),anyString(),anyString());
    }
    @Test void advanceAndDayOfAreIndependentAndPastOrDisabledDatesDoNotCatchUp() {
        var next=item("next","wg","1990-10-09");next.setReminderDaysBefore(3);
        var past=item("past","wg","1990-10-05");
        var disabled=item("disabled","wg","1990-10-06");disabled.setHomeReminderEnabled(false);
        when(dates.selectList(any())).thenReturn(List.of(next,past,disabled));
        var first=service().queue("wg");assertThat(first.notices()).hasSize(1);
        assertThat(first.notices().get(0).daysBefore()).isEqualTo(3);
        var reserve=service().reserve("wg",first.date(),List.of(first.notices().get(0).key()));
        service().record("wg",first.date(),List.of(first.notices().get(0).key()),reserve.token(),"SHOWN");
        clock=Clock.offset(clock,Duration.ofDays(3));
        var dayOf=service().queue("wg");assertThat(dayOf.notices()).hasSize(1);
        assertThat(dayOf.notices().get(0).daysBefore()).isZero();
        assertThat(dayOf.notices().get(0).key()).isNotEqualTo(first.notices().get(0).key());
    }
    @Test void lunarOccurrenceAndTwentyFourSolarTermsUseRealCalendar() {
        clock=Clock.fixed(Instant.parse("2026-02-17T03:00:00Z"),ZoneId.of("Asia/Shanghai"));
        var lunar=item("lunar","wg","2025-01-29");lunar.setCalendarType("LUNAR");lunar.setLunarMonth(1);lunar.setLunarDay(1);lunar.setLunarLeapMonth(false);
        when(dates.selectList(any())).thenReturn(List.of(lunar));
        assertThat(service().queue("wg").notices()).extracting(HomeDateReminderService.Notice::date).contains("2026-02-17");
        var terms=holidays.allEvents(LocalDate.of(2026,1,1),LocalDate.of(2026,12,31)).stream().filter(event->event.code().startsWith("china.solar-term.")).toList();
        assertThat(terms).hasSize(24);
        for(var term:terms){
            clock=Clock.fixed(LocalDate.parse(term.date()).atTime(9,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant(),ZoneId.of("Asia/Shanghai"));
            assertThat(service().queue("wg").notices()).anyMatch(notice->notice.kind().equals("SOLAR_TERM")&&notice.date().equals(term.date()));
        }
    }
    @Test void concurrentDevicesHaveOnlyOneLeaseAndOnlyVisibleReceiptConsumes() {
        when(dates.selectList(any())).thenReturn(List.of(item("id","wg","1990-10-06")));
        var candidate=service().queue("wg");var keys=List.of(candidate.notices().get(0).key());
        var reservations=IntStream.range(0,8).parallel().mapToObj(i->service().reserve("wg",candidate.date(),keys)).toList();
        assertThat(reservations.stream().filter(value->!value.notices().isEmpty())).hasSize(1);
        var claimed=reservations.stream().filter(value->!value.notices().isEmpty()).findFirst().orElseThrow();
        service().record("wg",candidate.date(),keys,claimed.token(),"RELEASED");
        assertThat(service().queue("wg").notices()).hasSize(1);
        claimed=service().reserve("wg",candidate.date(),keys);
        service().record("wg",candidate.date(),keys,claimed.token(),"SHOWN");
        service().record("wg",candidate.date(),keys,claimed.token(),"CONFIRMED");
        service().record("wg",candidate.date(),keys,claimed.token(),"CONFIRMED");
        assertThat(service().queue("wg").notices()).isEmpty();
        verify(dates,never()).updateById(any(FamilyImportantDateEntity.class));
    }
    @Test void unshownLeaseExpiresAndStaleTokenCannotConsumeNewLease() {
        when(dates.selectList(any())).thenReturn(List.of(item("id","wg","1990-10-06")));
        var candidate=service().queue("wg");var keys=List.of(candidate.notices().get(0).key());
        var first=service().reserve("wg",candidate.date(),keys);
        clock=Clock.offset(clock,Duration.ofSeconds(21));
        var second=service().reserve("wg",candidate.date(),keys);assertThat(second.notices()).hasSize(1);
        assertThatThrownBy(()->service().record("wg",candidate.date(),keys,first.token(),"SHOWN")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->service().record("another-member",candidate.date(),keys,second.token(),"SHOWN")).isInstanceOf(ConflictException.class);
    }
    @Test void deletedOrDisabledSourceAndOldShanghaiDateAreRejected() {
        var item=item("id","wg","1990-10-06");when(dates.selectList(any())).thenReturn(List.of(item));
        var candidate=service().queue("wg");var keys=List.of(candidate.notices().get(0).key());
        var first=service().reserve("wg",candidate.date(),keys);item.setHomeReminderEnabled(false);
        assertThatThrownBy(()->service().record("wg",candidate.date(),keys,first.token(),"SHOWN")).isInstanceOf(ConflictException.class);
        assertThat(service().reserve("wg",candidate.date(),keys).notices()).isEmpty();
        clock=Clock.fixed(Instant.parse("2026-10-06T16:00:01Z"),ZoneId.of("Asia/Shanghai"));
        assertThatThrownBy(()->service().reserve("wg",candidate.date(),keys)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->service().queue(null)).isInstanceOf(UnauthorizedException.class);
    }
    @Test void settingsCompareAndSetAndDisableAreIndependentOfFeishu() {
        var service=service();var initial=service.settings("wg");
        var saved=service.saveSettings("wg",new HomeDateReminderService.Settings(false,false,initial.revision()));
        assertThat(saved.revision()).isEqualTo(1);assertThat(service.queue("wg").notices()).isEmpty();
        assertThatThrownBy(()->service.saveSettings("wg",new HomeDateReminderService.Settings(true,false,0))).isInstanceOf(ConflictException.class);
        assertThat(service.saveSettings("wg",new HomeDateReminderService.Settings(false,false,0))).isEqualTo(saved);
        assertThat(service.settings("other-member").enabled()).isTrue();
    }
}
