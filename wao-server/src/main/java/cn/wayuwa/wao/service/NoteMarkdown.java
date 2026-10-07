package cn.wayuwa.wao.service;

import cn.wayuwa.wao.dto.DocDto;
import java.util.ArrayList;
import java.util.Objects;

/** AI 读取旧版小记时保留正文结构；已有新格式时始终以新格式为准。 */
final class NoteMarkdown {
    private NoteMarkdown() {}

    static String of(DocDto doc) {
        if (doc.content() != null) return Objects.toString(doc.content().markdown(), "");
        var parts = new ArrayList<String>();
        if (doc.blocks() != null) for (var block : doc.blocks()) {
            if (block.h() != null) parts.add("#".repeat(Math.max(1, Math.min(6,
                    block.hLevel() == null ? 2 : block.hLevel()))) + " " + block.h());
            else if (block.p() != null) parts.add(block.p());
            else if (block.call() != null) parts.add("!> " + block.call());
            else if (block.q() != null && !block.q().isEmpty()) parts.add("> " + block.q().get(0)
                    + (block.q().size() > 1 && block.q().get(1) != null && !block.q().get(1).isBlank()
                    ? " —— " + block.q().get(1) : ""));
            else if (block.ul() != null) parts.add(String.join("\n",
                    block.ul().stream().map(item -> "- " + item).toList()));
        }
        return String.join("\n\n", parts);
    }
}
