package cn.wayuwa.wao.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 任务步骤 / 子任务条目。
 *
 * <p>子任务不是独立任务实体，始终内嵌在所属任务里，但可以有自己的描述与图片。
 *
 * @param text   标题
 * @param done   是否完成
 * @param id     稳定标识（历史数据可能为 null，此时由调用方按下标兜底）
 * @param desc   描述（Markdown）
 * @param images 内嵌图片
 * @param number 服务端永久编号
 * @param parentId 同一主任务内父子任务标识，空为根
 * @param owner 负责人成员标识
 * @param due 截止日期，格式 yyyy-MM-dd
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Step(String text, boolean done, String id, String desc, List<ImageRef> images, Long number,
        String parentId, String owner, String due) {
    /** 兼容旧的单层子任务；新增属性缺省为空。 */
    public Step(String text, boolean done, String id, String desc, List<ImageRef> images, Long number) {
        this(text, done, id, desc, images, number, null, null, null);
    }
    /** 兼容历史调用；编号只由服务端分配。 */
    public Step(String text, boolean done, String id, String desc, List<ImageRef> images) {
        this(text, done, id, desc, images, null);
    }
}
