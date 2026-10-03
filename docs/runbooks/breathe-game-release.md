# 页面内游戏独立发布

本轮使用 ADR-310。正式包与 Demo 分开，存档不属于制品，任何发布及回滚均不得清理 IndexedDB 或旧版本目录。

## 选择构建范围

`python3 wao-game/tools/change-plan.py BASE HEAD` 返回 `buildWeb/buildGames/testGames`。主站改动不重建游戏；恐龙逻辑改动只测试构建恐龙；容器改动构建主站并回归实际依赖的游戏；协议/schema 扩大相应范围。未知游戏或共享路径拒绝猜测。流水线验证扩大不等于重启后端。

`ci-build-frontends.sh` 按计划安装依赖、执行受影响游戏测试/Lint、主站及单款构建；`ci-deploy.sh` 游戏专属批次只安装及切换游戏清单，不重新生成主站包或改网关路由。首次新增路由需随主站集成一起发布。

## 制品契约与回滚

1. `game-release.py pack wao-game/dist/dino OUT.tgz dino SHA` 生成兼容性记录与逐文件 SHA256。
2. `game-release.py install OUT.tgz ROOT dino SHA` 先临时目录校验，再安装不可变 `ROOT/dino/SHA`。
3. `game-release.py activate ROOT dino SHA --expected-revision REV` 验证并只切换本款。revision 不符必须停下，读取协调记录，不覆盖。
4. `verify-public.py --games dino --version SHA --origin https://wao.wayuwa.cn` 验证公开清单、兼容版本与全部游戏文件哈希。
5. 回滚用同一 activate 命令指向已保留旧 SHA，并使用刚读取的 revision；不要直接复制旧 catalog 覆盖其他游戏。旧页面继续固定旧 URL，新进入游戏才使用新指针。读档规则需兼容。

## 当前临时通道

2026-10-03 现场公网为 Windows 临时后端加云端 Nginx 静态文件；不是 Jenkins 成功发布。公网 Jenkins 匿名接口返回401，不能据此判断其内部构建成功或失败。必须复核 `deploy/data/windows-local/public-release-coordinator.json` 与当前公共版本；已有 publishing 负责人时先复用/等待批次，不抢占。

当前既有 `cloud_access.py` 提供获授权云命令通道，凭据仅在私有运行目录。`cloud-game-stage.py` 通过 `--runtime` 指定该目录，要求协调记录 publishing、authorizedByUser=true 且精确匹配本 SHA。逐块上传只接收固定文件名、严格40位SHA，重试相同字节幂等，冲突拒绝；校验后安装制品。`activate` 仅向当前HTTPS配置添加受限 `/play-assets` 路由，备份旧配置和目录清单，Nginx检查后重载；不重启应用或修改数据库。超时只记录执行编号，先查结果，不能盲目重发。

主站变更复用当前已有临时静态发布流程：确认前版本、打包不可变主站、既有短时传输、校验、关闭传输、原子切换主站指针。操作脚本和传输配置保留在忽略的私有运行目录，不得提交上传密钥。第一款先安装游戏并添加路由，再生成主站传输配置备份，避免主站发布恢复配置时丢失游戏路由。

每个云端操作前重新校验协调者；未知结果、版本变化或开放的其他传输路径均停止切换，记录证据并定位。主站或游戏任一激活失败不能标记批次 complete。公网核验包括主站/游戏各自SHA、入口、资源哈希、健康、实际游戏及存档；记录部署耗时与仍未验证的项目。后端版本应与发布前一致。

## 本地演练证据

`test_ci_composition.py` 用真实 Git 差异和打包器驱动流水线，外部构建/SSH动作明确替身：覆盖游戏、主站、容器三种范围及主站/游戏分离激活。它不是云端发布证据。`test_game_release.py` 覆盖损坏、不可变路径、CAS、其他游戏指针和回滚。

## 第二款与后续独立更新

当前注册 `dino` 和 `rift`。上述pack/install/activate命令的游戏参数可用rift；`cloud-game-stage.py --game rift`使用独立包名与报告名。发布rift不重建、不替换dino版本目录或指针。未传`verify-public.py --games`时核验清单所有已注册游戏；不同版本分别传各自预期SHA，不能把主站SHA强套旧游戏。

rift新增开发/构建脚本不视为依赖变化；包依赖或lock变化则扩大全部游戏验证和构建。共享host只构建主站并测试已注册游戏，游戏逻辑仅构建本款；host引用的catalog/save元数据变更同时构建主站。主站单独发布复用现有游戏制品。测试文件不引起游戏重新打包。

云路由新增采用“备份配置→nginx -t→重载新增路由→CAS激活单款catalog”；重载失败恢复旧配置且不切catalog，catalog冲突也恢复配置。不能先暴露新catalog再验证路由重载成功。`test_cloud_game_stage.py`覆盖成功与三种失败控制流，不冒充云端实机操作。

裂隙本地正式包只有index和哈希资源，host.html/narrow.html/故障注入与性能采样为开发验收专用。构建及公开加载尺寸、最终版本和发布时间记录在`docs/validation/breathe-rift-v1.md`。

ADR-312补充主站Git记录边界：纯游戏制品更新保留原主站SHA与其档案，不为记录更新重建主站；游戏版本由各自manifest/catalog追溯。其他主站/文档/纯测试批次仍按ADR-311刷新主站记录；下一次主站发布收录此前所有游戏提交。`--release-batch`落实此区分，显式`--full-web`仍供确需全主站构建使用。

本地生产静态预览已在40个f与40个e的明确fixture版本之间切换；旧页面保持旧URL，新页面读新版本，真实跑局后回滚仍保留3局纪录与2份备份。见首款验收文档。最终公网版本与实际耗时须在上线后补记。
