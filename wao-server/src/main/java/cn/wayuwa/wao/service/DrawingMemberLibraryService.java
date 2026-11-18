package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.mapper.DrawingMemberLibraryMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DrawingMemberLibraryService {
    private static final int MAX_ITEMS = 1500;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final DrawingMemberLibraryMapper mapper;
    private final ObjectMapper json;

    public record Change(List<JsonNode> upsert, List<String> remove) {}

    public DrawingMemberLibraryService(DrawingMemberLibraryMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public List<JsonNode> read(String memberId) {
        return List.copyOf(readItems(mapper.find(memberId)).values());
    }

    @Transactional
    public List<JsonNode> change(String memberId, Change change) {
        if (change == null || change.upsert() == null || change.remove() == null
                || change.upsert().size() > MAX_ITEMS || change.remove().size() > MAX_ITEMS) {
            throw new BadRequestException("素材库变更格式不正确或条目过多");
        }
        Map<String, JsonNode> additions = new LinkedHashMap<>();
        for (JsonNode item : change.upsert()) {
            if (item == null || !item.isObject() || !item.path("id").isTextual()
                    || !validId(item.path("id").asText()) || !item.path("elements").isArray()
                    || item.path("elements").isEmpty() || !List.of("published", "unpublished").contains(item.path("status").asText())) {
                throw new BadRequestException("素材格式不正确");
            }
            for (JsonNode element : item.path("elements")) {
                if (!element.isObject() || !element.path("type").isTextual() || !element.path("id").isTextual()) {
                    throw new BadRequestException("素材元素格式不正确");
                }
            }
            additions.put(item.path("id").asText(), item);
        }
        if (change.remove().stream().anyMatch(id -> !validId(id))) {
            throw new BadRequestException("素材标识不正确");
        }
        if (additions.isEmpty() && change.remove().isEmpty()) return read(memberId);

        mapper.ensure(memberId);
        Map<String, JsonNode> existing = readItems(mapper.lock(memberId));
        change.remove().forEach(existing::remove);
        existing.forEach(additions::putIfAbsent);
        List<JsonNode> result = List.copyOf(additions.values());
        try {
            byte[] payload = json.writeValueAsBytes(result);
            if (result.size() > MAX_ITEMS || payload.length > MAX_BYTES) {
                throw new BadRequestException("账号素材库已满，可移除部分素材后重试");
            }
            mapper.save(memberId, new String(payload, StandardCharsets.UTF_8));
            return result;
        } catch (JsonProcessingException e) {
            throw new BadRequestException("无法读取素材内容");
        }
    }

    private boolean validId(String id) {
        return id != null && !id.isBlank() && id.length() <= 128;
    }

    private Map<String, JsonNode> readItems(String payload) {
        Map<String, JsonNode> items = new LinkedHashMap<>();
        if (payload == null) return items;
        try {
            for (JsonNode item : json.readTree(payload)) items.put(item.path("id").asText(), item);
            return items;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取账号素材库", e);
        }
    }
}
