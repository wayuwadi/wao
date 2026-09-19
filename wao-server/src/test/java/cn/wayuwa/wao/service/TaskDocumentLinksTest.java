package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TaskDocumentLinksTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void combinesExplicitTaskAndSubtaskDocumentsWithoutProjectInference() {
        assertThat(TaskDocumentLinks.ids("""
                {"docs":["d1","d2",""],"subs":[{"docs":["d2","d3"]},{"parentId":"s1","docs":["d4"]}],
                 "project":"p1","links":[{"type":"project","id":"p2"}]}
                """, json)).containsExactly("d1", "d2", "d3", "d4");
    }

    @Test
    void missingOrMalformedLegacyDetailsHaveNoInferredDocuments() {
        for (String detail : new String[] {null, "", "{}", "null", "broken", "{\"docs\":null,\"subs\":null}"}) {
            assertThat(TaskDocumentLinks.ids(detail, json)).isEmpty();
        }
    }
}
