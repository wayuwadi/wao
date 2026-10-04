package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.dto.SaveNetworkOutageWindowRequest;
import org.junit.jupiter.api.Test;

class NetworkOutageWindowServiceTest {

    private final SettingService settings = mock(SettingService.class);
    private final NetworkOutageWindowService service = new NetworkOutageWindowService(settings,
            Clock.fixed(Instant.parse("2026-08-25T17:30:00Z"), ZoneId.of("Asia/Shanghai")));

    private void configured(String enabled, String start, String end) {
        when(settings.getAll(Set.of(NetworkOutageWindowService.ENABLED_KEY,
                NetworkOutageWindowService.START_KEY, NetworkOutageWindowService.END_KEY)))
                .thenReturn(Map.of(NetworkOutageWindowService.ENABLED_KEY, enabled,
                        NetworkOutageWindowService.START_KEY, start,
                        NetworkOutageWindowService.END_KEY, end));
    }

    @Test
    void retiredWindowNeverBlocksAutomaticTasksIncludingOldDefaultHours() {
        when(settings.getAll(Set.of(NetworkOutageWindowService.ENABLED_KEY,
                NetworkOutageWindowService.START_KEY, NetworkOutageWindowService.END_KEY)))
                .thenReturn(new java.util.HashMap<>());
        assertThat(service.get().enabled()).isTrue();
        assertThat(service.isUnavailable(LocalTime.MIDNIGHT)).isFalse();
        assertThat(service.isUnavailable(LocalTime.of(5, 59))).isFalse();
        assertThat(service.isUnavailable(LocalTime.of(6, 0))).isFalse();
        assertThat(service.isUnavailable()).isFalse();
    }

    @Test
    void ignoresHistoricalEnabledCrossMidnightWindow() {
        configured("true", "23:30", "06:15");

        assertThat(service.isUnavailable(LocalTime.of(23, 30))).isFalse();
        assertThat(service.isUnavailable(LocalTime.of(1, 0))).isFalse();
        assertThat(service.isUnavailable(LocalTime.of(6, 15))).isFalse();
        assertThat(service.isUnavailable(LocalTime.NOON)).isFalse();
    }

    @Test
    void disabledWindowNeverBlocksAutomaticTasks() {
        configured("false", "00:00", "06:00");

        assertThat(service.isUnavailable(LocalTime.of(1, 0))).isFalse();
    }

    @Test
    void savesNormalizedTimesAndRejectsAmbiguousFullDayWindow() {
        configured("true", "00:30", "06:30");

        service.save(new SaveNetworkOutageWindowRequest(true, "00:30", "06:30"));

        verify(settings).put(NetworkOutageWindowService.ENABLED_KEY, "true");
        verify(settings).put(NetworkOutageWindowService.START_KEY, "00:30");
        verify(settings).put(NetworkOutageWindowService.END_KEY, "06:30");
        assertThatThrownBy(() -> service.save(new SaveNetworkOutageWindowRequest(true, "06:00", "06:00")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("不能相同");
    }
}
