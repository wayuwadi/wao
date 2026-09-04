# 天气与日历卦名接口

以下接口须登录，使用 ApiResponse 响应，不接收任意上游 URL。

- `GET /api/weather/locations?query=上海`：2–80 字符，最多 8 个真实城市，返回 name、latitude、longitude。
- `GET /api/weather/forecast?latitude=31.23&longitude=121.47`：校验有限经纬度，精度两位小数，返回 fetchedAt、days（date、code、low、high、rain、wind）。缺失数值返回 null，不解释为零。固定 Open-Meteo 地址、8 秒超时、最多 256 条 30 分钟内缓存。
- `GET /api/tianren/readings/calendar?from=2026-09-01&to=2026-09-30`：本人真实存档，范围不超过 366 天；旧 history 接口不变。
- `GET/PUT /api/preferences/weather-v1`：现有成员偏好契约。JSON 包含 location（name/latitude/longitude 或 null）、rhythmVisible、calendarVisible、readingsVisible。前端草稿按成员隔离。

上游失败不缓存空结果或模拟天气；客户端展示错误与重试。
