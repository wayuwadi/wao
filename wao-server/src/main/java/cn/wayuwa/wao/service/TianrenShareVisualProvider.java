package cn.wayuwa.wao.service;

/** 卦象配图模块向公开分享卡片提供的最小投影；不暴露私有图片端点或提示词。 */
public interface TianrenShareVisualProvider {
    /**
     * 读取有效分享凭据对应的最新成功解读配图；不得触发生成。
     *
     * @param shareToken 已通过卦象公开服务验证的分享凭据
     * @return 对应版本的公开摘要及图片，尚无成功配图时返回 null
     */
    Visual findSharedVisual(String shareToken);

    /** interpretation 必须与公开服务当前返回的解读完全相同；imageVersion 必须随图片改变。 */
    record Visual(String interpretation, String summary, String imageVersion,
            byte[] content, String contentType) { }
}
