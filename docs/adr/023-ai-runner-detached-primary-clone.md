# ADR-023：AI Runner 主克隆保持游离 HEAD，默认分支只归任务 worktree

## 状态

已采纳。

## 背景

ADR-021 定下的工作区结构是：每台执行电脑在 `<root>/<工程文件夹>` 保留一个主克隆，每次运行
再从它派生 `<root>/.wao-runs/<run-id>` 独立 worktree。主克隆是 `git clone` 出来的，HEAD 一直
指向 `main`，于是 Git 认为 `main` 已经被主克隆占用。

Codex / Claude Code 在 worktree 里做基线核对时，最自然的动作就是 `git switch main` 或
`git checkout main && git pull`，结果稳定报错：

```
fatal: 'main' is already checked out at '/home/wadi/CodeSpace/wao'
```

AI 会把这条错误当成环境异常反复重试、改用奇怪的绕行方式（自己再建一个 worktree），甚至直接
判定阻塞退出，任务因此「一直失败且没有把改动推上去」。同时 `.wao-runs/` 下的历史工作区从不
回收，每个都是一份完整源码副本，磁盘会被慢慢占满。

## 决策

- 主克隆只当对象仓库用：新克隆加 `--no-checkout`，且每次准备工作区时都把 HEAD 切成游离
  状态（`git checkout --detach`）。已有的旧克隆在下一次运行时被自动纠正，不需要重新克隆。
  游离 HEAD 只改写 HEAD 引用，不改动任何工作区文件。
- 默认分支的名字因此永远空闲，任务 worktree 可以自由 `switch`、`checkout`、`pull`。
- 任务提示里显式声明工作区已经就绪：分支已基于最新 `origin/<默认分支>` 创建，禁止另建
  worktree，需要比对基线时读 `origin/<默认分支>` 而不是检出它。
- `.wao-runs/` 下的运行工作区保留 7 天供人工复查，超期在下一次准备工作区时自动回收。回收
  通过 worktree 的 `.git` 指针反查所属主克隆，因此多个项目共用同一个 `.wao-runs/` 也不会误删。

## 后果

- AI 不会再因为分支占用浪费轮次或误判阻塞，`git switch main` 这类习惯动作直接可用。
- 主克隆不再有工作副本，每台电脑每个项目省下一份完整源码；历史工作区有上限，磁盘可预测。
- 主克隆目录不能再当人工开发目录用（本来也不应该）；需要人工查看代码请进
  `.wao-runs/<run-id>`，或者另外克隆一份。
- 回收窗口是 7 天：想长期留存某次运行的现场，需要在到期前手工复制出去。
