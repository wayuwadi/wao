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
    @Test void acceptsWorkbookSnapshotButRejectsOversizedGeometryAndMissingSheets() throws Exception {
        var root = json.readTree("{\"type\":\"waoWorkbook\",\"attrs\":{\"snapshot\":{\"id\":\"book\",\"sheetOrder\":[\"s1\"],\"sheets\":{\"s1\":{\"id\":\"s1\",\"name\":\"Sheet1\",\"rowCount\":1000,\"columnCount\":26,\"cellData\":{\"0\":{\"0\":{\"v\":14,\"f\":\"=7*2\",\"s\":\"bold\"}}}}},\"styles\":{\"bold\":{\"bl\":1}}}}}");
        var content = new RichDocContentDto(1, root, "14");
        assertThatCode(() -> KnowledgeSheetContent.validate(content)).doesNotThrowAnyException();
        var sheet = (com.fasterxml.jackson.databind.node.ObjectNode) root.path("attrs").path("snapshot").path("sheets").path("s1");
        sheet.put("rowCount", 100001);
        assertThatThrownBy(() -> KnowledgeSheetContent.validate(content)).isInstanceOf(BadRequestException.class);
        sheet.put("rowCount", 1000); sheet.put("id", "wrong");
        assertThatThrownBy(() -> KnowledgeSheetContent.validate(content)).isInstanceOf(BadRequestException.class);
    }

}
