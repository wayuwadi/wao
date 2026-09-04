package cn.wayuwa.wao.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WeatherServiceTest {
    @Test void rejectsNonFiniteAndOutOfRangeCoordinates() {
        assertThatThrownBy(() -> WeatherService.validateCoordinates(Double.NaN, 121)).hasMessageContaining("坐标无效");
        assertThatThrownBy(() -> WeatherService.validateCoordinates(31, Double.POSITIVE_INFINITY)).hasMessageContaining("坐标无效");
        assertThatThrownBy(() -> WeatherService.validateCoordinates(91, 121)).hasMessageContaining("坐标无效");
        assertThatThrownBy(() -> WeatherService.validateCoordinates(31, -181)).hasMessageContaining("坐标无效");
        WeatherService.validateCoordinates(-90, 180);
    }
    @Test void keepsMissingFieldsUnknownAndRealZeroIntact() throws Exception {
        var root = new ObjectMapper().readTree("""
          {"daily":{"time":["2026-09-04","2026-09-05"],"weather_code":[0,null],"temperature_2m_min":[0],"temperature_2m_max":[24],"precipitation_probability_max":[0,null]}}
          """);
        var days = WeatherService.parseForecast(root, "2026-09-04T00:00:00Z").days();
        assertThat(days.get(0).code()).isZero();
        assertThat(days.get(0).rain()).isZero();
        assertThat(days.get(1).code()).isNull();
        assertThat(days.get(1).low()).isNull();
        assertThat(days.get(1).rain()).isNull();
    }
    @Test void refusesEmptyForecastInsteadOfGeneratingDemoData() throws Exception {
        var root = new ObjectMapper().readTree("{}");
        assertThatThrownBy(() -> WeatherService.parseForecast(root, "")).hasMessageContaining("没有返回预报");
    }
}
