package cn.wayuwa.wao.agent.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 保存已发布文章的真实链接，并按时间追加一组平台数据。 */
public record UpdatePublicationDataRequest(
        @NotNull(message = "缺少版本号") Integer version,
        @Size(max = 1000, message = "发布链接不能超过 1000 字") String publicationUrl,
        @PositiveOrZero Long views,
        @PositiveOrZero Long likes,
        @PositiveOrZero Long comments,
        @PositiveOrZero Long favorites,
        @PositiveOrZero Long shares) {

    public boolean hasMetrics() {
        return views != null || likes != null || comments != null || favorites != null || shares != null;
    }
}
