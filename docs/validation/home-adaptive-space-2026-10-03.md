# 首页空间调整验收

基线 88443811。2026-10-03，本地5247连接既有18080后端和数据库。

- 1912px：主卡片484px高，文案与350px高伙伴区域并排；状态卡、天气、四个伙伴及全部入口仍可见。
- 390px：文案与280px伙伴区域上下排列，无页面横向溢出。
- 实际打开伙伴菜单并抛球，界面提示“小球接住啦”；未改变接物、纸条、保存逻辑。
- `npm --prefix wao-web run test:unit -- --run src/__tests__/HomeView.spec.ts src/__tests__/companionPlay.spec.ts src/__tests__/companionSpace.spec.ts --maxWorkers=2`：4项通过。
- `npm --prefix wao-web run build`：类型检查与生产构建通过。

重跑：打开首页，检查桌面和手机排布，打开“和伙伴玩”并抛球。接物与草稿完整回归沿用 `docs/runbooks/home-companion-play-regression.md`，本轮没有重新执行全部AI保存链路。
