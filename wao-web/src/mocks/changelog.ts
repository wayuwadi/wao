import type { ChangelogEntry } from '@/types/changelog'

/** 仅用于更新日志 V1 的显式示例预览，不代表真实版本或交付承诺。 */
export const mockChangelogEntries: ChangelogEntry[] = [
  {
    id: 'demo-ai-goals',
    version: 'v0.9.0',
    publishedOn: '2026-09-10',
    title: '给 AI 一个目标，让工作继续向前',
    summary: '从想法到执行，把目标、过程和验收放在同一个工作空间。每一步进展，都有迹可循。',
    categories: ['feature', 'ai', 'workflow'],
    highlights: [
      '围绕目标发起 AI 工作，集中查看执行进展',
      '保留运行记录与交付结果，方便回顾和继续对话',
      '把需要你判断的事项放到验收环节',
    ],
    details:
      '你可以先写下希望完成的结果，再查看执行过程和阶段产出。需要调整方向时，继续对话补充要求；完成后回到任务验收。此条内容用于演示产品更新的写法。',
    tags: ['AI Run', '目标管理', '协作'],
    source: { kind: 'pr', aiGenerated: true },
  },
  {
    id: 'demo-notes',
    version: 'v0.9.0',
    publishedOn: '2026-09-10',
    title: '随手记下的灵感，也有自己的位置',
    summary: '小记拥有独立入口，记录想法、整理资料和回看内容更顺手。',
    categories: ['feature', 'improvement'],
    highlights: ['在固定的小记空间里快速记录', '用标签和搜索找回需要的内容'],
    details:
      '把临时想法收进小记，再按主题整理。更新卡片可以同时归入新功能与优化，方便从不同角度浏览。',
    tags: ['小记', '知识管理'],
    source: { kind: 'manual', aiGenerated: false },
  },
  {
    id: 'demo-todo',
    version: 'v0.8.2',
    publishedOn: '2026-09-08',
    title: '用四象限，把精力留给重要的事',
    summary: '按重要与紧急程度整理待办，让今天应该先做什么更清楚。',
    categories: ['improvement', 'workflow'],
    highlights: ['四象限布局展示待办优先级', '更紧凑的任务卡片，减少来回翻找'],
    details:
      '在同一视图里比较任务优先级，帮助安排一天的工作。版本筛选会同时保留当前分类与搜索条件。',
    tags: ['待办', '效率'],
    source: { kind: 'pr', aiGenerated: true },
  },
  {
    id: 'demo-editor',
    version: 'v0.8.2',
    publishedOn: '2026-09-08',
    title: '长一点的笔记，也能安心编辑',
    summary: '改善富文本列表与保存提示的阅读体验，减少编辑过程中的打断。',
    categories: ['fix'],
    highlights: ['统一列表内容的显示样式', '让保存状态和重试提示更容易发现'],
    details:
      '修复类更新只描述用户可感知的变化。实际问题范围和验证结果将在后续接入真实发布数据时提供。',
    tags: ['编辑器', '稳定性'],
    source: { kind: 'commit', aiGenerated: true },
  },
  {
    id: 'demo-system',
    version: 'v0.8.1',
    publishedOn: '2026-09-03',
    title: '熟悉的工作空间，在不同屏幕上延续',
    summary: '调整窄屏布局与深色模式下的层级，让内容始终清晰可读。',
    categories: ['system', 'improvement'],
    highlights: ['窄屏按阅读顺序排列内容', '沿用全站深浅主题与品牌颜色'],
    details:
      '页面优先使用 WAO 的全局设计变量，按钮、边框和文字随主题切换。示例版本号仅用于展示最近版本导航。',
    tags: ['外观', '多端体验'],
    source: { kind: 'manual', aiGenerated: false },
  },
  {
    id: 'demo-ai-summary',
    version: 'v0.8.0',
    publishedOn: '2026-08-28',
    title: '让每一次回顾，都成为下一步的起点',
    summary: '集中呈现 AI 工作摘要，帮助你快速了解结果并接着推进。',
    categories: ['ai', 'feature'],
    highlights: ['用简明摘要回看最近工作', '围绕阶段结果继续补充目标'],
    details:
      '这条较早的示例用于验证跨月份时间线。AI 整理标识展示未来生成来源，当前所有卡片均为预置示例。',
    tags: ['AI', '工作回顾'],
    source: { kind: 'pr', aiGenerated: true },
  },
]
