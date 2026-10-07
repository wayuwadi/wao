package cn.wayuwa.wao.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

/** 读取真实持久引用；成员偏好只在服务端解析，不返回成员或正文。 */
@Mapper
public interface FeishuBotUsageMapper {
    @Select("SELECT pref_value FROM member_preference WHERE pref_key = 'workday-rhythm-config-v1'")
    List<String> workdayPreferences();
    @Select("SELECT COUNT(*) FROM workday_rhythm_checklist_item WHERE webhook_id = #{id} AND notify_webhook = TRUE")
    long checklistReferences(@Param("id") String id);
    @Select("SELECT COUNT(*) FROM ai_task_auto_run_state WHERE notification_webhook_id = #{id}")
    long autoRunReferences(@Param("id") String id);
}
