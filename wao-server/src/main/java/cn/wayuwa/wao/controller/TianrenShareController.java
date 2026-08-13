package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.SharedTianrenReadingDto;
import cn.wayuwa.wao.service.TianrenReadingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** “天人一卦”公开只读分享接口；路径位于既有免登录的 /api/share 命名空间。 */
@RestController
@RequestMapping("/api/share/tianren")
public class TianrenShareController {

    private final TianrenReadingService readings;

    public TianrenShareController(TianrenReadingService readings) {
        this.readings = readings;
    }

    /** 按不可猜测的分享凭据读取卦象公开投影。 */
    @GetMapping("/{token}")
    public ApiResponse<SharedTianrenReadingDto> reading(@PathVariable String token) {
        return ApiResponse.ok(readings.shared(token));
    }
}
