package cn.wayuwa.wao.service;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.wayuwa.wao.dto.RichDocContentDto;
import cn.wayuwa.wao.common.BadRequestException;
import static org.assertj.core.api.Assertions.*;
class KnowledgeSheetContentTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void acceptsEmptySheetAndRejectsMalformedOrMergedCells() throws Exception {
        assertThatCode(() -> KnowledgeSheetContent.validate(KnowledgeSheetContent.empty(json))).doesNotThrowAnyException();
        var malformed = new RichDocContentDto(1, json.readTree("{\"type\":\"doc\",\"content\":{\"invalid\":1}}"), "");
        assertThatThrownBy(() -> KnowledgeSheetContent.validate(malformed)).isInstanceOf(BadRequestException.class);
        var content = KnowledgeSheetContent.empty(json);
        ((com.fasterxml.jackson.databind.node.ObjectNode) content.document().path("content").get(0).path("content").get(0).path("content").get(0)).putObject("attrs").put("colspan", 2);
        assertThatThrownBy(() -> KnowledgeSheetContent.validate(content)).isInstanceOf(BadRequestException.class);
    }
}
