package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.mapper.DocMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AiAssistantKnowledgeServiceTest {

    private final SettingService settings = mock(SettingService.class);
    private final DocMapper docMapper = mock(DocMapper.class);
    private final AiAssistantKnowledgeService service = new AiAssistantKnowledgeService(
            settings, docMapper, new ObjectMapper());

    @Test
    void returnsEmptyContextWhenChatSyncKnowledgeBaseIsNotConfigured() {
        when(settings.get(SettingService.CHAT_SYNC_KB_ID, "")).thenReturn("");

        assertThat(service.relevantChatRecords("之前工资是几月开始？")).isEmpty();
    }

    @Test
    void ranksMatchingChatDocumentAndIncludesTraceableSource() {
        when(settings.get(SettingService.CHAT_SYNC_KB_ID, "")).thenReturn("kb-chat");
        DocEntity salary = doc("d1", "飞书群聊 · 2026-07-02",
                "[{\"h\":\"工资安排\"},{\"p\":\"从 2026 年 11 月开始，每个月拿出 5000 元。\"}]");
        DocEntity grocery = doc("d2", "飞书群聊 · 2026-07-03",
                "[{\"p\":\"今天下班记得买牛奶。\"}]");
        when(docMapper.selectList(any())).thenReturn(List.of(grocery, salary));

        String context = service.relevantChatRecords("之前工资是几月开始？");

        assertThat(context).contains("飞书群聊 · 2026-07-02")
                .contains("2026 年 11 月开始")
                .contains("/kb/kb-chat?doc=d1")
                .contains("参考聊天记录");
        assertThat(context).doesNotContain("飞书群聊 · 2026-07-03");
    }

    private DocEntity doc(String id, String title, String blocks) {
        DocEntity doc = new DocEntity();
        doc.setId(id);
        doc.setKbId("kb-chat");
        doc.setTitle(title);
        doc.setAuto(true);
        doc.setBlocksJson(blocks);
        doc.setUpdatedAt(LocalDateTime.now());
        return doc;
    }
}
