package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.service.WeatherService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/weather")
public class WeatherController {
    private final WeatherService service;
    private final CurrentMember current;
    public WeatherController(WeatherService service, CurrentMember current) { this.service = service; this.current = current; }
    @GetMapping("/locations")
    public ApiResponse<List<WeatherService.Location>> locations(@RequestParam String query, HttpServletRequest http) {
        requireMember(http); return ApiResponse.ok(service.locations(query));
    }
    @GetMapping("/forecast")
    public ApiResponse<WeatherService.Forecast> forecast(@RequestParam double latitude, @RequestParam double longitude, HttpServletRequest http) {
        requireMember(http); return ApiResponse.ok(service.forecast(latitude, longitude));
    }
    private void requireMember(HttpServletRequest http) {
        if (current.id(http) == null) throw new UnauthorizedException("请先登录");
    }
}
