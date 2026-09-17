package cn.wayuwa.wao.mapper;

import java.util.List;
import org.apache.ibatis.annotations.*;

/** Permanent display number registry; deleted numbers are retained. */
@Mapper
public interface WorkItemNumberMapper {
    /** 跨主任务移动时保留已分配的展示编号。 */
    @Update("UPDATE work_item_number SET item_key = #{target} WHERE item_key = #{source}")
    int move(@Param("source") String source, @Param("target") String target);
    record Entry(String itemKey, long number) {}

    @Select("""
        <script>SELECT item_key AS itemKey, sequence_id - 1 AS number FROM work_item_number
        WHERE item_key IN
        <foreach collection="keys" item="key" open="(" separator="," close=")">#{key}</foreach>
        </script>
        """)
    List<Entry> find(@Param("keys") List<String> keys);

    @Insert("""
        <script>INSERT INTO work_item_number(item_key) VALUES
        <foreach collection="keys" item="key" separator=",">(#{key})</foreach>
        ON DUPLICATE KEY UPDATE item_key = work_item_number.item_key</script>
        """)
    void allocate(@Param("keys") List<String> keys);
}
