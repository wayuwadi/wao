package cn.wayuwa.wao.service;

import java.util.List;
import java.util.LinkedHashSet;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.mapper.NoteTagMapper;
import org.springframework.stereotype.Service;

/** 管理小记共享标签与一致的名称约束。 */
@Service
public class NoteTagService {
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
    private final NoteTagMapper mapper;
    public NoteTagService(NoteTagMapper mapper) { this.mapper = mapper; }

    /** 列出已持久化标签，未关联文档的标签也保留。 */
    public List<String> list() { return mapper.listNames(); }

    /** 幂等新增一个标签；重试和同时创建同名标签不产生重复项。 */
    public String create(String name) {
        String normalized = checkedName(name);
        mapper.ensureName(normalized);
        return normalized;
    }

    /** 删除标签或将其并入目标标签；仅更改标签关联，保留文档正文和生命周期。 */
    @org.springframework.transaction.annotation.Transactional
    public void replace(String source, String target) {
        String from = checkedName(source);
        String to = target == null ? null : checkedName(target);
        if (from.equals(to)) throw new BadRequestException("请选择不同的目标标签");
        if (to != null) mapper.ensureName(to);
        for (var note : mapper.taggedNotes(from)) {
            try {
                List<String> current = json.readValue(note.getTagsJson(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                LinkedHashSet<String> updated = new LinkedHashSet<>();
                for (String tag : current) {
                    if (!from.equals(tag)) updated.add(tag);
                    else if (to != null) updated.add(to);
                }
                mapper.updateTags(note.getId(), json.writeValueAsString(updated));
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new IllegalStateException("标签数据无法读取，未提交本次修改", exception);
            }
        }
        mapper.deleteName(from);
    }

    /** 校验并去重一篇小记的标签；空集合表示明确清空。 */
    public static List<String> normalize(List<String> tags) {
        if (tags.size() > 20) throw new BadRequestException("每篇小记最多添加 20 个标签");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String tag : tags) result.add(checkedName(tag));
        return List.copyOf(result);
    }

    private static String checkedName(String value) {
        String name = value == null ? "" : value.strip();
        if (name.isEmpty() || name.length() > 32 || name.chars().anyMatch(Character::isISOControl)) {
            throw new BadRequestException("标签名称须为 1–32 个字符，不能包含换行或控制字符");
        }
        return name;
    }
}
