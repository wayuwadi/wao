# 本地工程一致性检查

完成开发、提交前运行，提前发现 Git 不会报告的同号文件和失效文档路径：

```bash
git fetch origin main
# 若当前分支尚未包含最新 main，先按正常流程同步并解决冲突。
python3 deploy/scripts/check-engineering-consistency.py --base origin/main
```

仅依赖 Python 3 标准库和 Git，不连接数据库，不启动服务，不修改文件，不自动 fetch、rebase 或重编号。基线必须是本地可解析的提交，程序输出完整 SHA。程序不会替你判断远端是否刚有新提交；推送前仍需刷新基线。

## 输出与处理

| 结果 | 退出码 | 处理 |
| --- | ---: | --- |
| 无错误，可含历史 ADR 警告 | 0 | 保留警告说明，继续相称的功能验收与提交检查 |
| 本次编号冲突、migration 改写、失效链接或未同步基线 | 1 | 修复或同步，再运行 |
| Git 引用不可用、编码/读取异常、文件指向仓库外 | 2 | 修复检查环境，不视为通过 |

机器读取可追加 `--json`，正常完成的报告包含 `base`、`status`、`counts` 和 `issues`。历史 ADR 重号在完全没有增加同号文件时保留为警告；新增第三篇同号 ADR 会失败。任何 migration 重号始终失败，即使已经存在于基线中。

已有 migration 按基线文件字节比较，修改、删除和重命名都会失败。工具不判断该文件是否真的在生产执行过，保守遵守“已交付 migration 不改写”的约定；通过新增 migration 演进数据。

检查的是当前工作区（包含 Git 跟踪文件与未被忽略的新文件），不是单独的暂存区快照。提交前仍需检查 `git diff --cached`，确认暂存内容与已验收工作区一致；它不代替 Maven 中的 `FlywayMigrationsTest` 或用户链路验收。

## Markdown 范围

只检查本次新增或修改的 `docs/**/*.md`、根目录 `README.md` 与 `AGENTS.md`，包括尚未暂存的新文件。支持常见内联链接、图片链接、引用式目标、尖括号包裹的空格路径、百分号编码与一层括号。链接相对当前文档解析，`/` 开头按仓库根目录解析。

跳过 fenced code block、行内代码、外部 URL 和仅锚点链接；不联网，不验证标题锚点、HTML 链接、动态生成目标或完整 Markdown 语法。未修改文档的历史坏链接不在本次扫描范围内。遇到特殊语法应人工复核，不把脚本通过等同于全库链接无误。

## 自检与首次扫描

```bash
python3 -m unittest discover -s deploy/scripts -p 'test_engineering_consistency.py'
```

测试使用独立临时 Git 仓库，覆盖分支并行时的语义冲突。规格见 [工程一致性检查](../specs/engineering-consistency-check.md)，首次 WAO 扫描与已有问题见 [验收记录](../validation/engineering-consistency-check-2026-09-19.md)。
