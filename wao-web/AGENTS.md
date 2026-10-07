# WAO 浏览器界面开发约束

本文件补充根目录 AGENTS，适用于本目录所有界面改动。

- 开始前必读 [界面设计系统](../docs/design/wao-ui-system.md)。品牌 / 设置分别参考 [品牌规范](../docs/design/wao-brand-usage.md)、[设置规范](../docs/design/settings-ui-pattern.md)。已确认方向：留白线框为主、浅底分区为辅、阴影集中在浮层。
- 通用卡片、按钮、字段优先复用 `WaoCard`、`WaoButton`、`WaoField`；弹窗和复杂设置复用 `SettingsDialogShell`、`AiSettingsLayout` 等已有组件。先定位复用入口，再补能力，不从零另造视觉。
- 颜色用 `styles/tokens.css` 的语义变量，圆角 / 间距 / 字体用相应 token。禁止在通用界面新增硬编码颜色、圆角和阴影。图表、品牌等必要例外按具体声明登记 `scripts/ui-style-exceptions.json` 理由，不笼统豁免整个页面。
- UI 改动交付前执行 `npm run ui:check -- --base origin/main`、相称测试、类型检查、构建和浏览器验收。检查明暗主题、320 / 390px、长文本、键盘 / 触屏、加载 / 空 / 草稿 / 保存 / 错误 / 冲突；真实业务链路不可使用虚构数据。
- 规范是跨 Agent 的工程记忆，`/design` 是可看的组件参考；调整公共组件或新语义 token 时同一需求同步两者。设计参考中的演示输入不代表可复用的业务持久化实现。
- 不全站强制换宽度或卡片；保留业务规格、已有草稿防丢和账号隔离。不能用“历史上这样写”作为新增风格例外的理由。
