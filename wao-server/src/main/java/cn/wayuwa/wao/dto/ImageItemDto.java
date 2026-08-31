package cn.wayuwa.wao.dto;

/**
 * 图床条目对外表示，字段与前端 {@code ImageItem} 一致。
 *
 * @param id   图片 id（种子演示数据没有 id，为 null）
 * @param name 文件名
 * @param size 人类可读大小，如 “640 KB”
 * @param time 上传时间描述
 * @param from 来源描述，如 “任务 · 整理证件盒”
 * @param url  取图地址（上传的图为 /api/images/{id}/raw）
 * @param c1   列表缩略图渐变起色
 * @param c2   列表缩略图渐变止色
 */
public record ImageItemDto(
        String id,
        String name,
        String size,
        String time,
        String from,
        String url,
        String c1,
        String c2,
        String projectId) {
    /** 滚动兼容：没有项目参数的既有内部调用视为未分类。 */
    public ImageItemDto(String id, String name, String size, String time, String from, String url, String c1,
            String c2) {
        this(id, name, size, time, from, url, c1, c2, null);
    }
}
