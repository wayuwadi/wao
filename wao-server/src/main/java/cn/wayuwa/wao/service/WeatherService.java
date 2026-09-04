package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;

/** 固定上游天气代理；只传城市关键词或粗粒度坐标，不传成员身份。 */
@Service
public class WeatherService {
    public record Location(String name, double latitude, double longitude) { }
    public record Day(String date, Integer code, Double low, Double high, Double rain, Double wind) { }
    public record Forecast(String fetchedAt, List<Day> days) { }
    private record Cached(Instant at, JsonNode data) { }
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final Map<String, Cached> cache = Collections.synchronizedMap(new LinkedHashMap<>());
    public WeatherService(ObjectMapper json) { this.json = json; }

    /** 搜索真实城市并保留行政区，避免重名城市被自动选中。 */
    public List<Location> locations(String query) {
        String value = query == null ? "" : query.trim();
        if (value.length() < 2 || value.length() > 80) throw new BadRequestException("请输入 2–80 个字符的城市名");
        JsonNode root = request("https://geocoding-api.open-meteo.com/v1/search?count=8&language=zh&name="
                + URLEncoder.encode(value, StandardCharsets.UTF_8));
        List<Location> result = new ArrayList<>();
        for (JsonNode row : root.path("results")) {
            if (!row.path("latitude").isNumber() || !row.path("longitude").isNumber()) continue;
            Set<String> parts = new LinkedHashSet<>();
            for (String field : List.of("name", "admin1", "country")) if (!row.path(field).asText().isBlank()) parts.add(row.path(field).asText());
            result.add(new Location(String.join(" · ", parts), row.path("latitude").asDouble(), row.path("longitude").asDouble()));
        }
        return result;
    }

    /** 获取按 WAO 上海时区划分的七日预报；缺少字段时保留未知值。 */
    public Forecast forecast(double latitude, double longitude) {
        validateCoordinates(latitude, longitude);
        String url = String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&timezone=Asia%%2FShanghai&forecast_days=7&daily=weather_code,temperature_2m_min,temperature_2m_max,precipitation_probability_max,wind_speed_10m_max", latitude, longitude);
        JsonNode root = request(url);
        return parseForecast(root, root.path("wao_fetched_at").asText());
    }

    static void validateCoordinates(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || Math.abs(latitude) > 90 || Math.abs(longitude) > 180)
            throw new BadRequestException("定位坐标无效，请重新选择城市");
    }

    static Forecast parseForecast(JsonNode root, String fetchedAt) {
        JsonNode daily = root.path("daily");
        List<Day> days = new ArrayList<>();
        for (int i = 0; i < Math.min(7, daily.path("time").size()); i++) {
            String date = LocalDate.parse(daily.path("time").get(i).asText()).toString();
            Double code = number(daily, "weather_code", i);
            days.add(new Day(date, code == null ? null : code.intValue(), number(daily, "temperature_2m_min", i),
                    number(daily, "temperature_2m_max", i), number(daily, "precipitation_probability_max", i), number(daily, "wind_speed_10m_max", i)));
        }
        if (days.isEmpty()) throw new BadRequestException("天气源暂时没有返回预报，请稍后重试");
        return new Forecast(fetchedAt, days);
    }

    private static Double number(JsonNode node, String field, int i) {
        JsonNode value = node.path(field).path(i);
        return value.isNumber() && Double.isFinite(value.asDouble()) ? value.asDouble() : null;
    }

    private JsonNode request(String url) {
        Instant now = Instant.now();
        Cached hit = cache.get(url);
        if (hit != null && Duration.between(hit.at(), now).toMinutes() < 30) return hit.data();
        try {
            var response = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("weather unavailable");
            JsonNode root = json.readTree(response.body());
            if (!root.isObject() || root.path("error").asBoolean()) throw new IllegalStateException("weather unavailable");
            ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("wao_fetched_at", now.toString());
            if (url.startsWith("https://api.open-meteo.com/")) parseForecast(root, now.toString());
            synchronized (cache) {
                cache.entrySet().removeIf(entry -> Duration.between(entry.getValue().at(), now).toMinutes() >= 30);
                if (cache.size() >= 256) cache.remove(cache.keySet().iterator().next());
                cache.put(url, new Cached(now, root));
            }
            return root;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("天气请求被中断，请重试");
        } catch (Exception error) {
            throw new BadRequestException("天气服务暂时不可用，请稍后重试或手动切换城市");
        }
    }
}
