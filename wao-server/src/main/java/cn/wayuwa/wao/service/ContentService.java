package cn.wayuwa.wao.service;

import java.util.List;

import cn.wayuwa.wao.mapper.ContentMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/** 内容 / 参考域读取服务：把存储的 payload JSON 原样解析后返回。 */
@Service
public class ContentService {

    private final ContentMapper mapper;
    private final ObjectMapper json;

    public ContentService(ContentMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    /** 文件列表。 */
    public List<JsonNode> files() {
        return parseAll(mapper.files());
    }

    /** 图床列表。 */
    public List<JsonNode> images() {
        return parseAll(mapper.images());
    }

    /** 日历事件列表。 */
    public List<JsonNode> events() {
        return parseAll(mapper.events());
    }

    /** 今日日程列表。 */
    public List<JsonNode> schedule() {
        return parseAll(mapper.schedule());
    }

    /** AI 建议列表。 */
    public List<JsonNode> suggestions() {
        return parseAll(mapper.suggestions());
    }

    /** 目标胶囊列表。 */
    public List<JsonNode> goals() {
        return parseAll(mapper.goals());
    }

    private List<JsonNode> parseAll(List<String> rows) {
        return rows.stream().map(this::parse).toList();
    }

    private JsonNode parse(String payload) {
        try {
            return json.readTree(payload);
        } catch (Exception ex) {
            return json.createObjectNode();
        }
    }
}
