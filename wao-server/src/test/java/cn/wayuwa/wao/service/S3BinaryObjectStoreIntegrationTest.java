package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Opt-in smoke test against the same private S3 API used by production. */
class S3BinaryObjectStoreIntegrationTest {
    @Test
    void putAndGetOriginalBytesAcrossTheS3Protocol() {
        Assumptions.assumeTrue("true".equals(System.getenv("WAO_STORAGE_SMOKE")));
        byte[] bytes = "WAO SDK object storage smoke test".getBytes(StandardCharsets.UTF_8);
        String key = "files/probe-java-sdk/" + ImageService.sha256(bytes);
        try (var store = new S3BinaryObjectStore(
                System.getenv("WAO_STORAGE_ENDPOINT"), System.getenv().getOrDefault("WAO_STORAGE_REGION", "garage"),
                System.getenv("WAO_STORAGE_BUCKET"), System.getenv("WAO_STORAGE_ACCESS_KEY"),
                System.getenv("WAO_STORAGE_SECRET_KEY"), true)) {
            store.put(key, bytes, "text/plain");
            assertThat(store.get(key)).isEqualTo(bytes);
            assertThat(store.getRange(key, 4, 6)).isEqualTo("SDK".getBytes(StandardCharsets.UTF_8));
        }
    }
}
