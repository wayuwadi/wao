package cn.wayuwa.wao.service;

import java.util.List;

import cn.wayuwa.wao.common.NotFoundException;
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

    /** 知识库列表。 */
    public List<JsonNode> kbs() {
        return parseAll(mapper.kbs());
    }

    /** 文档列表。 */
    public List<JsonNode> docs() {
        return parseAll(mapper.docs());
    }

    /**
     * 单篇文档。
     *
     * @param id 文档 id
     * @return 文档 JSON
     * @throws NotFoundException 文档不存在时
     */
    public JsonNode doc(String id) {
        String payload = mapper.doc(id);
        if (payload == null) {
            throw new NotFoundException("文档不存在：" + id);
        }
        return parse(payload);
    }

    /** 自动化 Job 列表。 */
    public List<JsonNode> jobs() {
        return parseAll(mapper.jobs());
    }

    /** 文件列表。 */
    public List<JsonNode> files() {
        return parseAll(mapper.files());
    }

    /** 图床列表。 */
    public List<JsonNode> images() {
        return parseAll(mapper.images());
    }

    /** 密码库分组列表。 */
    public List<JsonNode> vault() {
        return parseAll(mapper.vault());
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
