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
    static boolean isWorkbook(RichDocContentDto content) {
        return content != null && content.document() != null && "waoWorkbook".equals(content.document().path("type").asText());
    }
    private static void validateWorkbook(JsonNode document) {
        JsonNode book = document.path("attrs").path("snapshot");
        if (!book.isObject() || !book.path("id").isTextual() || book.path("id").asText().isBlank()
                || !book.path("sheets").isObject() || !book.path("sheetOrder").isArray()
                || book.path("sheetOrder").isEmpty() || book.path("sheetOrder").size() > 20
                || document.toString().length() > 5_000_000) workbookFail();
        var ids = new java.util.HashSet<String>();
        int count = 0;
        for (JsonNode key : book.path("sheetOrder")) {
            if (!key.isTextual() || !ids.add(key.asText())) workbookFail();
            JsonNode sheet = book.path("sheets").path(key.asText());
            if (!sheet.isObject() || !key.asText().equals(sheet.path("id").asText())
                    || sheet.path("rowCount").asInt() < 1 || sheet.path("rowCount").asInt() > 100_000
                    || sheet.path("columnCount").asInt() < 1 || sheet.path("columnCount").asInt() > 1024) workbookFail();
            JsonNode cells = sheet.path("cellData");
            if (!cells.isMissingNode() && !cells.isObject()) workbookFail();
            var rows = cells.fields();
            while (rows.hasNext()) {
                var row = rows.next();
                if (!indexWithin(row.getKey(), sheet.path("rowCount").asInt())) workbookFail();
                if (row.getValue().isNull()) continue;
                if (!row.getValue().isObject()) workbookFail();
                var columns = row.getValue().fields();
                while (columns.hasNext()) {
                    var cell = columns.next();
                    if (++count > 100_000 || !indexWithin(cell.getKey(), sheet.path("columnCount").asInt())
                            || (!cell.getValue().isNull() && !cell.getValue().isObject())) workbookFail();
                }
            }
        }
        if (book.path("sheets").size() != ids.size()) workbookFail();
    }
    private static boolean indexWithin(String index, int limit) {
        try { return index.matches("[0-9]{1,6}") && Integer.parseInt(index) < limit; }
        catch (NumberFormatException ignored) { return false; }
    }
    private static void workbookFail() { throw new BadRequestException("工作簿结构不合法或超过容量限制（20 个工作表、100000 个已用单元格、5MB）"); }
    static void validate(RichDocContentDto content) {
        if (isWorkbook(content)) { validateWorkbook(content.document()); return; }
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
