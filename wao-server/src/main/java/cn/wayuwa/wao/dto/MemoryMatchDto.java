package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 服务端权威的记忆翻牌快照；cards 中 0 到 7 分别对应八种 WAO 模块图标。 */
/** 记忆翻牌快照；cards 中未翻开位置为 null，服务端不会提前泄露隐藏牌面。 */
public record MemoryMatchDto(List<Integer> cards, List<Integer> matched, List<Integer> revealed,
                             int steps, Integer bestSteps, boolean finished, int revision,
                             LocalDateTime updatedAt) {
}
