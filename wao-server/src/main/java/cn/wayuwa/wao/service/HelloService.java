package cn.wayuwa.wao.service;

import java.time.Clock;
import java.time.OffsetDateTime;

import cn.wayuwa.wao.dto.HelloData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class HelloService {

    private final String serviceName;
    private final Clock clock;

    @Autowired
    public HelloService(@Value("${spring.application.name}") String serviceName) {
        this(serviceName, Clock.systemDefaultZone());
    }

    HelloService(String serviceName, Clock clock) {
        this.serviceName = serviceName;
        this.clock = clock;
    }

    /**
     * Builds the deterministic Phase 0 payload used to verify the request chain.
     *
     * @return current service identity and server time
     */
    public HelloData hello() {
        return new HelloData("Hello from WAO", serviceName, OffsetDateTime.now(clock));
    }
}
