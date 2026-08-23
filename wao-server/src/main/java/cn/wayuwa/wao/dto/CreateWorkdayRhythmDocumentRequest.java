package cn.wayuwa.wao.dto;
import java.util.List;
/** 首次输入时幂等创建当天文档，可带页面本机草稿。 */
public record CreateWorkdayRhythmDocumentRequest(String title, List<DocBlockDto> blocks) { }
