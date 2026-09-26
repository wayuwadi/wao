package cn.wayuwa.wao.service;

import java.util.Arrays;

/** Private object storage for user asset bytes. Keys never leave the server API. */
public interface BinaryObjectStore {
    /** Whether new asset writes should target the object store. */
    boolean writesEnabled();

    /** Keep a MySQL copy during rollout so an older node can still serve new rows. */
    boolean retainMysqlCopy();

    /** Write immutable bytes under a unique key; failure must abort the database write. */
    void put(String key, byte[] bytes, String contentType);

    /** Read bytes or throw when an object is unavailable. */
    byte[] get(String key);

    /** Read an inclusive byte range; stores without native range support may use a full read. */
    default byte[] getRange(String key, long start, long end) {
        byte[] bytes = get(key);
        if (start < 0 || end < start || end >= bytes.length) {
            throw new IllegalArgumentException("Object range is out of bounds");
        }
        return Arrays.copyOfRange(bytes, Math.toIntExact(start), Math.toIntExact(end + 1));
    }
}
