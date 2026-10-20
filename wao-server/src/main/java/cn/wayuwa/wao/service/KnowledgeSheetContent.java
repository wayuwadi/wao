package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.dto.RichDocContentDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 表格页使用可导出、可搜索的普通表格节点；限制与浏览器一致。 */
final class KnowledgeSheetContent {
    static RichDocContentDto empty(ObjectMapper json) {
        var root = json.createObjectNode().put("type", "doc");
        var table = root.putArray("content").addObject().put("type", "table");
        var rows = table.putArray("content");
        for (int r = 0; r < 20; r++) {
            var cells = rows.addObject().put("type", "tableRow").putArray("content");
            for (int c = 0; c < 8; c++) cells.addObject().put("type", "tableCell").putArray("content").addObject().put("type", "paragraph");
        }
        return new RichDocContentDto(1, root, "");
    }
    static void validate(RichDocContentDto content) {
        JsonNode root = content == null ? null : content.document();
        if (root == null || !"doc".equals(root.path("type").asText()) || !root.path("content").isArray() || root.path("content").size() != 1) fail();
        JsonNode table = root.path("content").get(0);
        if (!"table".equals(table.path("type").asText())) fail();
        JsonNode rows = table.path("content");
        if (!rows.isArray() || rows.isEmpty() || rows.size() > 100) fail();
        int columns = rows.get(0).path("content").size();
        if (columns < 1 || columns > 26 || rows.size() * columns > 1000) fail();
        for (JsonNode row : rows) {
            if (!"tableRow".equals(row.path("type").asText()) || !row.path("content").isArray() || row.path("content").size() != columns) fail();
            for (JsonNode cell : row.path("content")) {
                if (!"tableCell".equals(cell.path("type").asText()) || !cell.path("content").isArray() || cell.path("content").size() != 1
                        || cell.path("attrs").path("colspan").asInt(1) != 1 || cell.path("attrs").path("rowspan").asInt(1) != 1) fail();
                JsonNode paragraph = cell.path("content").get(0);
                if (!"paragraph".equals(paragraph.path("type").asText())) fail();
                int length = 0;
                for (JsonNode text : paragraph.path("content")) {
                    if (!"text".equals(text.path("type").asText()) || !text.path("text").isTextual() || !text.path("marks").isEmpty()) fail();
                    length += text.path("text").asText().length();
                }
                if (length > 20000) fail();
            }
        }
    }
    private static void fail() { throw new BadRequestException("表格须为矩形文本单元格，最多 100 行、26 列、1000 格；请使用表格编辑器保存"); }
}
