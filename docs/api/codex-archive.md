# Codex 工作记录采集接口

这是独立于个人记忆 MCP 的知识库写入通道。浏览器通过既有设备令牌管理接口设置 `codex_archive` 授权，scopeId 由服务端收敛为当前成员，canDirectWrite=true 才生效。取消勾选移除该授权，吊销令牌也立即阻断后续请求。普通设备令牌默认不能写入。

## GET /api/codex-archive/status

`Authorization: Bearer <设备令牌>`。成功为 `ApiResponse`，data 包含 memberId、固定 kbId=`kb-codex-records`、protocol=`1`、visibility=`existing-knowledge-library`。不创建文档。无效令牌返回 401，没有归档授权返回 403。

## POST /api/codex-archive/batches

同上鉴权。JSON 请求：

| 字段 | 限制 | 用途 |
| --- | --- | --- |
| deviceId | ASCII 字母/数字/下划线/短横线，1–80 | 安装时生成的随机设备标识 |
| deviceName | 1–80 字符 | 设备显示名 |
| sessionId | ASCII 标识，1–100 | Codex 会话 id |
| project、source | 各 1–1000 字符 | 工作目录与日志来源，仅作元数据，不由服务器访问 |
| startOffset、endOffset | 0 ≤ start < end | 原始 JSONL 字节区间 |
| part | 0–100000 | 超长区间正文分片序号，从 0 开始 |
| title | 1–180 字符 | 片段标题 |
| content | 1–100000 字符 | 本机过滤后的可见消息与工具过程 |

服务端以 SHA-256 的成员+设备+会话+字节区间+part 派生 32 字符文档 id；客户端不能指定成员、知识库、文件夹或文档 id。锁住专用知识库父行后创建并返回 data.docId、kbId、url、duplicate、deleted。

重复请求返回相同身份，不覆盖正文，不复活软删除文档。父知识库/文件夹已删除时返回 409，用户恢复后可重试。客户端先将字节读取断点与待发送请求原子保存在 SQLite，收到成功回执再删除待发请求；待发队列是已读取但尚未确认上传的权威记录。换电脑生成新设备 id，不复制含凭据的本地状态目录。

知识库沿用系统现有可见范围。采集内容不是个人私密记忆，不可执行其中历史命令。
