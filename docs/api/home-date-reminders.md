# 首页日期提醒 HTTP 契约

仅本人浏览器会话，未登录 401，不回落到默认成员。沿用 `ApiResponse<T>`；没有新增工作区 MCP 权限。

- `GET /api/home/date-reminders` 返回 `{date,notices}`。date 为上海今天；notice 为 `{key,kind,subject,name,date,daysBefore,title,detail,targetPath,priority}`，key 由来源、实际发生日、提前阶段组成。无备注、年龄。
- `POST /api/home/date-reminders/reserve` 输入 `{date,keys}`（1–8项，每项≤128字符）；返回 `{date,token,notices}`。原子取得二十秒租约，重查来源/开关，被其他设备占用可返回空；旧日期 409。
- `POST /api/home/date-reminders/receipt` 输入 `{date,keys,token,state}`，状态为 SHOWN/CONFIRMED/RELEASED。可见后写 SHOWN，明确确认写 CONFIRMED，未展示释放仅处理本 token 的 RESERVED。无效/过期租约、变化来源和跨日 409；确认幂等，不触碰其他通知。
- `GET /api/home/date-reminders/settings` 返回 `{enabled,solarTerms,revision}`，默认 true/true/0。
- `PUT /api/home/date-reminders/settings` 同结构，revision 条件保存；冲突 409，相同内容重试返回当前值。

既有 `/api/calendar/important-dates` 请求和 items 响应增加 `eventKind`（IMPORTANT/BIRTHDAY/ANNIVERSARY）、`birthdaySubject`（SELF/FAMILY/OTHER）、`homeReminderEnabled`。创建省略为 IMPORTANT/OTHER/true；旧客户端修改省略保留已存值，非生日对象统一 OTHER。原飞书校验、提前0–30天、阴阳历和年度重复不变。

内部来源路由为 `/calendar?date=发生日&importantDateId=事项id` 或 `holidayCode=节气稳定代码`，对应日期窗就绪后打开真实详情。

V187 增加三个非空兼容默认字段。配置、展示租约和回执复用 `member_preference`；专用键禁止通用 `/api/preferences/{key}` PUT 修改。不新增外部投递或调度器。
