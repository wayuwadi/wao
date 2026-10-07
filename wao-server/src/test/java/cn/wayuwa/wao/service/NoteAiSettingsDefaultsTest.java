package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class NoteAiSettingsDefaultsTest {
    @Test void legacySerialAndFiveSlotSettingsNormalizeToUnboundedParallelWithoutChangingStoredVersion() {
        var service = new NoteAiSettingsService(mock(JdbcTemplate.class), mock(MemberPreferenceMapper.class), new ObjectMapper());
        var values = Map.of(NoteAiSettingsService.DEFAULTS, "{\"executionMode\":\"serial\",\"maxConcurrent\":5,\"image\":false}");
        var view = service.parse(values);
        assertThat(view.settings().executionMode()).isEqualTo("parallel");
        assertThat(view.settings().maxConcurrent()).isZero();
        assertThat(view.settings().image()).isFalse();
        assertThat(service.parse(values).version()).isEqualTo(view.version());
        assertThat(service.parse(Map.of()).settings().maxConcurrent()).isZero();
    }

    @Test void exposesExecutionDefaultsWithoutChangingOverridesOrConfigurationVersion() {
        var json = new ObjectMapper();
        var service = new NoteAiSettingsService(mock(JdbcTemplate.class), mock(MemberPreferenceMapper.class), json);
        var empty = service.parse(Map.of());
        var custom = service.parse(Map.of("note-title-prompt-v1", "自定义标题"));
        assertThat(empty.settings().titlePrompt()).isEmpty();
        assertThat(custom.settings().titlePrompt()).isEqualTo("自定义标题");
        assertThat(custom.defaultPrompts()).isEqualTo(empty.defaultPrompts());
        assertThat(empty.defaultPrompts().titlePrompt()).isEqualTo(TextPolishJobService.DEFAULT_NOTE_TITLE_PROMPT);
        assertThat(empty.defaultPrompts().polishPrompt()).isEqualTo(TextPolishService.DEFAULT_PROMPT);
        assertThat(empty.defaultPrompts().imagePrompt()).isEqualTo(NoteAiManagedService.DEFAULT_IMAGE_PROMPT);
        assertThat(service.parse(Map.of()).version()).isEqualTo(empty.version());
        assertThat(json.valueToTree(empty).path("defaultPrompts").size()).isEqualTo(3);
    }
}
