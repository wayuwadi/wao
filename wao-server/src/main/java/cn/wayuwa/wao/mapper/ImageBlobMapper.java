package cn.wayuwa.wao.mapper;

import java.util.List;

import cn.wayuwa.wao.domain.ImageBlobEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 图床 Mapper。 */
@Mapper
public interface ImageBlobMapper extends BaseMapper<ImageBlobEntity> {

    /** A single image row without binary content, for access checks. */
    @Select("SELECT id, name, content_type, byte_size, source, project_id, content_hash, "
            + "object_key, preview_key, created_at FROM image_blob WHERE id = #{id}")
    ImageBlobEntity findMeta(@Param("id") String id);

    /** 列出元数据，不含 {@code content}——图床列表不该把每张图的正文都读进内存。 */
    @Select("SELECT id, name, content_type, byte_size, source, project_id, content_hash, created_at "
            + "FROM image_blob ORDER BY created_at DESC")
    List<ImageBlobEntity> listMeta();

    /**
     * 按内容摘要找一张已经存过的图，同样不读 {@code content}。
     *
     * @param hash     图片正文的 SHA-256 十六进制摘要
     * @param byteSize 字节数，和摘要一起比对
     * @return 最早存下的那一份；没有则为 null
     */
    @Select("SELECT id, name, content_type, byte_size, source, project_id, content_hash, created_at "
            + "FROM image_blob WHERE content_hash = #{hash} AND byte_size = #{byteSize} "
            + "ORDER BY created_at LIMIT 1")
    ImageBlobEntity findByContentHash(@Param("hash") String hash, @Param("byteSize") long byteSize);

    /** 在并发去重写入冲突后读取已获胜的唯一记录。 */
    @Select("SELECT id, name, content_type, byte_size, source, project_id, content_hash, dedupe_key, created_at "
            + "FROM image_blob WHERE dedupe_key = #{dedupeKey} LIMIT 1 FOR UPDATE")
    ImageBlobEntity findByDedupeKey(@Param("dedupeKey") String dedupeKey);
}
