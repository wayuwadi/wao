package cn.wayuwa.wao.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;

class WeKnoraSourceServiceTest {
    @Test void preservesLongUnicodeAndStableHashesWithoutExportTime() {
        String text="真实来源🌏".repeat(6000);
        var a=WeKnoraSourceService.parts("docs","d1","长文","",text);
        var b=WeKnoraSourceService.parts("docs","d1","长文","",text);
        assertEquals(a,b);assertTrue(a.size()>2);
        String joined=a.stream().map(s->s.content().substring(s.content().indexOf("\n\n",s.content().indexOf("同步标识："))+2)).reduce("",String::concat);
        assertEquals(text,joined);
        assertTrue(a.stream().allMatch(s->s.url().contains("tab=sync&source=")));
    }
    @Test void rejectsUnknownTablesAndPrivateCredentialsFromStructuredContent() {
        var service=new WeKnoraSourceService(mock(JdbcTemplate.class),new ObjectMapper());
        assertThrows(IllegalArgumentException.class,()->WeKnoraSourceService.validateModules(List.of("account")));
        String rendered=service.render("{\"content\":[{\"type\":\"paragraph\",\"text\":\"可检索内容\"}],\"apiKey\":\"SECRET\",\"password\":\"PRIVATE\"}");
        assertTrue(rendered.contains("可检索内容"));assertFalse(rendered.contains("SECRET"));assertFalse(rendered.contains("PRIVATE"));
        assertFalse(service.render("<script>credential()</script><h1>标题</h1>").contains("credential"));
    }
    @Test void sourceAndTargetIdentityAreStableAcrossTitleEdits() {
        var old=WeKnoraSourceService.parts("docs","d1","旧名","","正文").get(0);
        var edited=WeKnoraSourceService.parts("docs","d1","新名","","正文").get(0);
        assertEquals(old.key(),edited.key());
        assertEquals(WeKnoraSyncService.marker(old.title()),WeKnoraSyncService.marker(edited.title()));
        assertNotEquals(old.hash(),edited.hash());
        assertEquals("",WeKnoraSyncService.marker("手工资料"));
    }
    @Test void privateDefinitionsRequireBoundOwnerAndNoSelectStarOrSecretTables() {
        for(var d:WeKnoraSourceService.DEFINITIONS) {
            assertFalse(d.select().contains("*"));
            if(d.personal())assertTrue(d.where().contains("?"),d.id());
            assertFalse(d.select().matches("(?i).*(password|cipher|api_key|share_token|package_content|file_content).*"),d.id());
        }
    }
    @Test void masksRecognizableTokensAndSignedSourceLinks() {
        String result=WeKnoraSourceService.redact("Bearer abcdefghijklmnop sk-0123456789abcdefghijk https://example.test/?token=private-value&ok=yes");
        assertFalse(result.contains("abcdefghijklmnop"));assertFalse(result.contains("private-value"));assertTrue(result.contains("ok=yes"));
    }
}
