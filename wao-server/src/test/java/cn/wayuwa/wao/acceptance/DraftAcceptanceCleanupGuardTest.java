package cn.wayuwa.wao.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.wayuwa.wao.domain.DocEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class DraftAcceptanceCleanupGuardTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant now = Instant.parse("2026-09-19T12:00:00Z");

    private ObjectNode report() {
        var report = json.createObjectNode();
        report.put("runId", "acceptance-fixture");
        report.put("title", DraftAcceptanceCleanupTest.PREFIX + "acceptance-fixture");
        report.put("kbPath", "/kb/acceptance-kb");
        report.put("startedAt", now.minusSeconds(600).toString());
        report.putArray("documents").addObject().put("id", "d_fixture").put("title", report.path("title").asText());
        return report;
    }

    private DocEntity document(DraftAcceptanceCleanupTest.Target target) {
        var doc = new DocEntity();
        doc.setId(target.id()); doc.setTitle(target.title()); doc.setKbId(target.kbId());
        doc.setCreatedAt(LocalDateTime.ofInstant(target.startedAt().plusSeconds(5), DraftAcceptanceCleanupTest.ZONE));
        return doc;
    }

    @Test void acceptsOnlyExactReportTargetsIncludingFailedSingleDocumentRuns() {
        var report = report();
        var targets = DraftAcceptanceCleanupTest.validateReport(report, now);
        assertThat(targets).hasSize(1);
        DraftAcceptanceCleanupTest.validateDocument(targets.get(0), document(targets.get(0)), now);
        report.withArray("documents").addObject().put("id", "d_copy")
                .put("title", report.path("title").asText() + "（冲突副本）");
        assertThat(DraftAcceptanceCleanupTest.validateReport(report, now)).hasSize(2);
    }

    @Test void refusesEmptyDuplicateOrUnboundedTargetsAndWrongTitle() {
        var report = report();
        report.withArray("documents").removeAll();
        assertThatThrownBy(() -> DraftAcceptanceCleanupTest.validateReport(report, now)).isInstanceOf(IllegalArgumentException.class);
        var duplicate = report();
        duplicate.withArray("documents").addObject().put("id", "d_fixture")
                .put("title", duplicate.path("title").asText() + "（冲突副本）");
        assertThatThrownBy(() -> DraftAcceptanceCleanupTest.validateReport(duplicate, now)).isInstanceOf(IllegalArgumentException.class);
        var wrong = report();
        ((ObjectNode) wrong.path("documents").get(0)).put("title", "其他业务文档");
        assertThatThrownBy(() -> DraftAcceptanceCleanupTest.validateReport(wrong, now)).isInstanceOf(IllegalArgumentException.class);
        var many = report();
        many.withArray("documents").addObject(); many.withArray("documents").addObject();
        assertThatThrownBy(() -> DraftAcceptanceCleanupTest.validateReport(many, now)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void refusesStaleAndFutureReports() {
        for (Instant time : new Instant[] { now.minusSeconds(24 * 3600 + 1), now.plusSeconds(61) }) {
            var report = report(); report.put("startedAt", time.toString());
            assertThatThrownBy(() -> DraftAcceptanceCleanupTest.validateReport(report, now)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void refusesRenamedMovedOldAndAutomaticDocumentsBeforeAnyWrite() {
        var target = DraftAcceptanceCleanupTest.validateReport(report(), now).get(0);
        for (int mutation = 0; mutation < 6; mutation++) {
            var doc = document(target);
            switch (mutation) {
                case 0 -> doc.setTitle("真实业务标题");
                case 1 -> doc.setKbId("other-kb");
                case 2 -> doc.setCreatedAt(doc.getCreatedAt().minusDays(1));
                case 3 -> doc.setAuto(true);
                case 4 -> doc.setAutoSyncScope("archive");
                case 5 -> doc.setId("another-document");
                default -> throw new IllegalStateException();
            }
            assertThatThrownBy(() -> DraftAcceptanceCleanupTest.validateDocument(target, doc, now)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> DraftAcceptanceCleanupTest.validateDocument(target, null, now)).isInstanceOf(IllegalArgumentException.class);
    }
}
