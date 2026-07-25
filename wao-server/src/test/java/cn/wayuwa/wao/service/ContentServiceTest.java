package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import cn.wayuwa.wao.mapper.ContentMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContentServiceTest {

    private final ContentMapper mapper = mock(ContentMapper.class);
    private final ContentService service = new ContentService(mapper, new ObjectMapper());

    @Test
    void parsesPayloadsToJson() {
        when(mapper.goals()).thenReturn(List.of("{\"id\":\"career\",\"name\":\"发布 WAO 1.0\",\"pct\":62}"));

        var list = service.goals();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("name").asText()).isEqualTo("发布 WAO 1.0");
    }

    @Test
    void fallsBackToEmptyObjectOnInvalidPayload() {
        when(mapper.suggestions()).thenReturn(List.of("not-json"));

        var list = service.suggestions();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).isObject()).isTrue();
        assertThat(list.get(0).isEmpty()).isTrue();
    }
}
