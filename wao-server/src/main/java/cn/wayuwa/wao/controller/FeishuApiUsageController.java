package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.mapper.FeishuApiUsageMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 已登录用户查看 WAO 自身双节点的飞书调用事实。 */
@RestController
@RequestMapping("/api/feishu/chat-sync/api-usage")
public class FeishuApiUsageController {
    private final FeishuApiUsageMapper mapper;
    public FeishuApiUsageController(FeishuApiUsageMapper mapper) { this.mapper = mapper; }

    /** 上海时区本月排名，仅覆盖计数上线之后的群聊客户端请求。 */
    @GetMapping
    public ApiResponse<List<FeishuApiUsageMapper.Usage>> usage() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        return ApiResponse.ok(mapper.ranking(today.withDayOfMonth(1), today));
    }
}
