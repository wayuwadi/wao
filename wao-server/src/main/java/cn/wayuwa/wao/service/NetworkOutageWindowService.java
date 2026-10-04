package cn.wayuwa.wao.service;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.dto.NetworkOutageWindowDto;
import cn.wayuwa.wao.dto.SaveNetworkOutageWindowRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 系统预期断网窗口。自动任务可统一读取该服务，避免在已知无网络时制造失败记录。
 *
 * <p>窗口按上海时间判断，开始时间包含、结束时间不包含，并支持例如 23:30–06:00 的跨午夜配置。
 */
@Service
public class NetworkOutageWindowService {

    public static final String ENABLED_KEY = "network.outage-window.enabled";
    public static final String START_KEY = "network.outage-window.start";
    public static final String END_KEY = "network.outage-window.end";
    public static final String DEFAULT_START = "00:00";
    public static final String DEFAULT_END = "06:00";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final SettingService settings;
    private final Clock clock;

    @Autowired
    public NetworkOutageWindowService(SettingService settings) {
        this(settings, Clock.system(ZONE));
    }

    NetworkOutageWindowService(SettingService settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    /** 返回当前配置；首次使用默认启用每天 00:00–06:00。 */
    public NetworkOutageWindowDto get() {
        Map<String, String> values = settings.getAll(Set.of(ENABLED_KEY, START_KEY, END_KEY));
        String enabled = values.get(ENABLED_KEY);
        return new NetworkOutageWindowDto(!"false".equalsIgnoreCase(enabled),
                validOrDefault(values.get(START_KEY), DEFAULT_START),
                validOrDefault(values.get(END_KEY), DEFAULT_END));
    }

    /**
     * 原子保存断网窗口。
     *
     * @param request 启用状态和每日起止时间
     * @return 保存后的规范化配置
     */
    @Transactional
    public NetworkOutageWindowDto save(SaveNetworkOutageWindowRequest request) {
        LocalTime start = parse(request.startTime(), "断网开始时间格式不正确");
        LocalTime end = parse(request.endTime(), "断网结束时间格式不正确");
        if (start.equals(end)) throw new BadRequestException("断网开始和结束时间不能相同");
        settings.put(ENABLED_KEY, String.valueOf(request.enabled()));
        settings.put(START_KEY, start.format(TIME_FORMAT));
        settings.put(END_KEY, end.format(TIME_FORMAT));
        return get();
    }

    /** 断网时段已下线，自动任务不再受历史窗口限制。 */
    public boolean isUnavailable() {
        return false;
    }

    /**
     * 兼容既有调用方；断网时段下线后指定时间也不限制自动任务。
     *
     * @param time 上海本地时间
     * @return 始终为 false
     */
    public boolean isUnavailable(LocalTime time) {
        // 功能已下线，历史配置不再阻塞自动任务；不删除持久化设置。
        return false;
    }

    private static String validOrDefault(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return LocalTime.parse(value, TIME_FORMAT).format(TIME_FORMAT);
        } catch (DateTimeParseException ignored) {
            return fallback;
        }
    }

    private static LocalTime parse(String value, String message) {
        try {
            return LocalTime.parse(value, TIME_FORMAT);
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new BadRequestException(message);
        }
    }
}
