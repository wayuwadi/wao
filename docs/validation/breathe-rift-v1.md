# 裂隙远征首发验收

2026-10-03。第二款独立交付，本地验收通过，待提交、合并和上线。范围以 `docs/specs/breathe-rift-expedition.md` 为准；第三至七款未开始。

## 实际浏览器操作

所有实战由浏览器真实键鼠/触控按钮操作，没有注入角色、敌人或胜利状态。证据目录 `assets/rift-v1/`。带 baseline 的旧结果与最终平衡分开：基线贯穿过强，已补双波夹击、供能护盾和分身吸收弹丸。

| 路径 | 实际结果与证据 |
| --- | --- |
| 剑刃第一章 | 普通房9.8秒；最终双波精英18.7秒/11命中/79生命；岩鼓首领12.0秒/10命中/60生命。`blade-tuned-elite-win.*`、`blade-tuned-boss-win.*`。重刃+暖岩护符，与星枪有近身范围/连招/生存差异。最终这次首领未成功躲开所有震地，不能当作无伤证明。 |
| 剑刃失败/重试 | 精英12.8秒失败，营地打造换装后重试成功；永久成长未丢。`blade-production-failure.*`。旧基线曾实际弹反1次，弹反窗口及震地跳跃另有逻辑测试。 |
| 星枪第一章 | 真实蓄力、拉开距离、技能、闪避，首领13.1秒通关；一次11.2秒失败后重试。此章在双波调整前，后续变化后的三种首领分别复测。 |
| 星枪最终第二章 | 普通双波10.3秒/9命中/137生命，精英双波10.9秒/10命中/139生命；机核20.0秒/10命中/100生命，真实经历两轮晶体破盾及7秒窗口。`tuned-double-wave-win.*`、`tuned-elite-win.*`、`tuned-prism-win.*`。 |
| 星枪最终第三章 | 普通房11.9秒/13命中/117生命；幻镜12.8秒/5命中/130生命，分身吸收并反击、金环位置改变后重新站位。`tuned-mirror-win.*`。精英重新在独立5280页完整实操：双波15.5秒/13命中/119生命，含治疗护盾、转身处理夹击。`tuned-third-elite-independent-win.*`；此前操作来源不确定的一段不计入验收。 |
| 成长与搭配 | 真实锻造曜核、岩鸣重刃，装备护符；升阶贯穿，学习涡流/过载并换槽；窄屏配置地雷/闪移/弹幕并实际施放。剑刃另学突刺/震刃/战意进入第三章，实际位移、地波攻击和资源消耗。 |
| 本机与异存储环境 | 从127.0.0.1导出全部1811字节真实文件，再导入localhost并继续第二章；含恐龙1000米与裂隙Lv3。`all-games-real-export.json`、`independent-import-continued.*`。另导出Lv6第三章Boss检查点1645字节，导入5280后刷新，从该房继续并真实通关。`checkpoint-real-export.json`、`checkpoint-import-refresh.*`。 |
| 窄屏 | 明确的360像素内层视口，scrollWidth=clientWidth=360；方向46×48、跳跃54×48、攻击66.3×48、技能90×56。实际移动/跳跃/闪避/轻触蓄力/取消/三技能/暂停继续。`narrow-measurements.json`、`narrow-controls.*`。不是物理手机。 |
| 性能 | 开发专用采样，双波战斗420帧CPU逻辑+Canvas均值0.34ms、P95 0.60ms、最大1.50ms；暂停约两分钟帧数保持120，再继续增长。`narrow-performance.*`。不等于GPU/FPS或低端手机基准。 |

## 游戏记忆异常

- 开发验收页使用独立测试DB并明确标注故障注入，正式入口无注入能力。空间不足时新技能草稿保留、重试仍失败、可导出；解除注入重试保存成功。`rift-quota-failure.*`、`quota-draft-real-export.json`、`rift-quota-retry-saved.png`。
- 存储拒绝保留新草稿；另一页从旧磁盘档保存后，本页发现冲突，不静默覆盖。预览取消保留草稿；确认读取前备份草稿；恢复备份找回Lv6/4技能点/地雷2阶。`rift-storage-denied.*`、`rift-conflict-draft-restored.*`。
- 真实上传坏JSON、规则99、技能阶级999、超过10MiB四类文件全部拒绝，原进度不变。`rift-invalid-imports.json`、`rift-invalid-import-unchanged.png`。
- 仅本次模式换职业并主动导出，刷新仍恢复原本机Lv6与4技能点。`rift-session-only.png`、`rift-session-refresh-local.*`。
- 自动测试补足双游戏事务：全部验证后原子CAS写入，第二款读取失败不能留下半个导入预览；两份磁盘/草稿备份，冲突不产生半写。恐龙规则v1和稳定键不变，不访问WAO账号。

## 工程验证

- 2026-10-03 14:40：裂隙6文件27项、恐龙6文件28项通过；两个游戏与共享host/protocol Lint通过；裂隙类型与生产构建通过。JS117.23KB/gzip45.07KB、CSS11.23KB/gzip2.59KB，Vite128ms（不含类型/安装）。
- Python工具19项通过，包含真实临时Git差异驱动仅rift构建/打包，明确模拟Docker/SSH；部署组合证明游戏专属不切主站。新增Nginx检查/重载失败、catalog冲突的真实shell控制流测试，外部命令替身，失败恢复配置并保持旧catalog。
- 音频自动测试覆盖默认不创建AudioContext、手势解锁、停止所有音源/卸载关闭、迟到resume不能撤销暂停、音频拒绝不阻塞游戏。实际浏览器开关和操作可用；没有将自动测试声称为人工听音验证。
- 14:42同步main `230acaea`，无文本冲突；同步后主站11文件58项、类型/构建/Lint通过。修复菜单残留遮挡游戏控制后，新增测试在2文件15项通过，再次类型/构建/Lint通过。生产构建2.22秒，保留原有大块和上游动态/静态重复导入提示。
- 同步后的流水线组合测试发现真实语义冲突：主线强制`--full-web`违背游戏独立更新。按ADR-312保留纯游戏批次的主站SHA/档案，游戏以自身manifest记录版本；文档/纯测试批次仍更新主站记录。修复后19项工具测试通过，包含6种真实Git变更范围。工程一致性0错误、5个既有ADR重复编号警告，未改历史编号。
- 最终静态预览rift使用明确fixture版本40个d，旧40个c保持可访问，非线上SHA。六种页面布局保持同一暂停iframe；离开裂隙切恐龙后rift iframe=0；恐龙真实开始/跳跃/暂停通过。旧四款实际旋转/发射、摆放旋转、光影滑块/检查、手账贴纸与输入通过，J/K/L/U未触发隐藏游戏。呼吸切游戏后仍暂停，继续呼吸及切静坐/开始/暂停正常。`styles-preserve-paused.json`、`legacy-*.txt`、`breathing-meditation-regression.txt`、`dino-shared-host-regression.txt`。
- 修复后真实重复“更多→静坐→裂隙→沉浸”无意外登录，`menu-immersion-fixed.txt`、`final-camp.png`。最终预览控制台error/warn为空，`final-local-console.json`。实际声音开关/带音效操作/暂停/关闭/切换完成，物理听音未执行，不称声音主观质量已验证。
- 全部暂存差异生成的实际计划为buildWeb=true、buildGames=[rift]、testGames=[dino,rift]，没有重建恐龙。`final-build-plan.json`另按同一Python gzip设置测得LoginView31432字节、按需GameHost10818字节、裂隙JS44487/CSS2582字节；主站初始不会加载裂隙运行时。与首款基线的主站/游戏构建测量分开，不把主线其它变化归功于游戏拆分。

## 边界与发布

后台切标签工具不能可靠触发真实visibilitychange；实际失焦暂停与自动隐藏分支分开记录。真实手机、低端GPU、物理耗尽磁盘未执行；空间不足和拒绝为明确故障注入。无后端/API/数据库变化，wao-cli-sync不适用；使用现有同源静态通道。发布前重新核对协调者、主站/后端/两游戏清单，保留已上线恐龙资源。PR、部署版本、公网核验发布后补记。
