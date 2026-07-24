package cn.wayuwa.wao.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void preservesIncomingRequestId() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/hello")
                .header(RequestIdFilter.REQUEST_ID_HEADER, "request-123"));
        var forwardedExchange = new AtomicReference<ServerWebExchange>();

        filter.filter(exchange, current -> {
            forwardedExchange.set(current);
            return current.getResponse().setComplete();
        }).block();

        assertThat(forwardedExchange.get().getRequest().getHeaders()
                .getFirst(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("request-123");
        assertThat(exchange.getResponse().getHeaders()
                .getFirst(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("request-123");
    }
}
