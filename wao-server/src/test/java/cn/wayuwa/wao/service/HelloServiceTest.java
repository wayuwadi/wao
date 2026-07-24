package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class HelloServiceTest {

    @Test
    void returnsServiceIdentityAndServerTime() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-24T00:00:00Z"), ZoneOffset.UTC);
        HelloService service = new HelloService("wao-server", clock);

        var result = service.hello();

        assertThat(result.message()).isEqualTo("Hello from WAO");
        assertThat(result.service()).isEqualTo("wao-server");
        assertThat(result.serverTime()).isEqualTo(OffsetDateTime.parse("2026-07-24T00:00:00Z"));
    }
}
