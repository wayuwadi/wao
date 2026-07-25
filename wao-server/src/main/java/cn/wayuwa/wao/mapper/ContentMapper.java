package cn.wayuwa.wao.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 内容 / 参考域 Mapper：各「文档表」的 payload 列以 JSON 字符串直读，
 * 由 Service 解析回 JSON 返回，避免为纯展示数据定义大量 DTO。
 * 知识库 / 文档已迁移为关系表，见 {@link cn.wayuwa.wao.service.KnowledgeService}。
 */
@Mapper
public interface ContentMapper {

    @Select("SELECT payload FROM file_item ORDER BY ord")
    List<String> files();

    @Select("SELECT payload FROM image_item ORDER BY ord")
    List<String> images();

    @Select("SELECT payload FROM calendar_event ORDER BY ord")
    List<String> events();

    @Select("SELECT payload FROM schedule_item ORDER BY ord")
    List<String> schedule();

    @Select("SELECT payload FROM suggestion ORDER BY ord")
    List<String> suggestions();

    @Select("SELECT payload FROM goal ORDER BY ord")
    List<String> goals();
}
