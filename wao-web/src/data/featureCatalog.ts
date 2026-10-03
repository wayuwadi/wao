import type { FeatureCatalogEntry, ReleaseEdition } from '@/types/featureExplorer'
import releaseHistory from './releaseHistory.json'

/**
 * 功能名称、图标和路由仍以 MODULES 为权威；这里只补充面向成员的介绍文案与展示分组。
 * 收录项必须已经存在真实浏览器路由，不能用规划中的能力填充目录。
 */
export const FEATURE_CATALOG: FeatureCatalogEntry[] = [
  {
    id: 'tianren',
    category: 'life',
    summary: '首页先预览，再展开今日卦象与解读。',
    description: '查看真实卦象、已保存的 AI 解读和往日记录；关注点自动保存为本机草稿。',
    tone: 'violet',
  },
  {
    id: 'home',
    category: 'life',
    summary: '卦象与 Codex 信息集中查看，留一点空白再开始。',
    description: '查看今日卦象与 Codex 统计，右键邀请伙伴跳舞，从自己的旧小记获得灵感，原地写下新想法。',
    tone: 'blue',
  },
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
    description: '把一个目标拆成任务，记录进展和验收结果，让进展与责任保持清楚。',
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

/** 构建 HEAD 自动生成的说明；随版本变化，不依赖人工精选。 */
export const CURRENT_RELEASE = __WAO_BUILD_RELEASE__

/** 经确认的更新档案；相同日期保持清单中的发布顺序，不覆盖历史记录。 */
export const RELEASE_HISTORY = [CURRENT_RELEASE, ...(releaseHistory as ReleaseEdition[]).filter(item => item.id !== CURRENT_RELEASE.id)]
