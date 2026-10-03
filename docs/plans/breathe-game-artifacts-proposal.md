# ADR 草案：页面内单机游戏与独立静态制品

状态：提议，整体设计确认后编号采纳；本轮不改变部署行为。对应[设计 A2](../specs/breathe-seven-games-design.md)。

## 已核实的约束

ADR-049 的单仓库继续有效；本提案调整其全量前端制品耦合，**不恢复旧独立 wao-game 仓库/服务/数据库**。ADR-040 的账号游戏厅不适用于本次免登录本机游戏；新增命名空间，不触碰其数据。旧 `/game/**` 重定向保留，新资源使用 `/play-assets/**`。

`ci-build-frontends.sh` 当前只委托 `ci-frontend-build.sh wao-web`；`ci-change-plan.sh` 的未知路径强制全量。`static-release.py` 目前校验 Git SHA、index.html/assets，按 SHA 安装单一主站目录，没有游戏 catalog 或单游戏激活。不能声称现在已经支持分游戏发布。

2026-10-03 公网 version.json 标记 windows1-development、SHA 54f742d1；本机存在 public-release-coordinator、临时静态传输工具。ADR-291 是 Linux2 目标拓扑，不能用历史地址直接部署。正式执行前只读复核协调者、现有批次和工具，阶段A不触发发布/改Nginx/重启服务。

## 决策提案

同仓库、Vue3/TypeScript/Vite，游戏独立构建，同源 iframe 嵌入 /breathe。当前仅建立 README 与 demos；按顺序增加以下文件，不创建七套空工程：

```text
wao-web/src/components/relax/     纯呼吸首页、静坐次级入口、彩虹游戏入口、轻量容器适配
wao-game/
  demos/                        设计专用，永不进入正式 catalog
  games/dino/                   第1款逻辑/render/input/audio/UI/tests/assets
  games/<后续gameId>/            到该款时才创建
  host/                         父页加载器与存档服务（轻量）
  protocol/                     v1 schema/消息校验/兼容测试
  shared/                       实際出现重复时才提取，不建通用游戏引擎
  tools/                        选择性构建/清单/依赖图/测试
  package.json + package-lock.json
```

iframe 能隔离 CSS 与输入焦点，销毁容易停止资源，独立路径可回滚；成本是父子通信、焦点可达性、存档协商需要测试。原生Vue组件嵌入能少一次通信但CSS/快捷键和主站构建耦合较强，不选。跨域iframe导致存储分区/权限与账号风险，不选。同源iframe不被当作安全沙箱，代码只从受控清单加载，不能开放任意URL。iframe title有游戏名，无需popups/top-navigation权限；浏览器原生全屏由父页控制。

第一款 Canvas2D，无大型依赖。其余按需要 Canvas2D/SVG；竞速提案 WebGL + Three.js，主站已使用 three，但游戏独立打包。新增/升级依赖需在该款核心Demo阶段记录锁定版本、许可证、构建后raw/gzip、首帧内存与维护成本，再确认是否符合ADR；本轮不安装依赖或承诺体积。

## 协议 v1 提案

封套 `{channel:'wao-game', protocol:1, gameId, instanceId, seq, type, payload}`。

父→子：init（经schema验证快照/保存模式/主题/尺寸/静音）、pause(reason)、resume、viewport、theme、audio、snapshot-request、dispose。
子→父：ready（支持协议/规则区间）、state（running/paused/ended）、checkpoint、snapshot-result、save-request、save-status-ack、disposed、error。

逐消息字段白名单、类型/范围/大小校验；`event.origin===location.origin` 且 `event.source===iframe.contentWindow`，gameId/随机instanceId与当前实例一致；seq防乱序、requestId配对确认；postMessage明确targetOrigin，绝不用`*`。不接收JS、HTML、任意URL或任意存储key。协议超时先暂停显示重试；不能挂着loading自动转发输入。原子导入成功后销毁旧实例，用确认快照新建；dispose超时直接移除 iframe，保留父页最后确认快照及用户可导出未写入状态。

存档由父页IndexedDB CAS事务持有，游戏独立版本升级不改变key。游戏规则版本与通信版本分开；目录manifest声明支持hostProtocol、readSaveRules、writeSaveRules。旧页面只加载启动时锁定的 catalog 和游戏SHA，不在进行中热切。

## 制品与发布

每游戏 `play-assets/<gameId>/<fullGitSHA>/index.html`、哈希assets、`game-manifest.json`：gameId、gitSha、artifactHash、protocol兼容范围、rules版本、构建工具版本、逐文件长度与SHA256。catalog只有已正式发布游戏映射，包含revision、previousRevision与各游戏版本；新游戏不存在时旧游戏继续显示。所有文件先校验再进入不可变目录，catalog最后原子替换。拒绝路径穿越、软链接、重复文件/超限/私密后缀；同路径不同哈希拒绝覆盖。

| 改动 | 构建/测试/发布范围 |
|---|---|
| docs、demos | 只校验文档/Demo，不激活任何正式制品 |
| games/dino 专属 | dino与实际依赖，不构建主站/其他游戏/后端 |
| wao-web专属 | 主站，复用已发布catalog和游戏制品 |
| shared或协议 | 从版本化依赖图求受影响游戏；协议还跑主站兼容测试 |
| lockfile/公共工具 | 比较依赖影响，无法证明时扩大到全部游戏，不能漏测 |
| 后端 | 按现有发布门禁，与纯游戏路径区分 |

首次需要主站轻量容器与dino制品协调上线：先安装游戏和路由→验证不可见制品→主站识别v1（未激活仍显示旧游戏）→CAS激活catalog→公网验收。各步骤失败保留旧入口；不要先删旧游戏再等新包。发布锁属于所有通道共同协调者，主站/游戏合并时按catalog revision检测并发，不能读取旧catalog整包覆盖别的游戏更新。

Jenkins适配 `ci-change-plan.sh`、构建与安装的明确游戏路径及测试；流水线脚本变化自身需相称全量验证，但生产静态激活不得因此重启后端。现用Windows临时通道也调用同一个安全打包/安装/catalog激活工具；如通道缺少可安全安装游戏目录的权限，不能绕过或扩大权限，报告具体缺口后再发布。

回滚只回退对应游戏catalog指针，不回滚其他游戏、不删除旧资源；存档规则须向后可读或提供迁移备份及旧版恢复入口。游戏活动页面始终引用不可变版本。首次保留全部游戏旧资源，不加自动清理任务。

公网完成证据：主站版本包含集成SHA、catalog指向目标游戏SHA、对应manifest完整、抽样关键JS/CSS/素材哈希一致、健康code=0/protocol=1、真实/breathe能打开该游戏且无资源404。运行日志说明是Jenkins还是当前静态通道，合并不等于上线。

## 测量与门禁

第一款实现前测同机同Node条件下当前主站冷/热构建时间、制品raw/gzip、/breathe初始JS/CSS和游戏进入前请求。拆分后重复测主站专属、单游戏、共享改动的构建；发布分别记录上传/校验/切换/公网验证时间。首次基线尚未测量，不宣称加速比例。验收必须证明主站更新不重建七款、单款更新不覆盖其他游戏、旧页面仍能懒加载旧资源、rollback能真实运行。
