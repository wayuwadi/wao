package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class NoteAiSettingsDefaultsTest {
    @Test void thinkingDefaultsOnButOlderSettingsCannotReenableItAfterMemberTurnsItOff() throws Exception {
        var values=new java.util.LinkedHashMap<String,String>();var prefs=mock(MemberPreferenceMapper.class);var db=mock(JdbcTemplate.class);
        when(db.queryForList(anyString(),eq("m"))).thenReturn(java.util.List.of(Map.of("id","m")));
        when(prefs.find(eq("m"),anyString())).thenAnswer(c->values.get(c.getArgument(1)));
        doAnswer(c->{values.put(c.getArgument(1),c.getArgument(2));return null;}).when(prefs).upsert(eq("m"),anyString(),anyString());
        var json=new ObjectMapper();var service=new NoteAiSettingsService(db,prefs,json);
        assertThat(service.get("m").settings().enabled("thinking")).isTrue();
        var off=service.parse(Map.of(NoteAiSettingsService.DEFAULTS,"{\"thinking\":false}")).settings();
        var saved=service.save("m",new NoteAiSettingsService.Save(off,service.get("m").version()));
        assertThat(saved.settings().thinkingEpoch()).isEqualTo(1);
        com.fasterxml.jackson.databind.node.ObjectNode oldClient=json.valueToTree(saved.settings());oldClient.remove(java.util.List.of("thinking","thinkingEpoch"));
        service.save("m",new NoteAiSettingsService.Save(json.treeToValue(oldClient,NoteAiSettingsService.Settings.class),saved.version()));
        assertThat(service.get("m").settings().enabled("thinking")).isFalse();
        assertThat(service.get("m").settings().thinkingEpoch()).isEqualTo(1);
        assertThatThrownBy(()->service.parse(Map.of(NoteAiSettingsService.DEFAULTS,"{\"thinking\":\"false\"}"))).hasMessageContaining("开关格式");
    }
    @Test void lightweightPolishDefaultsDoNotChangeImageChoiceOrStoredConfigurationVersion() {
        var service = new NoteAiSettingsService(mock(JdbcTemplate.class), mock(MemberPreferenceMapper.class), new ObjectMapper());
        var values = Map.of(NoteAiSettingsService.DEFAULTS, "{\"reasoningEffort\":\"high\"}", "note-ai-model-v1", "gpt-6-astra");
        var view = service.parse(values);
        assertThat(view.settings().polishModel()).isEqualTo("gpt-6-luna");
        assertThat(view.settings().polishReasoningEffort()).isEqualTo("low");
        assertThat(view.settings().model()).isEqualTo("gpt-6-astra");
        assertThat(view.settings().reasoningEffort()).isEqualTo("high");
        assertThat(view.version()).isEqualTo(NoteAiSettingsService.hash(service.write(values)));
    }

    @Test void casSavePersistsPolishChoiceAndOlderClientCannotResetIt() {
        var values = new java.util.LinkedHashMap<String,String>();
        var prefs = mock(MemberPreferenceMapper.class);
        var db = mock(JdbcTemplate.class);
        when(db.queryForList(anyString(),eq("m"))).thenReturn(java.util.List.of(Map.of("id","m")));
        when(prefs.find(eq("m"),anyString())).thenAnswer(c -> values.get(c.getArgument(1)));
        doAnswer(c -> { values.put(c.getArgument(1),c.getArgument(2)); return null; }).when(prefs).upsert(eq("m"),anyString(),anyString());
        var json = new ObjectMapper();
        var service = new NoteAiSettingsService(db,prefs,json);
        var models = mock(LocalCodexModelService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"models",models);
        when(models.validate("gpt-6-astra")).thenReturn("gpt-6-astra");
        when(models.validateEffort("gpt-6-astra","high")).thenReturn("high");
        var initial = service.get("m");
        var custom = service.parse(Map.of(NoteAiSettingsService.DEFAULTS,"{\"polishModel\":\"gpt-6-astra\",\"polishReasoningEffort\":\"high\"}")).settings();
        var saved = service.save("m",new NoteAiSettingsService.Save(custom,initial.version()));
        assertThat(service.get("m").settings().polishReasoningEffort()).isEqualTo("high");
        com.fasterxml.jackson.databind.node.ObjectNode legacyJson = json.valueToTree(saved.settings());
        legacyJson.remove(java.util.List.of("polishModel","polishReasoningEffort"));
        var legacy = json.convertValue(legacyJson,NoteAiSettingsService.Settings.class);
        assertThat(service.save("m",new NoteAiSettingsService.Save(legacy,saved.version())).settings().polishModel()).isEqualTo("gpt-6-astra");
        assertThatThrownBy(() -> service.save("m",new NoteAiSettingsService.Save(custom,initial.version()))).hasMessageContaining("其他设备");
        when(models.validateEffort("gpt-6-astra","high")).thenThrow(new cn.wayuwa.wao.common.BadRequestException("不支持"));
        assertThatThrownBy(() -> service.save("m",new NoteAiSettingsService.Save(custom,service.get("m").version()))).hasMessageContaining("不支持");
        assertThat(service.get("m").settings().polishReasoningEffort()).isEqualTo("high");
    }
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
        assertThat(empty.defaultPrompts().thinkingPrompt()).isEqualTo(NoteThinkingImageService.PROMPT);
        assertThat(empty.defaultPrompts().thinkingImagePrompt()).isEqualTo(NoteThinkingImageService.IMAGE_PROMPT);
        assertThat(json.valueToTree(empty).path("defaultPrompts").size()).isEqualTo(5);
    }
    @Test void thinkingPromptsAreSavedWithCasAndOldClientsCannotEraseThem() throws Exception {
        var values=new java.util.LinkedHashMap<String,String>();var prefs=mock(MemberPreferenceMapper.class);var db=mock(JdbcTemplate.class);
        when(db.queryForList(anyString(),eq("m"))).thenReturn(java.util.List.of(Map.of("id","m")));
        when(prefs.find(eq("m"),anyString())).thenAnswer(c->values.get(c.getArgument(1)));
        doAnswer(c->{values.put(c.getArgument(1),c.getArgument(2));return null;}).when(prefs).upsert(eq("m"),anyString(),anyString());
        var json=new ObjectMapper();var service=new NoteAiSettingsService(db,prefs,json);var initial=service.get("m");
        var custom=service.parse(Map.of("note-thinking-prompt-v1","只挖掘可迁移的方法","note-thinking-image-prompt-v1","深色手绘关系图")).settings();
        var saved=service.save("m",new NoteAiSettingsService.Save(custom,initial.version()));
        assertThat(saved.settings().thinkingPrompt()).isEqualTo("只挖掘可迁移的方法");
        assertThat(saved.settings().thinkingImagePrompt()).isEqualTo("深色手绘关系图");
        assertThat(saved.version()).isNotEqualTo(initial.version());
        assertThat(saved.settings().imagePrompt()).isEmpty();
        var old=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(saved.settings());old.remove(java.util.List.of("thinkingPrompt","thinkingImagePrompt"));
        var preserved=service.save("m",new NoteAiSettingsService.Save(json.treeToValue(old,NoteAiSettingsService.Settings.class),saved.version()));
        assertThat(preserved.settings().thinkingImagePrompt()).isEqualTo("深色手绘关系图");
        assertThatThrownBy(()->service.save("m",new NoteAiSettingsService.Save(custom,initial.version()))).hasMessageContaining("其他设备");
        var tooLong=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(custom);tooLong.put("thinkingPrompt","字".repeat(4001));
        assertThatThrownBy(()->service.save("m",new NoteAiSettingsService.Save(json.treeToValue(tooLong,NoteAiSettingsService.Settings.class),preserved.version()))).hasMessageContaining("4000");
        var reset=service.parse(Map.of()).settings();
        assertThat(service.save("m",new NoteAiSettingsService.Save(reset,preserved.version())).settings().thinkingPrompt()).isEmpty();
    }
}
