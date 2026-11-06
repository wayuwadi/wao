package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import cn.wayuwa.wao.domain.ImageBlobEntity;
import org.junit.jupiter.api.Test;

class ImagePreviewCacheTest {
    @Test
    void evictsLeastRecentlyUsedEntriesWithinTheByteBudget() {
        var cache = new ImagePreviewCache(10, 10);
        cache.put(key("a"), preview(4));
        cache.put(key("b"), preview(4));
        assertThat(cache.get(key("a"))).isNotNull();
        cache.put(key("c"), preview(4));
        assertThat(cache.get(key("a"))).isNotNull();
        assertThat(cache.get(key("b"))).isNull();
        assertThat(cache.get(key("c"))).isNotNull();
    }

    @Test
    void entryCountIsBoundedEvenForEmptyPayloadsAndOversizedImagesAreNotRetained() {
        var cache = new ImagePreviewCache(2_000_000, 2);
        cache.put(key("a"), preview(0));
        cache.put(key("b"), preview(0));
        cache.put(key("c"), preview(0));
        cache.put(key("large"), preview(1024 * 1024 + 1));
        assertThat(cache.get(key("a"))).isNull();
        assertThat(cache.get(key("b"))).isNotNull();
        assertThat(cache.get(key("c"))).isNotNull();
        assertThat(cache.get(key("large"))).isNull();
    }

    @Test
    void replacementAndDeletionReleaseTheCorrectBytes() {
        var cache = new ImagePreviewCache(10, 10);
        cache.put(key("a"), preview(8));
        cache.put(key("a"), preview(1));
        cache.put(key("b"), preview(8));
        assertThat(cache.get(key("a"))).isNotNull();
        cache.invalidate("b");
        cache.put(key("c"), preview(8));
        assertThat(cache.get(key("a"))).isNotNull();
        assertThat(cache.get(key("b"))).isNull();
    }

    private static ImageService.Preview preview(int size) { return new ImageService.Preview("image/png", new byte[size]); }
    private static ImagePreviewCache.Key key(String id) {
        var entity = new ImageBlobEntity(); entity.setId(id);
        return ImagePreviewCache.Key.of(entity);
    }
}
