package cn.wayuwa.wao.service;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import cn.wayuwa.wao.common.BadRequestException;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * CalDAV 协议客户端（RFC 4791）：账户发现与事件拉取。
 *
 * <p>用 JDK {@link HttpClient} 而不是 RestClient，因为 CalDAV 依赖 PROPFIND / REPORT
 * 这类 WebDAV 扩展方法，默认的 HttpURLConnection 工厂不支持自定义方法。
 *
 * <p>发现流程：{@code current-user-principal} → {@code calendar-home-set} → Depth:1 列出
 * 日历集合；对不支持 principal 发现的服务器，回退为把用户给的地址直接当 home 集合列。
 */
@Component
public class CaldavClient {

    private static final String NS_DAV = "DAV:";
    private static final String NS_CALDAV = "urn:ietf:params:xml:ns:caldav";
    private static final String NS_APPLE = "http://apple.com/ns/ical/";
    private static final DateTimeFormatter UTC_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT);

    /** 服务器上的一个日历集合。 */
    public record RemoteCalendar(String href, String name, String color) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 校验并规范化服务器地址：省略协议时补 HTTPS，最终必须是 http/https 且带主机名。 */
    public String normalizeServerUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!StringUtils.hasText(value)) throw new BadRequestException("请填写 CalDAV 服务器地址");
        // 企业微信等服务商给用户展示的是 caldav.wecom.work 这类“服务器”值，
        // 而不是完整 URL。CalDAV 凭据不应走明文链路，因此缺省协议统一补 HTTPS。
        if (!value.contains("://")) value = "https://" + value;
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException ex) {
            throw new BadRequestException("CalDAV 服务器地址格式不正确");
        }
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || !StringUtils.hasText(uri.getHost())) {
            throw new BadRequestException("CalDAV 服务器地址必须是 HTTP 或 HTTPS 地址");
        }
        return value;
    }

    /** 发现账户下的全部日历集合；无法发现或凭据不对时抛出带原因的 {@link BadRequestException}。 */
    public List<RemoteCalendar> discoverCalendars(String serverUrl, String username, String password) {
        String base = normalizeServerUrl(serverUrl);
        String home = null;
        try {
            String principal = propfindHref(base, username, password, principalBody(), NS_DAV, "current-user-principal");
            if (principal != null) {
                home = propfindHref(resolve(base, principal), username, password,
                        homeSetBody(), NS_CALDAV, "calendar-home-set");
            }
        } catch (BadRequestException ex) {
            throw ex;
        } catch (Exception ignored) {
            // 服务器不支持 principal 发现时走回退路径
        }
        String target = home != null ? resolve(base, home) : base;
        String xml = request("PROPFIND", target, username, password, "1", calendarListBody());
        List<RemoteCalendar> calendars = parseCalendars(xml, target);
        if (calendars.isEmpty()) {
            throw new BadRequestException("在该地址下没有发现任何日历，请确认填写的是 CalDAV 地址");
        }
        return calendars;
    }

    /** REPORT calendar-query 拉取时间窗内的事件，返回逐条 iCalendar 原文。 */
    public List<String> fetchCalendarData(String calendarHref, String username, String password,
            ZonedDateTime from, ZonedDateTime to) {
        String body = """
                <?xml version="1.0" encoding="utf-8"?>
                <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  <d:prop><d:getetag/><c:calendar-data/></d:prop>
                  <c:filter>
                    <c:comp-filter name="VCALENDAR">
                      <c:comp-filter name="VEVENT">
                        <c:time-range start="%s" end="%s"/>
                      </c:comp-filter>
                    </c:comp-filter>
                  </c:filter>
                </c:calendar-query>
                """.formatted(UTC_STAMP.format(from.withZoneSameInstant(java.time.ZoneOffset.UTC)),
                UTC_STAMP.format(to.withZoneSameInstant(java.time.ZoneOffset.UTC)));
        String xml = request("REPORT", calendarHref, username, password, "1", body);
        return parseCalendarData(xml);
    }

    // ---- HTTP ----

    private String request(String method, String url, String username, String password, String depth, String body) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Authorization", basicAuth(username, password))
                .header("Depth", depth)
                .header("Content-Type", "application/xml; charset=utf-8")
                .timeout(Duration.ofSeconds(20))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new BadRequestException("连接 CalDAV 服务器失败：" + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("连接 CalDAV 服务器被中断");
        }
        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            throw new BadRequestException("CalDAV 账号或密码不正确（部分服务商需使用应用专用密码）");
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new BadRequestException("CalDAV 服务器返回 " + resp.statusCode() + "，请检查地址是否正确");
        }
        return resp.body();
    }

    private String propfindHref(String url, String username, String password,
            String body, String ns, String tag) {
        String xml = request("PROPFIND", url, username, password, "0", body);
        return parseFirstHref(xml, ns, tag);
    }

    private static String basicAuth(String username, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static String principalBody() {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:"><d:prop><d:current-user-principal/></d:prop></d:propfind>
                """;
    }

    private static String homeSetBody() {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  <d:prop><c:calendar-home-set/></d:prop>
                </d:propfind>
                """;
    }

    private static String calendarListBody() {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/">
                  <d:prop>
                    <d:resourcetype/><d:displayname/><a:calendar-color/>
                    <c:supported-calendar-component-set/>
                  </d:prop>
                </d:propfind>
                """;
    }

    // ---- XML 解析（包内可见，便于用固定报文做单元测试） ----

    /** 取 multistatus 里第一个 {@code <ns:tag><d:href>} 的地址。 */
    static String parseFirstHref(String xml, String ns, String tag) {
        Document doc = parseXml(xml);
        NodeList nodes = doc.getElementsByTagNameNS(ns, tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            NodeList hrefs = ((Element) nodes.item(i)).getElementsByTagNameNS(NS_DAV, "href");
            if (hrefs.getLength() > 0) {
                String href = hrefs.item(0).getTextContent().trim();
                if (StringUtils.hasText(href)) return href;
            }
        }
        return null;
    }

    /** 解析 PROPFIND Depth:1 结果，取出支持 VEVENT 的日历集合。 */
    static List<RemoteCalendar> parseCalendars(String xml, String baseUrl) {
        Document doc = parseXml(xml);
        List<RemoteCalendar> out = new ArrayList<>();
        NodeList responses = doc.getElementsByTagNameNS(NS_DAV, "response");
        for (int i = 0; i < responses.getLength(); i++) {
            Element resp = (Element) responses.item(i);
            NodeList hrefs = resp.getElementsByTagNameNS(NS_DAV, "href");
            if (hrefs.getLength() == 0) continue;
            NodeList types = resp.getElementsByTagNameNS(NS_DAV, "resourcetype");
            if (types.getLength() == 0
                    || ((Element) types.item(0)).getElementsByTagNameNS(NS_CALDAV, "calendar").getLength() == 0) {
                continue;
            }
            // 声明了组件集合却不含 VEVENT 的（纯任务/日记集合）跳过；没声明的按支持处理
            NodeList compSets = resp.getElementsByTagNameNS(NS_CALDAV, "supported-calendar-component-set");
            if (compSets.getLength() > 0) {
                NodeList comps = ((Element) compSets.item(0)).getElementsByTagNameNS(NS_CALDAV, "comp");
                boolean vevent = comps.getLength() == 0;
                for (int j = 0; j < comps.getLength(); j++) {
                    if ("VEVENT".equalsIgnoreCase(((Element) comps.item(j)).getAttribute("name"))) vevent = true;
                }
                if (!vevent) continue;
            }
            String href = resolve(baseUrl, hrefs.item(0).getTextContent().trim());
            NodeList names = resp.getElementsByTagNameNS(NS_DAV, "displayname");
            String name = names.getLength() > 0 ? names.item(0).getTextContent().trim() : "";
            if (!StringUtils.hasText(name)) name = lastSegment(href);
            NodeList colors = resp.getElementsByTagNameNS(NS_APPLE, "calendar-color");
            String color = colors.getLength() > 0 ? normalizeColor(colors.item(0).getTextContent().trim()) : null;
            out.add(new RemoteCalendar(href, name, color));
        }
        return out;
    }

    /** 从 REPORT 结果中取出每条 calendar-data 的 iCalendar 原文。 */
    static List<String> parseCalendarData(String xml) {
        Document doc = parseXml(xml);
        List<String> out = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagNameNS(NS_CALDAV, "calendar-data");
        for (int i = 0; i < nodes.getLength(); i++) {
            String ics = nodes.item(i).getTextContent();
            if (StringUtils.hasText(ics)) out.add(ics);
        }
        return out;
    }

    static String resolve(String baseUrl, String href) {
        if (!StringUtils.hasText(href)) return baseUrl;
        return URI.create(baseUrl).resolve(href).toString();
    }

    private static String lastSegment(String href) {
        String path = URI.create(href).getPath();
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (StringUtils.hasText(parts[i])) return parts[i];
        }
        return "日历";
    }

    /** Apple 的 calendar-color 常带透明度（#RRGGBBAA），裁到 #RRGGBB。 */
    private static String normalizeColor(String color) {
        if (color != null && color.matches("#[0-9a-fA-F]{8}")) return color.substring(0, 7);
        return StringUtils.hasText(color) ? color : null;
    }

    private static Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception ex) {
            throw new BadRequestException("CalDAV 服务器响应不是有效的 XML：" + ex.getMessage());
        }
    }
}
