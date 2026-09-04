package cn.wayuwa.wao.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class CreatorContextServiceTest {
    @Autowired private CreatorContextService contexts;
    @Autowired private SharedAccountService accounts;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void combinesOwnedWaoSourcesAndExcludesAnotherMembersPrivateContent() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String workspaceId = accounts.workspaceId("acc-demo");
        LocalDateTime now = LocalDateTime.now();
        Timestamp timestamp = Timestamp.valueOf(now);

        jdbc.update("INSERT INTO memory_document(id,scope_type,scope_id,state,current_published_revision_id,updated_at) VALUES(?,?,?,?,?,?)",
                "memory-" + suffix, "personal", "demo", "active", "revision-" + suffix, timestamp);
        jdbc.update("INSERT INTO memory_revision(id,memory_id,title,content_md) VALUES(?,?,?,?)",
                "revision-" + suffix, "memory-" + suffix, "Codex 学习记录", "我希望把步骤讲给朋友听");
        jdbc.update("INSERT INTO memory_document(id,scope_type,scope_id,state,current_published_revision_id,updated_at) VALUES(?,?,?,?,?,?)",
                "other-memory-" + suffix, "personal", "other-member", "active", "other-revision-" + suffix, timestamp);
        jdbc.update("INSERT INTO memory_revision(id,memory_id,title,content_md) VALUES(?,?,?,?)",
                "other-revision-" + suffix, "other-memory-" + suffix, "Codex 私人记录", "OTHER_MEMBER_SECRET");

        jdbc.update("INSERT INTO kb(id,name,perm,deleted_at) VALUES(?,?,?,NULL)",
                "kb-" + suffix, "内容知识库", "仅自己");
        jdbc.update("INSERT INTO doc(id,kb_id,title,author_id,blocks_json,created_at,updated_at,deleted_at,archived_at) VALUES(?,?,?,?,?,?,?,NULL,NULL)",
                "doc-" + suffix, "kb-" + suffix, "WAO 内容方法", "demo", "[{\"p\":\"把真实问题整理成可执行步骤\"}]", timestamp, timestamp);

        jdbc.update("INSERT INTO ai_assistant_conversation(id,member_id,title,created_at,updated_at) VALUES(?,?,?,?,?)",
                "conversation-" + suffix, "demo", "程序员朋友", timestamp, timestamp);
        jdbc.update("INSERT INTO ai_assistant_message(id,conversation_id,role,content,created_at) VALUES(?,?,?,?,?)",
                "message-" + suffix, "conversation-" + suffix, "user", "朋友说他不会使用 Codex", timestamp);

        jdbc.update("INSERT INTO agent_publish_task(id,workspace_id,requested_by_account_id,platform,topic,title,content,status,progress,version,use_wao_context,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "article-" + suffix, workspaceId, "acc-demo", "WECHAT_MP", "以前的文章", "过往成稿",
                "我习惯像朋友聊天一样解释 WAO 和 AI 工具", "PUBLISHED", 100, 1, true, timestamp, timestamp);

        CreatorContextService.Context result = contexts.find("demo", workspaceId,
                "用 WAO 帮朋友学习 Codex", "做大家随时可以找到的程序员朋友");

        assertThat(result.sources()).extracting(source -> source.type())
                .contains("MEMORY", "KNOWLEDGE", "CONVERSATION", "STYLE");
        assertThat(result.prompt()).contains("Codex 学习记录", "WAO 内容方法", "程序员朋友", "过往成稿")
                .doesNotContain("OTHER_MEMBER_SECRET");
    }
}
