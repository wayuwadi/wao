package cn.wayuwa.wao.feishu;

import cn.wayuwa.wao.mapper.FeishuApiUsageMapper;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 独立事务记录真实出站尝试；业务回滚不能抹掉已经消耗的调用。 */
@Component
public class FeishuApiUsageRecorder {
    private final FeishuApiUsageMapper mapper;

    public FeishuApiUsageRecorder(FeishuApiUsageMapper mapper) { this.mapper = mapper; }

    /** 只保存方法和归一化路径，排除查询参数、消息及群身份。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String method, URI uri) {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        mapper.record(now.toLocalDate(), endpoint(method, uri), now);
    }

    static String endpoint(String method, URI uri) {
        String path = uri.getPath().replaceAll("/(messages|chats|images)/[^/]+", "/$1/{id}")
                .replaceAll("/(reactions|resources)/[^/]+", "/$1/{id}");
        // 仅固定 API 路径，不允许任意 URI 成为高基数统计或泄露身份。
        return method + " " + path;
    }
}
