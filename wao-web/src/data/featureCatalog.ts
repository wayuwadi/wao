import type { FeatureCatalogEntry, ReleaseEdition } from '@/types/featureExplorer'

/**
 * 功能名称、图标和路由仍以 MODULES 为权威；这里只补充面向成员的介绍文案与展示分组。
 * 收录项必须已经存在真实浏览器路由，不能用规划中的能力填充目录。
 */
export const FEATURE_CATALOG: FeatureCatalogEntry[] = [
  {
    id: 'changelog',
    category: 'system',
    summary: '先看最新变化，再按目标探索全部能力。',
    description: '最新功能、既有能力和详细说明集中在同一页。',
    tone: 'violet',
    recommended: true,
  },
  {
    id: 'projects',
    category: 'ai',
    summary: '目标、任务、成员与交付结果集中协作。',
    description: '把一个目标拆成任务，关联 AI 运行和验收结果，让进展与责任保持清楚。',
    tone: 'mint',
    recommended: true,
  },
  {
    id: 'notes',
    category: 'knowledge',
    summary: '随手写、安心存，也能在全屏里专注整理。',
    description: '记录临时想法和长期内容；草稿、保存和失败重试状态清楚可见。',
    tone: 'amber',
    recommended: true,
  },
  {
    id: 'knowledge',
    category: 'knowledge',
    summary: '把可复用资料整理成长期知识。',
    description: '按知识库和目录维护资料，在授权范围内供成员与 AI 检索使用。',
    tone: 'mint',
  },
  {
    id: 'reference-images',
    category: 'knowledge',
    summary: '收集截图与视觉参考，保留真实上下文。',
    description: '让产品参考图、错误截图和视觉证据不再散落，后续工作可直接引用。',
    tone: 'rose',
  },
  {
    id: 'todo',
    category: 'planning',
    summary: '把今天要推进的事项放在清楚的位置。',
    description: '查看和维护个人待办，按当前工作节奏决定下一步行动。',
    tone: 'blue',
  },
  {
    id: 'calendar',
    category: 'life',
    summary: '查看重要日期、安排与经过授权的提醒。',
    description: '把日历、重要日期和工作日信息组织在统一时间视图中。',
    tone: 'rose',
  },
  {
    id: 'automation',
    category: 'system',
    summary: '让重复任务按计划运行并保留结果。',
    description: '查看自动化配置、最近运行和失败状态，在需要处理时获得明确入口。',
    tone: 'blue',
  },
  {
    id: 'text-polish',
    category: 'system',
    summary: '保留原文的同时获得更清楚的表达。',
    description: '润色结果不覆盖原文，可快速切换、重试并核对处理状态。',
    tone: 'violet',
  },
]

/** 当前前端制品携带的精选说明；只有 id 变化时才会成为新的自动介绍批次。 */
export const CURRENT_RELEASE: ReleaseEdition = {
  id: 'release-highlights-feature-explorer-v1',
  publishedOn: '2026-09-27',
  title: '这次，WAO 更容易被看懂了',
  intro:
    '先用一分钟看看最值得关注的变化，再回到自己的工作。你可以随时跳过，也能从“版本与更新”重新打开。',
  highlights: [
    {
      featureId: 'changelog',
      eyebrow: '功能探索',
      title: '新功能、旧功能，一处看全',
      summary: '最新变化放在前面，全部能力按目标分类；每张卡片都说明它能帮你完成什么。',
      points: ['从本次更新继续探索全部功能', '功能名称、图标和路由复用真实功能地图'],
    },
    {
      featureId: 'notes',
      eyebrow: '小记',
      title: '写着写着，空间自然变大',
      summary: '长内容可以进入全屏继续写；保存成功后自然回到原位置，失败时保留现场和草稿。',
      points: ['原文和草稿始终保留', '窄屏也有完整编辑空间'],
    },
  ],
}
