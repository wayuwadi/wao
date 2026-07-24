package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.mapper.ContentMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ContentServiceTest {

    private final ContentMapper mapper = mock(ContentMapper.class);
    private final ContentService service = new ContentService(mapper, new ObjectMapper());

    @Test
    void parsesPayloadsToJson() {
        when(mapper.kbs()).thenReturn(List.of("{\"id\":\"k1\",\"name\":\"WAO 产品\",\"folders\":[]}"));

        var list = service.kbs();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("name").asText()).isEqualTo("WAO 产品");
    }

    @Test
    void docThrowsWhenMissing() {
        when(mapper.doc("nope")).thenReturn(null);
        assertThatThrownBy(() -> service.doc("nope")).isInstanceOf(NotFoundException.class);
    }
}
