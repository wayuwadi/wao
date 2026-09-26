package cn.wayuwa.wao.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Legacy mode keeps all writes in MySQL until the object cluster is verified. */
@Component
@ConditionalOnProperty(name = "wao.storage.mode", havingValue = "mysql", matchIfMissing = true)
public class DisabledBinaryObjectStore implements BinaryObjectStore {
    @Override public boolean writesEnabled() { return false; }
    @Override public boolean retainMysqlCopy() { return true; }
    @Override public void put(String key, byte[] bytes, String contentType) {
        throw new IllegalStateException("Object storage writes are disabled");
    }
    @Override public byte[] get(String key) {
        throw new IllegalStateException("Object storage reads are disabled");
    }
}
