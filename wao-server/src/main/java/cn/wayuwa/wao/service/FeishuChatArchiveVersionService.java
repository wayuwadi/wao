package cn.wayuwa.wao.service;

import cn.wayuwa.wao.mapper.FeishuChatArchiveSequenceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 为每次飞书消息查询签发数据库单调版本，防止较早抓取的慢任务晚到后覆盖较新归档。
 */
@Service
public class FeishuChatArchiveVersionService {

    private final FeishuChatArchiveSequenceMapper mapper;

    public FeishuChatArchiveVersionService(FeishuChatArchiveSequenceMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 原子签发一个跨节点递增版本。
     *
     * @return 正整数归档版本
     */
    @Transactional
    public long nextVersion() {
        Long current = mapper.selectForUpdate();
        if (current == null || current < 0 || current == Long.MAX_VALUE) {
            throw new IllegalStateException("飞书聊天归档版本序列不可用");
        }
        long next = current + 1;
        if (mapper.updateValue(next) != 1) {
            throw new IllegalStateException("飞书聊天归档版本签发失败");
        }
        return next;
    }
}
