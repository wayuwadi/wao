package cn.wayuwa.wao.service;

import java.util.List;

import cn.wayuwa.wao.common.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaldavClientTest {

    private final CaldavClient client = new CaldavClient();

    @Test
    void 规范化服务器地址只接受http和https() {
        assertThat(client.normalizeServerUrl(" https://caldav.example.com ")).isEqualTo("https://caldav.example.com");
        assertThat(client.normalizeServerUrl("caldav.wecom.work")).isEqualTo("https://caldav.wecom.work");
        assertThat(client.normalizeServerUrl("calendar.example.com/dav/user"))
                .isEqualTo("https://calendar.example.com/dav/user");
        assertThatThrownBy(() -> client.normalizeServerUrl("")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> client.normalizeServerUrl("ftp://x.example.com")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void 解析日历列表只保留VEVENT集合并取名称与颜色() {
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"
                    xmlns:a="http://apple.com/ns/ical/">
                  <d:response>
                    <d:href>/dav/cal/home/</d:href>
                    <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/dav/cal/home/family/</d:href>
                    <d:propstat><d:prop>
                      <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                      <d:displayname>家庭日历</d:displayname>
                      <a:calendar-color>#0E8C8CFF</a:calendar-color>
                      <c:supported-calendar-component-set><c:comp name="VEVENT"/></c:supported-calendar-component-set>
                    </d:prop></d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/dav/cal/home/todos/</d:href>
                    <d:propstat><d:prop>
                      <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                      <d:displayname>纯任务</d:displayname>
                      <c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set>
                    </d:prop></d:propstat>
                  </d:response>
                </d:multistatus>
                """;
        List<CaldavClient.RemoteCalendar> cals = CaldavClient.parseCalendars(xml, "https://dav.example.com");
        assertThat(cals).hasSize(1);
        assertThat(cals.get(0).href()).isEqualTo("https://dav.example.com/dav/cal/home/family/");
        assertThat(cals.get(0).name()).isEqualTo("家庭日历");
        assertThat(cals.get(0).color()).isEqualTo("#0E8C8C");
    }

    @Test
    void 解析REPORT结果取出每条iCalendar原文() {
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  <d:response><d:propstat><d:prop>
                    <c:calendar-data>BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:x1
                END:VEVENT
                END:VCALENDAR</c:calendar-data>
                  </d:prop></d:propstat></d:response>
                </d:multistatus>
                """;
        List<String> data = CaldavClient.parseCalendarData(xml);
        assertThat(data).hasSize(1);
        assertThat(data.get(0)).contains("UID:x1");
    }

    @Test
    void 相对href按服务器根解析() {
        assertThat(CaldavClient.resolve("https://dav.example.com/base/", "/cal/a/"))
                .isEqualTo("https://dav.example.com/cal/a/");
        assertThat(CaldavClient.parseFirstHref("""
                <?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:">
                  <d:response><d:propstat><d:prop>
                    <d:current-user-principal><d:href>/principals/wg/</d:href></d:current-user-principal>
                  </d:prop></d:propstat></d:response>
                </d:multistatus>
                """, "DAV:", "current-user-principal")).isEqualTo("/principals/wg/");
    }
}
