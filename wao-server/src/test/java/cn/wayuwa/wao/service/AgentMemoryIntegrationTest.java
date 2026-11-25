package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.service.AgentMemoryService.Entry;
import cn.wayuwa.wao.service.AgentMemoryService.Input;
import cn.wayuwa.wao.service.AgentMemoryService.Page;
import cn.wayuwa.wao.service.AgentMemoryService.Review;
import cn.wayuwa.wao.service.WorkspaceMcpTokenService.AuthenticatedToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:agentmemory;DB_CLOSE_DELAY=-1;MODE=MySQL")
@Sql(scripts = {"/project-memory-schema.sql", "/unified-memory-schema.sql", "/db/migration/V217__personal_agent_memories.sql"},
        statements = "INSERT INTO member VALUES ('other', '其他成员', '他', '#4358D6', '成员')")
@Sql(statements = "DROP TABLE agent_memory_revision", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class AgentMemoryIntegrationTest {
    @Autowired private AgentMemoryService memories;
    @Autowired private WorkspaceMcpTools tools;
    @Autowired private ObjectMapper json;

    @Test
    void bothAgentsExchangeOnlyConfirmedMemoriesWithExplicitOwnerGrants() throws Exception {
        var reader = token("wg", false, "search_agent_memories");
        var writer = token("wg", true, "propose_agent_memory");
        var old = new AuthenticatedToken("legacy", "wg", true);
        assertThat(tools.list(old).toString()).doesNotContain("agent_memor");
        assertThat(tools.list(reader).toString()).contains("search_agent_memories").doesNotContain("propose_agent_memory");
        assertThatThrownBy(() -> tools.call("search_agent_memories", json.createObjectNode(), writer))
                .isInstanceOf(BadRequestException.class);
        for (String agent : List.of("codex", "agy")) {
            var args = json.createObjectNode().put("kind", "experience").put("title", agent + " 提交的经验")
                    .put("content", "运行项目专用检查，查看稳定输出").put("project", "Org/Demo")
                    .put("source", agent).put("source_summary", "本项目验证");
            var receipt = json.valueToTree(tools.call("propose_agent_memory", args, writer));
            assertThat(receipt.path("state").asText()).isEqualTo("draft");
            var query = json.createObjectNode().put("query", agent).put("project", "org/demo");
            assertThat(((Page) tools.call("search_agent_memories", query, reader)).items()).isEmpty();
            var id = receipt.path("id").asText();
            var draft = memories.history("wg", id).get(0);
            var approved = memories.review("wg", id, review(draft, "publish", draft.content(), false));
            assertThat(approved.version()).isEqualTo(2);
            assertThat(((Page) tools.call("search_agent_memories", query, reader)).items()).containsExactly(approved);
            assertThat(((Page) tools.call("search_agent_memories", query, token("other", false, "search_agent_memories"))).items()).isEmpty();
            assertThatThrownBy(() -> memories.history("other", id)).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> memories.review("other", id, review(approved, "archive", approved.content(), false)))
                    .isInstanceOf(NotFoundException.class);
            query.remove("project");
            assertThat(((Page) tools.call("search_agent_memories", query, reader)).items()).isEmpty();
        }
    }

    @Test
    void duplicateProposalsAndConcurrentEditsAreSafeAndHistorySurvivesArchive() throws Exception {
        var proposal = new Input("preference", "回答偏好", "先给结果", "", "codex", "复用偏好");
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Entry> sameProposal = () -> memories.propose("wg", proposal);
            var futures = pool.invokeAll(List.of(sameProposal, sameProposal));
            var candidate = futures.get(0).get();
            assertThat(futures.get(1).get().id()).isEqualTo(candidate.id());
            assertThat(memories.list("wg", "draft", null, "", "codex", 0).items()).hasSize(1);
            var approved = memories.review("wg", candidate.id(), review(candidate, "publish", "先给结果", true));
            assertThat(memories.search("wg", null, "ignored", true, 0).items()).containsExactly(approved);

            Callable<Boolean> revise = () -> {
                try { memories.review("wg", approved.id(), review(approved, "revise", "先给结果和证据", true)); return true; }
                catch (ConflictException expected) { return false; }
            };
            var writes = pool.invokeAll(List.of(revise, revise));
            assertThat(List.of(writes.get(0).get(), writes.get(1).get())).containsExactlyInAnyOrder(true, false);
            var history = memories.history("wg", candidate.id());
            assertThat(history).extracting(Entry::version).containsExactly(3, 2, 1);
            memories.review("wg", candidate.id(), review(history.get(0), "archive", null, false));
            assertThat(memories.search("wg", null, null, true, 0).items()).isEmpty();
            assertThat(memories.history("wg", candidate.id())).hasSize(3);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void mergeChecksBothVersionsAndProjectAndCredentialBoundaries() {
        var base = memories.propose("wg", new Input("experience", "检查", "先运行构建", "demo", "agy", "一次成功检查"));
        var target = memories.review("wg", base.id(), review(base, "publish", base.content(), false));
        var draft = memories.propose("wg", new Input("experience", "新增检查", "还需要运行单测", "demo", "import", "已复查手记"));
        Review stale = new Review("merge", draft.revisionId(), "完整检查", "运行构建和单测", "", false, target.id(), base.revisionId());
        assertThatThrownBy(() -> memories.review("wg", draft.id(), stale)).isInstanceOf(ConflictException.class);
        Review proper = new Review("merge", draft.revisionId(), "完整检查", "运行构建和单测", "", false, target.id(), target.revisionId());
        assertThat(memories.review("wg", draft.id(), proper).id()).isEqualTo(target.id());
        assertThat(memories.history("wg", draft.id()).get(0).state()).isEqualTo("archived");
        assertThat(memories.search("wg", "单测", "demo", false, 0).items()).extracting(Entry::content).containsExactly("运行构建和单测");
        assertThatThrownBy(() -> memories.propose("wg", new Input("experience", "保密", "样例", "demo", "agy",
                "口令：dummy-credential-for-test"))).isInstanceOf(BadRequestException.class);
    }

    private static Review review(Entry row, String action, String content, boolean startup) {
        return new Review(action, row.revisionId(), row.title(), content, row.project(), startup, null, null);
    }

    private static AuthenticatedToken token(String member, boolean writable, String permission) {
        return new AuthenticatedToken(permission, member, writable, false,
                new WorkspaceCapabilityPolicy(List.of(permission), null, null));
    }
}
