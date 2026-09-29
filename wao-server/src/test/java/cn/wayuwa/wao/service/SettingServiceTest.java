package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.domain.SystemSettingEntity;
import cn.wayuwa.wao.mapper.SystemSettingMapper;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

class SettingServiceTest {

    private static final String TEST_KEY = "ZKp9ywcbuZOrQCrxx2kd+0N9VJfnzRNSQQZXMP/vXVw=";

    private final SystemSettingMapper mapper = mock(SystemSettingMapper.class);
    private final SettingService service = new SettingService(mapper, new VaultCryptoService(TEST_KEY));

    /** 让 mapper 表现得像一张真表：insert / updateById 写进 map，selectById 读回来。 */
    private Map<String, SystemSettingEntity> backedByMemory() {
        Map<String, SystemSettingEntity> rows = new HashMap<>();
        when(mapper.selectById(any())).thenAnswer((InvocationOnMock i) -> rows.get(i.getArgument(0).toString()));
        when(mapper.insert(any(SystemSettingEntity.class))).thenAnswer((InvocationOnMock i) -> {
            SystemSettingEntity e = i.getArgument(0);
            rows.put(e.getCfgKey(), e);
            return 1;
        });
        when(mapper.updateById(any(SystemSettingEntity.class))).thenAnswer((InvocationOnMock i) -> {
            SystemSettingEntity e = i.getArgument(0);
            rows.put(e.getCfgKey(), e);
            return 1;
        });
        return rows;
    }

    @Test
    void plainValueRoundTrips() {
        backedByMemory();

        service.put(SettingService.CHAT_SYNC_APP_ID, "cli_demo");

        assertThat(service.get(SettingService.CHAT_SYNC_APP_ID)).isEqualTo("cli_demo");
    }

    @Test
    void secretValueIsEncryptedAtRestButReadsBackAsPlaintext() {
        Map<String, SystemSettingEntity> rows = backedByMemory();

        service.put(SettingService.CHAT_SYNC_APP_SECRET, "super-secret-value");

        assertThat(rows.get(SettingService.CHAT_SYNC_APP_SECRET).getCfgValue())
                .startsWith("enc:")
                .doesNotContain("super-secret-value");
        assertThat(service.get(SettingService.CHAT_SYNC_APP_SECRET)).isEqualTo("super-secret-value");
    }

    @Test
    void putNullKeepsExistingValue() {
        backedByMemory();
        service.put(SettingService.CHAT_SYNC_APP_SECRET, "keep-me");

        service.put(SettingService.CHAT_SYNC_APP_SECRET, null);

        assertThat(service.get(SettingService.CHAT_SYNC_APP_SECRET)).isEqualTo("keep-me");
    }

    @Test
    void putNullOnUnknownKeyWritesNothing() {
        when(mapper.selectById(any())).thenReturn(null);

        service.put(SettingService.FEISHU_WEBHOOK_URL, null);

        verify(mapper, never()).insert(any(SystemSettingEntity.class));
    }

    @Test
    void getFallsBackWhenMissingOrBlank() {
        when(mapper.selectById(any())).thenReturn(null);

        assertThat(service.get(SettingService.CHAT_SYNC_CHAT_ID, "无")).isEqualTo("无");
        assertThat(service.getBoolean(SettingService.FEISHU_WEBHOOK_ENABLED)).isFalse();
    }

    @Test
    void maskKeepsOnlyHeadAndTail() {
        assertThat(SettingService.mask("fake0000000000000000000000secret")).isEqualTo("fake••••••cret");
        assertThat(SettingService.mask("short")).isEqualTo("••••");
        assertThat(SettingService.mask(null)).isEmpty();
    }
}
