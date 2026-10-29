package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Moka 员工端考勤读取客户端；接管会话须由用户单次确认，不记录或返回密码。 */
@Component
public class MokaAttendanceClient {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final ObjectMapper json;
    private final HttpClient http;
    private final String passportUrl;
    private final String coreUrl;

    @Autowired
    public MokaAttendanceClient(ObjectMapper json,
            @Value("${wao.attendance.moka.passport-url:https://passport.mokahr.com}") String passportUrl,
            @Value("${wao.attendance.moka.core-url:https://core.mokahr.com}") String coreUrl) {
        this(json, passportUrl, coreUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    MokaAttendanceClient(ObjectMapper json, String passportUrl, String coreUrl, HttpClient http) {
        this.json = json; this.passportUrl = strip(passportUrl); this.coreUrl = strip(coreUrl); this.http = http;
    }

    /** 使用邮箱密码获得员工端会话；遇到验证码或强制登录要求时停止并交给用户处理。 */
    public Session login(String username, String password) {
        return login(username, password, false);
    }

    /** 仅本次手动连接可确认接管已有会话；使用 Moka 返回的 rid 完成一次确认。 */
    public Session login(String username, String password, boolean confirmSessionReplacement) {
        ObjectNode request = json.createObjectNode().put("email", username).put("password", password)
                .put("isMobile", false).put("type", "login").put("bus", 20);
        String loginUrl = passportUrl + "/api/outer/moka-unified-account/mokaUid/login";
        JsonNode login = post(loginUrl, request, null);
        JsonNode data = data(login);
        int step = data.path("step").asInt(-1);
        if (step == 4 && confirmSessionReplacement) {
            String rid = text(data, "rid");
            if (!StringUtils.hasText(rid)) throw new AuthenticationRequiredException("Moka 未返回登录确认凭据，请稍后重试");
            data = data(post(loginUrl, request.put("rid", rid).put("force", true), null));
            step = data.path("step").asInt(-1);
        }
        if (step == 3) throw new AuthenticationRequiredException("Moka 要求验证码，请先在浏览器完成一次登录");
        if (step == 4) throw new SessionConfirmationRequiredException();
        if (step != 0) {
            String error = text(data, "errorMsg", "message", "msg");
            throw new AuthenticationRequiredException(StringUtils.hasText(error) ? error : "Moka 邮箱或密码不正确");
        }
        String ticket = text(data, "ticket");
        if (!StringUtils.hasText(ticket)) throw new AuthenticationRequiredException("Moka 登录未返回会话票据");
        JsonNode unified = post(coreUrl + "/api/outer/moka-unified-account/mokaUid/uniLogin",
                json.createObjectNode().put("bus", 20).put("ticket", ticket).put("isMobile", false), null);
        JsonNode session = data(unified);
        String uid = text(session, "moka-uid", "mokaUid");
        String jwt = text(session, "moka-jwt", "mokaJwt");
        if (!StringUtils.hasText(jwt)) throw new AuthenticationRequiredException("Moka 登录未返回有效会话");
        return new Session(uid, jwt);
    }

    /** 校验会话并解析当前员工 id。 */
    public String employeeId(Session session) {
        JsonNode payload = data(post(coreUrl + "/api/organization/v1/hr/personal/getUserInfo",
                json.createObjectNode(), session));
        String value = findText(payload, Set.of("employeeId", "employee_id"));
        if (!StringUtils.hasText(value)) value = text(payload, "id");
        if (!StringUtils.hasText(value)) throw new RemoteException("Moka 当前账号没有可用的员工身份");
        return value;
    }

    /** 读取指定日期的最小考勤事实。 */
    public RemoteDay day(Session session, String employeeId, LocalDate date) {
        JsonNode body = json.createObjectNode().put("year", date.getYear()).put("month", date.getMonthValue())
                .put("startDate", date.toString()).put("endDate", date.toString())
                .put("fieldVisiblePageEnum", "VISIBLE_DAY").put("employeeId", employeeId);
        JsonNode payload = data(post(coreUrl + "/api/abs/attendance/myAttendance/v2/m/detail", body, session));
        JsonNode row = firstObject(payload);
        LocalDateTime in = findTime(row, date, Set.of("clockInTime", "clockInAt"), true);
        LocalDateTime out = findTime(row, date, Set.of("clockOutTime", "clockOutAt"), false);
        if (in != null && out != null && !out.isAfter(in)) out = out.plusDays(1);
        String raw = row == null ? "" : row.toString();
        String dayType = raw.contains("leaveRecord") && hasNonEmptyField(row, "leaveRecord") ? "LEAVE" : "WORKDAY";
        Set<String> anomalies = new LinkedHashSet<>();
        addFlag(row, anomalies, "late", "迟到"); addFlag(row, anomalies, "leaveEarly", "早退");
        addFlag(row, anomalies, "absent", "旷工"); addFlag(row, anomalies, "lackWorkTime", "工时不足");
        return new RemoteDay(date, in, out, dayType, String.join("、", anomalies));
    }

    private JsonNode post(String url, JsonNode body, Session session) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Accept", "application/json")
                .header("Origin", coreUrl).header("Referer", coreUrl + "/")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (session != null) {
            builder.header("Cookie", "moka-jwt=" + session.jwt() + "; moka-uid=" + safe(session.uid()))
                    .header("moka-jwt", session.jwt());
            if (StringUtils.hasText(session.uid())) builder.header("moka-uid", session.uid());
        }
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 401 || response.statusCode() == 403) throw new SessionExpiredException();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RemoteException("Moka 服务返回 HTTP " + response.statusCode());
            }
            JsonNode value = json.readTree(response.body());
            if (value.path("success").isBoolean() && !value.path("success").asBoolean()) {
                String message = text(value, "msg", "message", "errorMsg");
                if (looksUnauthorized(message)) throw new SessionExpiredException();
                throw new RemoteException(StringUtils.hasText(message) ? message : "Moka 返回失败");
            }
            int code = value.path("code").asInt(0);
            if (code == 401 || code == 403) throw new SessionExpiredException();
            if (code != 0 && code != 200) {
                String message=text(value,"msg","message","errorMsg");
                if(looksUnauthorized(message)) throw new SessionExpiredException();
                throw new RemoteException(StringUtils.hasText(message)?message:"Moka 返回错误码 "+code);
            }
            return value;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new RemoteException("Moka 请求被中断");
        } catch (IOException ex) {
            throw new RemoteException("连接 Moka 失败：" + ex.getMessage());
        }
    }

    private JsonNode data(JsonNode value) { return value.has("data") ? value.path("data") : value; }
    private JsonNode firstObject(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return null;
        if (value.isArray()) return value.isEmpty() ? null : value.get(0);
        for (String key : List.of("list", "records", "rows", "detail")) {
            JsonNode nested = value.path(key); if (nested.isArray() && !nested.isEmpty()) return nested.get(0);
        }
        return value;
    }
    private LocalDateTime findTime(JsonNode node, LocalDate date, Set<String> names, boolean first) {
        List<String> values = new ArrayList<>(); collectValues(node, names, values);
        List<LocalDateTime> parsed = values.stream().map(value -> parseTime(value, date)).filter(java.util.Objects::nonNull).sorted().toList();
        return parsed.isEmpty() ? null : parsed.get(first ? 0 : parsed.size() - 1);
    }
    private void collectValues(JsonNode node, Set<String> names, List<String> values) {
        if (node == null) return;
        if (node.isObject()) {
            Iterator<String> fields = node.fieldNames();
            while (fields.hasNext()) { String field = fields.next(); JsonNode value = node.get(field);
                if (names.contains(field) && value.isValueNode()) values.add(value.asText());
                collectValues(value, names, values);
            }
        } else if (node.isArray()) node.forEach(value -> collectValues(value, names, values));
    }
    private LocalDateTime parseTime(String value, LocalDate date) {
        if (!StringUtils.hasText(value) || "null".equals(value)) return null;
        try { long epoch = Long.parseLong(value); if (value.length() <= 10) epoch *= 1000; return LocalDateTime.ofInstant(Instant.ofEpochMilli(epoch), ZONE); }
        catch (NumberFormatException ignored) { }
        for (DateTimeFormatter format : List.of(DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))) {
            try { return LocalDateTime.parse(value, format); } catch (DateTimeParseException ignored) { }
        }
        try { return date.atTime(LocalTime.parse(value.length() >= 5 ? value.substring(0, 5) : value)); }
        catch (RuntimeException ignored) { return null; }
    }
    private boolean hasNonEmptyField(JsonNode node, String name) {
        if (node == null) return false;
        if (node.isObject()) {
            JsonNode direct=node.get(name); if (direct!=null&&!direct.isNull()&&(!direct.isContainerNode()||direct.size()>0)) return true;
            Iterator<JsonNode> it=node.elements(); while(it.hasNext()) if(hasNonEmptyField(it.next(),name)) return true;
        } else if (node.isArray()) for(JsonNode child:node) if(hasNonEmptyField(child,name)) return true;
        return false;
    }
    private void addFlag(JsonNode node, Set<String> target, String key, String label) { if (findBoolean(node, key)) target.add(label); }
    private boolean findBoolean(JsonNode node, String key) {
        if (node == null) return false; if (node.isObject()) { if (node.path(key).asBoolean(false)) return true;
            Iterator<JsonNode> it = node.elements(); while (it.hasNext()) if (findBoolean(it.next(), key)) return true;
        } else if (node.isArray()) for (JsonNode child : node) if (findBoolean(child, key)) return true; return false;
    }
    private String findText(JsonNode node, Set<String> names) {
        if (node == null) return null; if (node.isObject()) { for (String name : names) { String found = text(node, name); if (StringUtils.hasText(found)) return found; }
            Iterator<JsonNode> it = node.elements(); while (it.hasNext()) { String found = findText(it.next(), names); if (StringUtils.hasText(found)) return found; }
        } else if (node.isArray()) for (JsonNode child : node) { String found = findText(child, names); if (StringUtils.hasText(found)) return found; } return null;
    }
    private static String text(JsonNode node, String... fields) { if (node == null) return null; for (String field : fields) { JsonNode value=node.path(field); if (value.isValueNode() && !value.asText().isBlank()) return value.asText(); } return null; }
    private static boolean looksUnauthorized(String value) { return value != null && (value.contains("登录") || value.toLowerCase().contains("unauthorized")); }
    private static String strip(String value) { return value.endsWith("/") ? value.substring(0, value.length()-1) : value; }
    private static String safe(String value) { return value == null ? "" : value; }

    public record Session(String uid, String jwt) { }
    public record RemoteDay(LocalDate date, LocalDateTime clockInAt, LocalDateTime clockOutAt, String dayType, String anomaly) { }
    public static class SessionExpiredException extends RuntimeException { }
    public static class AuthenticationRequiredException extends BadRequestException { public AuthenticationRequiredException(String message){super(message);} }
    public static class SessionConfirmationRequiredException extends AuthenticationRequiredException {
        public SessionConfirmationRequiredException(){super("Moka 检测到其他会话；WAO 不会自动接管，请点击重新同步并确认连接，或先退出其他设备上的 Moka");}
    }
    public static class RemoteException extends BadRequestException { public RemoteException(String message){super(message);} }
}
