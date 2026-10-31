package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MokaAttendanceClientTest {
    private HttpServer server; private String base;
    @BeforeEach void start() throws Exception {server=HttpServer.create(new InetSocketAddress(0),0);server.start();base="http://127.0.0.1:"+server.getAddress().getPort();}
    @AfterEach void stop(){server.stop(0);}

    @Test void loginNeverSendsForceAndStopsWhenMokaRequestsIt() {
        AtomicReference<String> body=new AtomicReference<>();server.createContext("/api/outer/moka-unified-account/mokaUid/login",exchange->{body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));reply(exchange,"{\"code\":0,\"data\":{\"step\":4}}");});
        MokaAttendanceClient client=client();
        assertThatThrownBy(()->client.login("me@example.com","secret")).isInstanceOf(MokaAttendanceClient.SessionConfirmationRequiredException.class).hasMessageContaining("不会自动接管");
        assertThat(body.get()).doesNotContain("force");
    }

    @Test void confirmedLoginUsesFreshRidOnlyAfterMokaRequestsConfirmation() {
        java.util.List<String> requests = new java.util.ArrayList<>();
        server.createContext("/api/outer/moka-unified-account/mokaUid/login", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, requests.size() == 1
                    ? "{\"code\":0,\"data\":{\"step\":4,\"rid\":\"confirmation-1\"}}"
                    : "{\"code\":0,\"data\":{\"step\":0,\"ticket\":\"ticket-1\"}}");
        });
        server.createContext("/api/outer/moka-unified-account/mokaUid/uniLogin", exchange -> reply(exchange,
                "{\"code\":0,\"data\":{\"moka-uid\":\"uid-1\",\"moka-jwt\":\"jwt-1\"}}"));
        assertThat(client().login("me@example.com", "secret", true)).isEqualTo(new MokaAttendanceClient.Session("uid-1", "jwt-1"));
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0)).doesNotContain("force", "rid");
        assertThat(requests.get(1)).contains("\"force\":true", "\"rid\":\"confirmation-1\"");
    }

    @Test void confirmedLoginStillStopsForCaptchaAndDoesNotRetryMissingRid() {
        AtomicReference<String> response = new AtomicReference<>("{\"code\":0,\"data\":{\"step\":3}}");
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/api/outer/moka-unified-account/mokaUid/login", exchange -> { calls.incrementAndGet(); reply(exchange, response.get()); });
        assertThatThrownBy(() -> client().login("me@example.com", "secret", true)).hasMessageContaining("验证码");
        response.set("{\"code\":0,\"data\":{\"step\":4}}");
        assertThatThrownBy(() -> client().login("me@example.com", "secret", true)).hasMessageContaining("确认凭据");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test void confirmedLoginNeverLoopsWhenMokaStillRequiresConfirmation() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/api/outer/moka-unified-account/mokaUid/login", exchange -> {
            calls.incrementAndGet(); reply(exchange, "{\"code\":0,\"data\":{\"step\":4,\"rid\":\"confirmation-1\"}}");
        });
        assertThatThrownBy(() -> client().login("me@example.com", "secret", true)).isInstanceOf(MokaAttendanceClient.SessionConfirmationRequiredException.class);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test void parsesNestedPunchTimesAndAnomaly() {
        server.createContext("/api/abs/attendance/myAttendance/v2/m/detail",exchange->reply(exchange,"""
                {"code":200,"data":{"records":[{"detail":{"myAttendanceAttendanceRecord#clockInAndOutTime":{"fieldValue":[{"clockInTime":"2026-09-03 09:00:00","clockOutTime":"2026-09-03 21:00:00"}]},"late":true}}]}}
                """));
        MokaAttendanceClient.RemoteDay day=client().day(new MokaAttendanceClient.Session("u","j"),"e",LocalDate.of(2026,9,3));
        assertThat(day.clockInAt()).isEqualTo(LocalDateTime.of(2026,9,3,9,0));
        assertThat(day.clockOutAt()).isEqualTo(LocalDateTime.of(2026,9,3,21,0));
        assertThat(day.anomaly()).contains("迟到");
    }

    @Test void readsNamespacedLeaveValuesWithoutLosingPunchesOrUsingElapsedDuration() {
        server.createContext("/api/abs/attendance/myAttendance/v2/m/detail", exchange -> reply(exchange, """
            {"code":200,"data":{"detail":{
              "myAttendanceAttendanceRecord#leaveRecord":{"fieldLabel":"请假记录","fieldValue":[
                {"title":"事假","beginDate":"2026-09-03","beginTime":"09:00","endDate":"2026-09-03","endTime":"18:00","duration":"8.0","durationUnit":"小时","status":2},
                {"title":"年假","beginDate":"2026-09-02","beginTime":"09:00","endDate":"2026-09-04","endTime":"18:00","duration":"3","durationUnit":"天"},
                {"title":"过去请假","beginDate":"2026-09-02","beginTime":"09:00","endDate":"2026-09-03","endTime":"00:00"}
              ]},"clockInTime":"2026-09-03 18:30:00","clockOutTime":"2026-09-03 21:00:00"}}}
            """));
        var day = client().day(new MokaAttendanceClient.Session("u","j"),"e",LocalDate.of(2026,9,3));
        assertThat(day.dayType()).isEqualTo("LEAVE");
        assertThat(day.leaves()).hasSize(2);
        assertThat(day.leaves().get(0).duration()).isEqualByComparingTo("8");
        assertThat(day.leaves().get(1).durationUnit()).isEqualTo("天");
        assertThat(day.clockInAt()).isEqualTo(LocalDateTime.of(2026,9,3,18,30));
    }

    @Test void emptyLeaveWrapperAndMetadataDoNotMarkDayAsLeave() {
        AtomicReference<String> records = new AtomicReference<>("[]");
        server.createContext("/api/abs/attendance/myAttendance/v2/m/detail", exchange -> reply(exchange,
            "{\"code\":200,\"data\":{\"detail\":{\"myAttendanceAttendanceRecord#leaveRecord\":{\"fieldLabel\":\"请假记录\",\"fieldValue\":" + records.get() + "}}}}"));
        for (String value : java.util.List.of("[]", "null", "[{}]")) {
            records.set(value);
            var day = client().day(new MokaAttendanceClient.Session("u","j"),"e",LocalDate.of(2026,9,3));
            assertThat(day.dayType()).isEqualTo("WORKDAY");
            assertThat(day.leaves()).isEmpty();
        }
    }

    @Test void userInfoFailureCodeEntersSessionRecoveryEvenWithSuccessFalse() {
        server.createContext("/api/organization/v1/hr/personal/getUserInfo", exchange -> reply(exchange,
            "{\"code\":100007,\"success\":false,\"message\":\"获取用户信息失败\"}"));
        assertThatThrownBy(() -> client().employeeId(new MokaAttendanceClient.Session("u","j")))
            .isInstanceOf(MokaAttendanceClient.SessionExpiredException.class);
    }

    private MokaAttendanceClient client(){return new MokaAttendanceClient(new ObjectMapper(),base,base,HttpClient.newHttpClient());}
    private void reply(com.sun.net.httpserver.HttpExchange exchange,String body) throws java.io.IOException {byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();}
}
