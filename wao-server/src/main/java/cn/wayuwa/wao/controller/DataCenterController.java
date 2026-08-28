package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.DataCenterOverviewDto;
import cn.wayuwa.wao.service.DataCenterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 数据中心只读目录接口。 */
@RestController
@RequestMapping("/api/data-center")
public class DataCenterController {

    private final DataCenterService dataCenter;

    public DataCenterController(DataCenterService dataCenter) {
        this.dataCenter = dataCenter;
    }

    /** 返回真实数据源数量、最近写入时间与覆盖边界。 */
    @GetMapping("/overview")
    public ApiResponse<DataCenterOverviewDto> overview() {
        return ApiResponse.ok(dataCenter.overview());
    }
}
