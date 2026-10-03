# WAO 页面内游戏

整体 A2 与工程拆分已确认。用户取消逐款 Demo 等待，目前仅开发第一款恐龙荒野，尚未发布。不要将 demos 加入正式游戏入口或发布清单。

- 总进度：[七款交付清单](../docs/plans/breathe-seven-games-progress.md)
- 产品设计：[整体设计与首发范围](../docs/specs/breathe-seven-games-design.md)
- 工程决策草案：[同源容器与独立制品](../docs/plans/breathe-game-artifacts-proposal.md)
- 体验：在此目录运行 `node demos/serve.mjs`，打开 `http://127.0.0.1:5268/`。

Demo 所有图形均为本轮原创 Canvas 绘制。示例数值、示例存档与正式数据隔离。无外部服务、登录、付费、数据库或素材依赖。

`games/dino` 放正式物理/绘制/输入/音频与界面；`host` 放账号无关记忆及容器；`protocol` 放版本化消息校验。运行 `npm ci` 后使用 `npm run dev:dino`；`http://127.0.0.1:5269/host.html` 是明确标注故障注入的开发验收入口，使用独立 IndexedDB `wao-game-memory-browser-fault-test`，不污染正式记忆，仅在开发服务器存在，正式包只有游戏入口。`npm run test:dino`、`npm run build:dino`、`npm run lint:dino` 分别验证逻辑、类型构建和Lint。后六款不得提前铺开实现。
