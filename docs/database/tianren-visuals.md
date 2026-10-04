# 天人图意与私有图片

新增迁移 V181，既有起卦、会话和运行表保持兼容。

- `tianren_visual`：`interpretation_run_id` 主键，记录成员、reading、协议、设置快照和权威事实；图意标题、变与不变、scene 独立保存。`requested_attempt_id` 表示当前尝试，`display_attempt_id` 表示已可读的图，更新后者必须比较当前尝试。
- `tianren_visual_attempt`：UUID 主键，每次图片运行对应一条；唯一 `(interpretation_run_id,request_id)` 防止重发，唯一 `image_run_id` 防止同运行重复绑定。保存当次图片提示词与模型快照，以及 PNG `content`、哈希和字节数；上限 10MB、单边最大4096像素。
- 外部列表/状态查询明确选取元数据，不读取 BLOB。原图只在成员、reading、attempt 三项关联校验后读取。
- 文字完成和图意待办与既有运行成功、会话回复在同一事务中提交。图片终态、BLOB 与显示指针也在同一 claim 事务提交；失败运行由既有运行表持久记录，读状态时同步反映。
- `member_preference` 的 TEXT 列保存 `tianren-ai-settings-v1` 聚合 JSON，`version` 递增并使用完整旧值 CAS。首次创建用 insert-if-absent；通用覆盖接口不能写该 key。

本次不清理历史图像、不批量补图、不修改已执行 migration。图片失败和旧图保留不等同于取消已经开始的模型调用。
