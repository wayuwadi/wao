package cn.wayuwa.wao.service;

import cn.wayuwa.wao.domain.DocEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 确定性正文提取与词项索引；索引不调用模型，不代替原文证据。 */
@Service
public class NoteDiscoveryIndexService {
    public static final String VERSION = "terms-v1";
    private static final Pattern WORD = Pattern.compile("[\\p{IsHan}]+|[A-Za-z0-9][A-Za-z0-9_.-]*");
    private final ObjectMapper json;
    public NoteDiscoveryIndexService(ObjectMapper json) { this.json = json; }

    /** 只提取正文属性，保留否定、数字和结尾；图片地址、结构类型不进入证据。 */
    public String text(DocEntity doc) {
        if (doc.getContentMarkdown() != null && !doc.getContentMarkdown().isBlank())
            return normalize(doc.getContentMarkdown().replaceAll("(?m)^!\\[文章总览图]\\([^\\n]+\\)\\s*", ""));
        String raw = doc.getContentJson() != null ? doc.getContentJson() : doc.getBlocksJson();
        if (raw == null || raw.isBlank()) return "";
        try { StringBuilder out = new StringBuilder(); collect(json.readTree(raw), out); return normalize(out.toString()); }
        catch (Exception ex) { throw new IllegalStateException("小记正文读取失败", ex); }
    }

    private void collect(JsonNode node, StringBuilder out) {
        if (node.isArray()) { node.forEach(child -> collect(child, out)); return; }
        if (!node.isObject()) return;
        if (node.path("document").isObject()) { collect(node.path("document"), out); return; }
        for (String key : List.of("call", "h", "p", "ul", "q")) {
            JsonNode value = node.path(key);
            if (value.isTextual()) out.append(value.asText()).append('\n');
            else if (value.isArray()) for (JsonNode line : value) if (line.isTextual()) out.append(line.asText()).append('\n');
        }
        if (node.path("text").isTextual()) out.append(node.path("text").asText()).append('\n');
        else if (node.path("content").isTextual()) out.append(node.path("content").asText()).append('\n');
        else if (node.path("content").isContainerNode()) collect(node.path("content"), out);
        if (node.path("children").isContainerNode()) collect(node.path("children"), out);
    }

    /** 语义敏感规范化，不删除否定、数字、标点或行顺序。 */
    public static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC)
                .replaceAll("(?m)[\\t ]+$", "").strip();
    }

    /** 中文二至四字词项及英文/数字标识用于召回，不把词项匹配当作结论。 */
    public Set<String> terms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        var matches = WORD.matcher(text == null ? "" : text);
        while (matches.find()) {
            String word = matches.group().toLowerCase(Locale.ROOT);
            if (word.length() <= 128) terms.add(word);
            if (word.codePoints().allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
                for (int size = 2; size <= 4; size++) for (int i = 0; i + size <= word.length(); i++) terms.add(word.substring(i, i + size));
            }
        }
        Map<String,String> synonyms = Map.of("打包", "收拾行李", "简称", "名称", "全称", "名称", "核对", "对齐", "写作", "草稿");
        synonyms.forEach((key,value) -> { if (text != null && text.contains(key)) terms.add(value); });
        return terms;
    }

    /** 稳定 SHA-256 指纹，不在日志中记录正文。 */
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("无法生成内容指纹", ex); }
    }
}
