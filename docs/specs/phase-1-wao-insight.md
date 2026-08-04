# Phase 1：娃的洞见接入

## 目标

将独立产品仓库 `wayuwacn/wao-insight` 的前端 Demo 接入 WAO。登录成员从一级菜单“娃的洞见”进入后，在 WAO 页签内使用同域 iframe 打开独立应用；Insight 继续独立构建和发布，不进入 WAO 前端源码与发布产物。

## 首版范围

- 外部应用稳定标识为 `wao-insight`，菜单名称为“娃的洞见”，默认放在“公共”分组。
- WAO 入口为 `/apps/wao-insight`，iframe 地址为 `/insight/`。
- Insight 前端构建产物由独立仓库发布到云前端节点 `/var/www/wao-insight/dist`，并自行维护 `/etc/nginx/wao-external-apps.d/wao-insight.conf`。
- Demo 暂时使用仓库内演示数据；没有真实服务端健康接口，因此注册表首版不配置 `healthUrl`，以页面加载结果作为可用性判断。
- 外部应用注册表从版本 1 升级到版本 2。读取旧版注册表时自动补入“娃的洞见”，保留已有应用的名称、分组、排序和显隐；用户在版本 2 中主动删除后不再恢复。

## 不在首版范围

- 不把 Insight Demo 代码复制进 `wao-web`。
- 不实现聊天资产分析、方案孵化、承诺落库等真实后端接口。
- 不新增独立登录体系；未来接入真实接口时继续沿用 ADR-038 的同域共享 Session 约定。

## 验收

1. 旧版外部应用注册表升级后出现“娃的洞见”，原有注册记录保持不变。
2. 点击菜单打开独立页签，iframe 加载 `/insight/`，不出现 WAO 主站递归嵌套。
3. Insight 页面刷新和新窗口打开均能加载静态资源。
4. WAO 前端测试、后端单元测试以及 Insight 的类型检查、单元测试和生产构建全部通过。
