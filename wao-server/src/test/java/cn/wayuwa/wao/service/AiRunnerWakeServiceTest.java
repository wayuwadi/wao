package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.AiRunnerNodeEntity;
import cn.wayuwa.wao.dto.UpdateRunnerWakeConfigRequest;
import cn.wayuwa.wao.mapper.AiRunnerNodeMapper;
import org.junit.jupiter.api.Test;

class AiRunnerWakeServiceTest {

    private final AiRunnerNodeMapper mapper = mock(AiRunnerNodeMapper.class);
    private final AiRunnerWakeService service = new AiRunnerWakeService(mapper);

    @Test
    void magicPacketContainsHeaderAndSixteenMacCopies() {
        byte[] packet = AiRunnerWakeService.magicPacket("00:11:22:33:44:55");

        assertThat(packet).hasSize(102);
        assertThat(packet).startsWith((byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff);
        assertThat(packet).containsSequence((byte) 0x00, (byte) 0x11, (byte) 0x22, (byte) 0x33, (byte) 0x44, (byte) 0x55);
    }

    @Test
    void updateConfigNormalizesMacAndDefaultsPort() {
        AiRunnerNodeEntity runner = new AiRunnerNodeEntity();
        runner.setId("rn1");
        when(mapper.selectById("rn1")).thenReturn(runner);

        service.updateConfig("rn1", new UpdateRunnerWakeConfigRequest("aa-bb-cc-dd-ee-ff", "wake.example.cn", null));

        assertThat(runner.getWakeMac()).isEqualTo("AA:BB:CC:DD:EE:FF");
        assertThat(runner.getWakeHost()).isEqualTo("wake.example.cn");
        assertThat(runner.getWakePort()).isEqualTo(9);
        verify(mapper).updateById(runner);
    }

    @Test
    void rejectsInvalidMacBeforeSaving() {
        AiRunnerNodeEntity runner = new AiRunnerNodeEntity();
        when(mapper.selectById("rn1")).thenReturn(runner);

        assertThatThrownBy(() -> service.updateConfig("rn1",
                new UpdateRunnerWakeConfigRequest("not-a-mac", "255.255.255.255", 9)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("MAC");
    }
}
