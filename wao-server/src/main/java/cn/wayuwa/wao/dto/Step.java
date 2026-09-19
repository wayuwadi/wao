package cn.wayuwa.wao.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 任务步骤 / 子任务条目。
 *
 * <p>子任务不是独立任务实体，始终内嵌在所属任务里，但可以有自己的描述、图片、状态、关联文档与评论。
 * 新增字段在历史数据里缺省为空，序列化时省略空值，保证旧快照的子任务摘要（{@code SubtaskTree.version}）不变。
 *
 * @param text   标题
 * @param done   是否完成（完成态仍以它为准，兼容所有只认 done 的读取方）
 * @param id     稳定标识（历史数据可能为 null，此时由调用方按下标兜底）
 * @param desc   描述（Markdown）
 * @param images 内嵌图片
 * @param number 服务端永久编号
 * @param parentId 同一主任务内父子任务标识，空为根
 * @param owner 负责人成员标识
 * @param due 截止日期，格式 yyyy-MM-dd
 * @param status 未完成时的细分状态，目前只有「进行中」；空表示未开始
 * @param createdBy 创建人成员标识
 * @param createdAt 创建时间（ISO 时间戳）
 * @param docs 关联的知识文档 id
 * @param comments 子任务评论
 * @param pr 子任务独立优先级，历史未设置时为空
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Step(String text, boolean done, String id, String desc, List<ImageRef> images, Long number,
        String parentId, String owner, String due,
        @JsonInclude(JsonInclude.Include.NON_NULL) String status,
        @JsonInclude(JsonInclude.Include.NON_NULL) String createdBy,
        @JsonInclude(JsonInclude.Include.NON_NULL) String createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> docs,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<CommentDto> comments,
        @JsonInclude(JsonInclude.Include.NON_NULL) String pr) {
    /** 兼容已有构造调用，历史优先级缺省为空，不改变快照摘要。 */
    public Step(String text, boolean done, String id, String desc, List<ImageRef> images, Long number,
            String parentId, String owner, String due, String status, String createdBy, String createdAt,
            List<String> docs, List<CommentDto> comments) {
        this(text, done, id, desc, images, number, parentId, owner, due, status, createdBy, createdAt, docs, comments, null);
    }
    /** 兼容只带树与排期属性的调用；其余新增属性缺省为空。 */
    public Step(String text, boolean done, String id, String desc, List<ImageRef> images, Long number,
            String parentId, String owner, String due) {
        this(text, done, id, desc, images, number, parentId, owner, due, null, null, null, null, null);
    }
    /** 兼容旧的单层子任务；新增属性缺省为空。 */
    public Step(String text, boolean done, String id, String desc, List<ImageRef> images, Long number) {
        this(text, done, id, desc, images, number, null, null, null);
    }
    /** 兼容历史调用；编号只由服务端分配。 */
    public Step(String text, boolean done, String id, String desc, List<ImageRef> images) {
        this(text, done, id, desc, images, null);
    }

    /** 同一条子任务换一个编号与层级，其余属性原样保留。 */
    public Step with(String id, Long number, String parentId) {
        return new Step(text, done, id, desc, images, number, parentId, owner, due, status, createdBy, createdAt, docs, comments, pr);
    }

    /** 展示用状态：已完成 / 进行中 / 未开始。 */
    @JsonIgnore
    public String statusLabel() {
        if (done) return "已完成";
        return "进行中".equals(status) ? "进行中" : "未开始";
    }
}
