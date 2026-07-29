package cn.wayuwa.wao.dto;

/**
 * 附件 / 文件引用。
 *
 * @param id   真实文件库 id；升级前的历史展示附件可能为空
 * @param name 文件名快照
 * @param size 文件大小展示值
 * @param icon 文件类型图标
 */
public record FileRef(String id, String name, String size, String icon) {
    /** 兼容升级前只保存展示元数据的附件引用。 */
    public FileRef(String name, String size, String icon) {
        this(null, name, size, icon);
    }
}
