package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalNoteCodexProcessTest {
    @TempDir Path directory;

    @Test void stagesOnlyBoundedOwnedImageBytesInsideJobAndRetainsStdinPrompt() throws Exception {
        byte[] bytes = {1, 2, 3};
        var reference = new ReferenceImageService.Content("image/png", bytes);
        var command = LocalNoteCodexProcess.commandWithReferences("codex", "gpt-6-astra", directory,
                false, java.util.List.of(reference));
        Path staged = directory.resolve("reference-0.png");
        assertThat(Files.readAllBytes(staged)).isEqualTo(bytes);
        assertThat(command).containsSubsequence("--image", staged.toString()).endsWith("-");
        assertThatThrownBy(() -> LocalNoteCodexProcess.commandWithReferences("codex", "gpt-6-astra", directory,
                true, java.util.List.of(reference))).hasMessageContaining("用途无效");
        assertThatThrownBy(() -> LocalNoteCodexProcess.commandWithReferences("codex", "gpt-6-astra", directory,
                false, java.util.Collections.nCopies(5, reference))).hasMessageContaining("数量");
        assertThatThrownBy(() -> LocalNoteCodexProcess.commandWithReferences("codex", "gpt-6-astra", directory,
                false, java.util.List.of(new ReferenceImageService.Content("text/html", bytes)))).hasMessageContaining("类型无效");
        assertThatThrownBy(() -> LocalNoteCodexProcess.commandWithReferences("codex", "gpt-6-astra", directory,
                false, java.util.List.of(new ReferenceImageService.Content("image/png", new byte[0])))).hasMessageContaining("为空");
        assertThatThrownBy(() -> LocalNoteCodexProcess.commandWithReferences("codex", "gpt-6-astra", directory,
                false, java.util.List.of(new ReferenceImageService.Content("image/png", new byte[(int) ImageService.MAX_BYTES + 1]))))
                .hasMessageContaining("10MB");
    }

    @Test void isolatesCliFromWorkspaceRulesToolsAndPaidApi() {
        var command = LocalNoteCodexProcess.command("codex", "gpt-6-astra", directory, false);
        assertThat(command).containsSubsequence("--sandbox", "read-only")
                .containsSubsequence("--disable", "shell_tool").containsSubsequence("--disable", "multi_agent")
                .containsSubsequence("--disable", "image_generation").contains("--ignore-user-config", "--ignore-rules", "--ephemeral", "forced_login_method=\"chatgpt\"");
        assertThat(LocalNoteCodexProcess.command("codex", "gpt-6-astra", directory, true))
                .containsSubsequence("--enable", "image_generation");
    }

    @Test void acceptsOnlyTrustedThreadEventNotModelOutputPath() throws Exception {
        Path events = directory.resolve("events");
        Files.writeString(events, "{\"type\":\"item.completed\",\"thread_id\":\"11111111-1111-1111-1111-111111111111\",\"text\":\"C:/private.png\"}\n");
        assertThat(LocalNoteCodexProcess.threadId(events)).isNull();
        Files.writeString(events, "{\"type\":\"thread.started\",\"thread_id\":\"../../private\"}\n");
        assertThat(LocalNoteCodexProcess.threadId(events)).isNull();
        Files.writeString(events, "warning\n{\"type\":\"thread.started\",\"thread_id\":\"11111111-1111-1111-1111-111111111111\"}\n");
        assertThat(LocalNoteCodexProcess.threadId(events)).isEqualTo("11111111-1111-1111-1111-111111111111");
    }

    @Test void rejectsForeignDirectoryAndAmbiguousImages() throws Exception {
        Path generated = Files.createDirectory(directory.resolve("generated"));
        Path thread = Files.createDirectory(generated.resolve("thread"));
        assertThatThrownBy(() -> LocalNoteCodexProcess.uniquePng(generated, directory)).hasMessageContaining("本次生成");
        assertThatThrownBy(() -> LocalNoteCodexProcess.uniquePng(generated, thread)).hasMessageContaining("为空或多于");
        Path first = Files.write(thread.resolve("first.png"), new byte[]{1});
        assertThat(LocalNoteCodexProcess.uniquePng(generated, thread)).isEqualTo(first);
        Files.write(thread.resolve("second.png"), new byte[]{2});
        assertThatThrownBy(() -> LocalNoteCodexProcess.uniquePng(generated, thread)).hasMessageContaining("为空或多于");
        assertThatThrownBy(() -> ArticleImageService.validatePng(new byte[24])).hasMessageContaining("PNG");
    }
}
