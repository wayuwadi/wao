package cn.wayuwa.wao.service;

import java.util.LinkedHashMap;
import cn.wayuwa.wao.domain.ImageBlobEntity;

/** Process-local derivative cache, bounded by both payload bytes and entry count. */
final class ImagePreviewCache {
    private static final int MAX_ENTRY_BYTES = 1024 * 1024;
    private final long maxBytes;
    private final int maxEntries;
    private final LinkedHashMap<Key, ImageService.Preview> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long bytes;

    ImagePreviewCache() { this(24L * 1024 * 1024, 256); }
    ImagePreviewCache(long maxBytes, int maxEntries) {
        this.maxBytes = maxBytes;
        this.maxEntries = maxEntries;
    }

    synchronized ImageService.Preview get(Key key) { return entries.get(key); }

    synchronized void put(Key key, ImageService.Preview preview) {
        int size = preview.content().length;
        if (size > MAX_ENTRY_BYTES || size > maxBytes || maxEntries < 1) return;
        ImageService.Preview old = entries.put(key, preview);
        if (old != null) bytes -= old.content().length;
        bytes += size;
        var iterator = entries.entrySet().iterator();
        while (bytes > maxBytes || entries.size() > maxEntries) {
            var entry = iterator.next();
            bytes -= entry.getValue().content().length;
            iterator.remove();
        }
    }

    synchronized void invalidate(String id) {
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().id().equals(id)) {
                bytes -= entry.getValue().content().length;
                iterator.remove();
            }
        }
    }

    record Key(String id, String hash, String type, Long size, String objectKey, String objectHash,
            String objectType, Long objectSize, String previewKey) {
        static Key of(ImageBlobEntity entity) {
            return new Key(entity.getId(), entity.getContentHash(), entity.getContentType(), entity.getByteSize(),
                    entity.getObjectKey(), entity.getObjectHash(), entity.getObjectContentType(),
                    entity.getObjectByteSize(), entity.getPreviewKey());
        }
    }
}
