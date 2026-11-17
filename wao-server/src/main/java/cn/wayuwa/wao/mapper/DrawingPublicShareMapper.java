package cn.wayuwa.wao.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DrawingPublicShareMapper {
    record Link(String id, LocalDateTime createdAt, LocalDateTime expiresAt, byte[] tokenCiphertext, byte[] tokenNonce) {}
    record Scene(String title, int revision, LocalDateTime updatedAt, LocalDateTime expiresAt, String sceneJson) {}

    @Select("""
            SELECT id FROM drawing_file
            WHERE id = #{drawingId} AND owner_member_id = #{ownerId} AND knowledge_doc_id IS NULL
            FOR UPDATE
            """)
    String lockPersonalDrawing(String drawingId, String ownerId);

    @Select("""
            SELECT s.id, s.created_at, s.expires_at, s.token_ciphertext, s.token_nonce
            FROM drawing_public_share s
            JOIN drawing_file f ON f.id = s.drawing_id AND f.owner_member_id = s.owner_member_id
            WHERE s.drawing_id = #{drawingId} AND s.owner_member_id = #{ownerId}
                AND f.knowledge_doc_id IS NULL AND s.revoked_at IS NULL
                AND (s.expires_at IS NULL OR s.expires_at > #{now})
            ORDER BY s.created_at DESC, s.id
            LIMIT 20
            """)
    List<Link> list(String drawingId, String ownerId, LocalDateTime now);

    @Insert("""
            INSERT INTO drawing_public_share
                (id, drawing_id, owner_member_id, token_hash, created_at, expires_at, token_ciphertext, token_nonce)
            VALUES (#{id}, #{drawingId}, #{ownerId}, #{tokenHash}, #{now}, #{expiresAt}, #{ciphertext}, #{nonce})
            """)
    void insert(String id, String drawingId, String ownerId, String tokenHash,
                LocalDateTime now, LocalDateTime expiresAt, byte[] ciphertext, byte[] nonce);

    @Update("""
            UPDATE drawing_public_share SET revoked_at = #{now}, token_ciphertext = NULL, token_nonce = NULL
            WHERE id = #{id} AND drawing_id = #{drawingId} AND owner_member_id = #{ownerId}
              AND revoked_at IS NULL
            """)
    int revoke(String id, String drawingId, String ownerId, LocalDateTime now);

    @Select("""
            SELECT f.title, f.revision, f.updated_at, s.expires_at,
                   CASE WHEN f.revision = #{knownRevision} THEN NULL ELSE f.scene_json END AS scene_json
            FROM drawing_public_share s
            JOIN drawing_file f ON f.id = s.drawing_id AND f.owner_member_id = s.owner_member_id
            WHERE s.token_hash = #{tokenHash} AND f.knowledge_doc_id IS NULL AND s.revoked_at IS NULL
              AND (s.expires_at IS NULL OR s.expires_at > #{now})
            """)
    Scene read(String tokenHash, int knownRevision, LocalDateTime now);
}
