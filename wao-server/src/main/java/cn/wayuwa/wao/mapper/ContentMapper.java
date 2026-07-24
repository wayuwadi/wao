package cn.wayuwa.wao.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 内容 / 参考域 Mapper：各「文档表」的 payload 列以 JSON 字符串直读，
 * 由 Service 解析回 JSON 返回，避免为纯展示数据定义大量 DTO。
 */
@Mapper
public interface ContentMapper {

    @Select("SELECT payload FROM kb ORDER BY ord")
    List<String> kbs();

    @Select("SELECT payload FROM doc ORDER BY ord")
    List<String> docs();

    @Select("SELECT payload FROM doc WHERE id = #{id}")
    String doc(String id);

    @Select("SELECT payload FROM job ORDER BY ord")
    List<String> jobs();

    @Select("SELECT payload FROM file_item ORDER BY ord")
    List<String> files();

    @Select("SELECT payload FROM image_item ORDER BY ord")
    List<String> images();

    @Select("SELECT payload FROM vault_group ORDER BY ord")
    List<String> vault();

    @Select("SELECT payload FROM calendar_event ORDER BY ord")
    List<String> events();

    @Select("SELECT payload FROM schedule_item ORDER BY ord")
    List<String> schedule();

    @Select("SELECT payload FROM suggestion ORDER BY ord")
    List<String> suggestions();

    @Select("SELECT payload FROM goal ORDER BY ord")
    List<String> goals();
}
