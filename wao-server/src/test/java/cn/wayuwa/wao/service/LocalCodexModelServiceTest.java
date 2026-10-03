package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

class LocalCodexModelServiceTest {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper();
    final List<LocalCodexModelService.Model> models = List.of(
            new LocalCodexModelService.Model("gpt-6.1-sol", "GPT-6.1 Sol"),
            new LocalCodexModelService.Model("gpt-6-astra", "GPT-6 Astra"));
    LocalCodexModelService service() {
        return spy(new LocalCodexModelService(new MockEnvironment()
                .withProperty("wao.ai.local-notes.enabled", "true")
                .withProperty("wao.ai.local-notes.directory", directory.toString()), json));
    }
    @Test void discoversNewModelsAndRejectsHiddenOrMalformedRows() throws Exception {
        var result = LocalCodexModelService.normalize(json.readTree("""
            {"data":[{"model":"gpt-6.1-sol","displayName":"GPT-6.1 Sol"},
            {"model":"gpt-future-model","displayName":"未来模型"},
            {"model":"hidden","hidden":true},{"model":"--unsafe flag"},{}]}
            """));
        assertThat(result).extracting(LocalCodexModelService.Model::id).containsExactly("gpt-6.1-sol", "gpt-future-model");
    }
    @Test void preservesExactSelectionAndDoesNotSilentlyFallback() throws Exception {
        var service = service(); doReturn(models).when(service).readModels();
        assertThat(service.validate("gpt-6.1-sol")).isEqualTo("gpt-6.1-sol");
        assertThat(service.validate(null)).isEqualTo("gpt-6-astra");
        assertThatThrownBy(() -> service.validate("gpt-no-longer-available")).hasMessageContaining("重新选择");
        verify(service, times(1)).readModels();
    }
    @Test void failureRetainsLastSuccessIncludingAcrossRestartAndRecovers() throws Exception {
        var first = service(); doReturn(models).when(first).readModels();
        String at = first.catalog().updatedAt();
        var restarted = service(); doThrow(new IOException()).when(restarted).readModels();
        assertThat(restarted.catalog().stale()).isTrue();
        assertThat(restarted.catalog().updatedAt()).isEqualTo(at);
        assertThat(restarted.validate("gpt-6.1-sol")).isEqualTo("gpt-6.1-sol");
        doReturn(List.of(new LocalCodexModelService.Model("gpt-future-model", "未来模型"))).when(restarted).readModels();
        ReflectionTestUtils.setField(restarted, "nextRead", 0L);
        assertThat(restarted.catalog().stale()).isFalse();
        assertThat(restarted.validate("gpt-future-model")).isEqualTo("gpt-future-model");
    }
    @Test void initialFailureAndEmptyCatalogAreNotPresentedAsSuccess() throws Exception {
        var service = service(); doReturn(List.of()).when(service).readModels();
        assertThat(service.catalog().stale()).isTrue();
        assertThat(service.catalog().updatedAt()).isNull();
        assertThatThrownBy(() -> service.validate(null)).hasMessageContaining("本机 Codex");
    }
    @Test void disabledServiceDoesNotStartCodex() throws Exception {
        var service = spy(new LocalCodexModelService(new MockEnvironment(), json));
        assertThat(service.catalog().models()).isEmpty(); verify(service, never()).readModels();
    }
    @Test @EnabledIfSystemProperty(named="wao.test.codex", matches=".+")
    void realLoggedInCodexCatalog() throws Exception {
        var service = new LocalCodexModelService(new MockEnvironment()
                .withProperty("wao.ai.local-notes.enabled", "true")
                .withProperty("wao.ai.local-notes.executable", System.getProperty("wao.test.codex"))
                .withProperty("wao.ai.local-notes.directory", directory.toString()), json);
        var catalog = service.catalog();
        assertThat(catalog.stale()).as(catalog.message()).isFalse();
        assertThat(catalog.models()).extracting(LocalCodexModelService.Model::id).contains("gpt-6.1-sol", "gpt-6-astra");
        System.out.println("CODEX_MODEL_IDS=" + catalog.models().stream().map(LocalCodexModelService.Model::id).toList());
    }
}
