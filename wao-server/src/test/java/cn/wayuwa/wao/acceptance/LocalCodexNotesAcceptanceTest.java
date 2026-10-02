package cn.wayuwa.wao.acceptance;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalCodexNotesAcceptanceTest {
    private LocalCodexNotesAcceptance.Config config(String member, String prefix, int timeout, String mode) {
        return new LocalCodexNotesAcceptance.Config(18082, member, prefix,
                Path.of("codex").toAbsolutePath().toString(), Path.of("target/acceptance").toAbsolutePath().toString(),
                List.of(), mode, timeout, 0);
    }

    @Test void rejectsUnscopedOrUnboundedExecution() {
        assertThrows(IllegalArgumentException.class, () -> config("", "WAO-CODEX-ACCEPTANCE-20261002-TEST", 180, "normal").validate());
        assertThrows(IllegalArgumentException.class, () -> config("m-test", "普通小记", 180, "normal").validate());
        assertThrows(IllegalArgumentException.class, () -> config("m-test", "WAO-CODEX-ACCEPTANCE-20261002-TEST", 0, "normal").validate());
        assertThrows(IllegalArgumentException.class, () -> config("m-test", "WAO-CODEX-ACCEPTANCE-20261002-TEST", 301, "normal").validate());
        assertThrows(IllegalArgumentException.class, () -> config("m-test", "WAO-CODEX-ACCEPTANCE-20261002-TEST", 180, "unknown").validate());
        assertDoesNotThrow(() -> config("m-test", "WAO-CODEX-ACCEPTANCE-20261002-TEST", 180, "hold").validate());
    }
}
