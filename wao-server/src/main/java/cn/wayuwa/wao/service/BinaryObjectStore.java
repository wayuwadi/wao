package cn.wayuwa.wao.service;

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
}
