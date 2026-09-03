package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.domain.AttendanceConnectorEntity;
import cn.wayuwa.wao.domain.AttendanceDayEntity;
import cn.wayuwa.wao.dto.AttendanceDayRequest;
import cn.wayuwa.wao.dto.AttendanceDto;
import cn.wayuwa.wao.mapper.AttendanceConnectorMapper;
import cn.wayuwa.wao.mapper.AttendanceDayMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AttendanceServiceTest {
    private AttendanceDayMapper days; private WorkdayRhythmDashboardService rhythm; private AttendanceService service;

    @BeforeEach void setUp() {
        days=mock(AttendanceDayMapper.class); rhythm=mock(WorkdayRhythmDashboardService.class);
        when(rhythm.workdayInfo(any(),any())).thenReturn(new WorkdayRhythmDashboardService.WorkdayInfo(true,"大周 · 周日上班"));
        service=new AttendanceService(mock(AttendanceConnectorMapper.class),days,mock(VaultService.class),
                mock(VaultCryptoService.class),mock(MokaAttendanceClient.class),rhythm,new ObjectMapper());
    }

    @Test void calculatesElevenHoursForNineToNineAndExcludesLeaveFromAverage() {
        LocalDate first=LocalDate.of(2026,9,1), second=first.plusDays(1);
        AttendanceDayEntity worked=day(first,"2026-09-01T09:00:00","2026-09-01T21:00:00","WORKDAY");
        AttendanceDayEntity leave=day(second,null,null,"LEAVE");
        when(days.findRange("wg",first,second)).thenReturn(List.of(worked,leave));

        AttendanceDto result=service.attendance("wg",first,second);

        assertThat(result.days().get(0).workHours()).isEqualByComparingTo("11.00");
        assertThat(result.totalHours()).isEqualByComparingTo("11.00");
        assertThat(result.monthDailyAverageHours()).isEqualByComparingTo("11.00");
        assertThat(result.validDays()).isEqualTo(1);
    }

    @Test void manualPunchUsesNextDayAndSynchronizesRhythmWithExactTimes() {
        LocalDate date=LocalDate.of(2026,9,2); AttendanceDayEntity saved=day(date,"2026-09-02T21:00:00","2026-09-03T02:00:00","WORKDAY");saved.setManualOverride(true);saved.setManualClockInAt(saved.getRemoteClockInAt());saved.setManualClockOutAt(saved.getRemoteClockOutAt());saved.setManualDayType("WORKDAY");
        when(days.find("wg",date)).thenReturn(saved);

        AttendanceDto.Day result=service.saveManual("wg",date,new AttendanceDayRequest("21:00","02:00",true,"WORKDAY"));

        ArgumentCaptor<LocalDateTime> time=ArgumentCaptor.forClass(LocalDateTime.class);
        verify(rhythm).syncAttendanceCompletion(org.mockito.ArgumentMatchers.eq("wg"),org.mockito.ArgumentMatchers.eq(date),org.mockito.ArgumentMatchers.eq("CLOCK_IN"),time.capture());
        assertThat(time.getValue()).isEqualTo(LocalDateTime.of(2026,9,2,21,0));
        assertThat(result.workHours()).isEqualByComparingTo("4.00");
    }

    @Test void repeatedSessionExpiryStopsAfterOneReloginAndRequiresUserAction() {
        AttendanceConnectorMapper connectors=mock(AttendanceConnectorMapper.class); VaultService vault=mock(VaultService.class);
        VaultCryptoService crypto=mock(VaultCryptoService.class); MokaAttendanceClient moka=mock(MokaAttendanceClient.class);
        AttendanceConnectorEntity connector=connector("READY");
        when(connectors.selectById("wg")).thenReturn(connector); when(vault.revealSecret("vault-1","wg")).thenReturn("secret");
        MokaAttendanceClient.Session first=new MokaAttendanceClient.Session("uid-1","jwt-1");
        MokaAttendanceClient.Session second=new MokaAttendanceClient.Session("uid-2","jwt-2");
        when(moka.login("s008500@nl-ds.cn","secret")).thenReturn(first,second);
        when(crypto.encrypt(any())).thenReturn(new VaultCryptoService.Encrypted(new byte[]{1},new byte[]{2}));
        when(moka.employeeId(any())).thenReturn("employee-1");
        LocalDate from=LocalDate.of(2026,9,1); AtomicInteger calls=new AtomicInteger();
        when(moka.day(any(),any(),any())).thenAnswer(invocation -> {
            int call=calls.incrementAndGet();
            if(call==1||call==3) throw new MokaAttendanceClient.SessionExpiredException();
            LocalDate date=invocation.getArgument(2);
            return new MokaAttendanceClient.RemoteDay(date,date.atTime(9,0),date.atTime(21,0),"WORKDAY","");
        });

        org.assertj.core.api.Assertions.assertThatThrownBy(()->new AttendanceService(connectors,days,vault,crypto,moka,rhythm,new ObjectMapper())
                .sync("wg",from,from.plusDays(1)))
                .isInstanceOf(MokaAttendanceClient.AuthenticationRequiredException.class)
                .hasMessageContaining("已停止自动登录");

        verify(moka,times(2)).login("s008500@nl-ds.cn","secret");
        assertThat(connector.getStatus()).isEqualTo("NEEDS_LOGIN");
    }

    @Test void automaticRefreshesSkipConnectorWaitingForInteractiveLogin() {
        AttendanceConnectorMapper connectors=mock(AttendanceConnectorMapper.class); MokaAttendanceClient moka=mock(MokaAttendanceClient.class);
        AttendanceConnectorEntity connector=connector("NEEDS_LOGIN"); when(connectors.selectById("wg")).thenReturn(connector);
        when(connectors.selectList(any())).thenReturn(List.of(connector));
        AttendanceService guarded=new AttendanceService(connectors,days,mock(VaultService.class),mock(VaultCryptoService.class),moka,rhythm,new ObjectMapper());

        guarded.refreshTodayIfStale("wg",LocalDate.now(AttendanceService.ZONE));
        guarded.refreshBeforeReminder("wg",LocalDate.now(AttendanceService.ZONE),LocalDateTime.now(AttendanceService.ZONE));

        assertThat(guarded.enabledMembers()).isEmpty();
        verifyNoInteractions(moka);
    }

    private AttendanceDayEntity day(LocalDate date,String in,String out,String type){AttendanceDayEntity value=new AttendanceDayEntity();value.setMemberId("wg");value.setAttendanceDate(date);value.setRemoteClockInAt(in==null?null:LocalDateTime.parse(in));value.setRemoteClockOutAt(out==null?null:LocalDateTime.parse(out));value.setRemoteDayType(type);value.setManualOverride(false);return value;}
    private AttendanceConnectorEntity connector(String status){AttendanceConnectorEntity value=new AttendanceConnectorEntity();value.setMemberId("wg");value.setUsername("s008500@nl-ds.cn");value.setVaultItemId("vault-1");value.setEnabled(true);value.setStatus(status);return value;}
}
