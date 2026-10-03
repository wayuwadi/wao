# 小记顺手发现运行与验收

全局默认为关闭；首次成员开关必须由设置内明确保存。手动挖掘历史不代表授权。运行时复用本机 Codex 登录、目录独占与动态模型目录，缺少登录或模型时明确失败，不切换供应商，不恢复 Runner。

## 配置与隔离

现有连接配置由调用者加载到 MYSQL_HOST/MYSQL_PORT/DB_NAME/DB_USER/DB_PASS；不要复制凭据入 Git、提示词、日志或命令参数。必须核实该连接确实指向要求的既有服务器库；本机自测库结果单独标记，不能冒充生产库验收。查看 Flyway history 与最新 main 后只应用本需求加法迁移；禁止 clean、改 checksum 或顺带执行别人的 migration。

执行主机显式启用 wao.ai.local-notes.enabled 和 wao.ai.discoveries.enabled，复用有效 Codex 可执行文件、已有 ChatGPT 登录、Vault 加密密钥。产品默认不开放发现调度；成员另行开启。默认模型由目录与本人偏好确定，本次开发模型不是业务配置。

本地脚本 start-local-note-ai.ps1 的 EnableDiscoveries 要求专用端口、StateDirectory、DiscoveryMemberIds、DiscoveryDocumentIds；禁止 18080/19090。local-cloud 关闭共享定时器与自动迁移，脚本关闭发现清理；LocalNoteAiService 的独立 worker 仅推进明确白名单。DocumentIds 可设 __none__ 隔离既有小记自动整理。禁止把白名单留空接生产库进行验收。应用层保存信号同样遵守发现文档白名单，防止本地合成验收被其他实例领取。

前端使用既有 npm run dev:acceptance，通过 WAO_ACCEPTANCE_API 指向专用产品服务，WAO_ACCEPTANCE_PORT 选择空闲端口。正常登录真实成员，不创建伪账号、不复制 Cookie、不绕过认证。只读抽样本人最多 10 篇，明确成员与文档范围；私人输入/输出保留本机受限目录，Git 仅存脱敏结论。

## 可调整工程初值

稳定 120 秒；轮询 20 秒；短事务成员租约 120 秒，推进后释放，在每次轮询重新领取/续期，不在模型等待期间持有数据库锁。每成员一批，全局两次未完成调用；从稳定的待处理文档选至多两篇主题锚点，剩余变更保留下一批。召回最多 40 篇，其中支持池 28，独立解决/复发检索预留 12；最终完整原文六篇/24000 字符。关键反证超过容量则 CONTEXT_LIMIT 延后，不能丢弃反证发布。

回填先 90 天再更早历史，每批 20/每天 200 篇；游标与高水位持久化。每日成员 8 次调用/3 批、全局 60 次；每阶段最多重试一次/每批最多 4 次调用；失败退避 5 分钟，配置检查 15 分钟；执行 300 秒/排队 60 分钟；展示间隔 24 小时/有效 7 天。wao.ai.discoveries.publish-interval-seconds 默认 86400；受限合成验收可临时缩短，必须在结束时恢复，不能用于绕过日调用预算。所有生成、审查、重试实际启动计数，排队先预占，明确未启动才退款；跨日执行重新预占。用量不可获得时为 null，不能当作零花费。

## 证据与恢复

A 合成推演、B 确定性与 MySQL 状态、C 实际模型、D 真实登录链路分别报告。NoteDiscoveryAiEvaluationTest 默认跳过；显式 discovery.ai.eval=true 才调用真实 CLI，首次材料、生成、审查和失败保存本机 .wao/discovery-evaluations。固定五例与 adversarial 用例分开执行，不删失败轮次。

NoteDiscoveryMysqlTest 需要显式 discovery.mysql.eval=true、discovery.mysql.member 指定已登记且没有活跃发现的成员；在租约恢复/并发预算/唯一约束后还原开启状态，登记状态保留为 MYSQL_ACCEPTANCE，不生成业务发现，不读取正文。不得对正在被产品 worker 调度的成员运行这项状态验收。

实际浏览器验证设置保存/读回/暂停，默认无空卡，原地展开/来源返回/收藏/否定撤销，409 与保存失败保留输入、窄屏与键盘；回归既有编辑/草稿恢复/自动保存/标题润色配图。真实小记可合法无结果，但三组合成正例必须有经验证的价值；调用或协议失败不能归类无发现。

重启恢复持久批次和已入队 attempt；不得重复提交有效运行。其他执行主机不抢取当前实例的本机任务。旧 claim 被新 epoch 隔离；暂停递增开启代次，迟到结果不能发布。日志仅记录 ID/状态/哈希/用量/脱敏错误。合并不等于上线；默认不跟进 Jenkins。
