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
    description: '最新功能、既有能力和详细说明集中在同一页；更新日记仍负责记录真实交付时间线。',
    tone: 'violet',
    recommended: true,
  },
  {
    id: 'updates',
    category: 'system',
    summary: '按真实交付时间回看 WAO 的变化。',
    description:
      '新功能、修复和体验优化按实际交付日期整理；没有更新的日期不占位，也不用示例内容填充。',
    tone: 'blue',
  },
  {
    id: 'ai-sessions',
    category: 'ai',
    summary: '回看本机 AI 工作，继续之前的上下文。',
    description: '集中查看本机 Codex 与 ChatGPT 会话的状态和摘要，从已经发生的工作继续推进。',
    tone: 'violet',
    recommended: true,
  },
  {
    id: 'chatgpt',
    category: 'ai',
    summary: '在 WAO 中继续 ChatGPT 对话。',
    description: '通过既有会话入口查看和继续对话，不用在多个窗口之间来回寻找。',
    tone: 'blue',
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
    id: 'memory',
    category: 'knowledge',
    summary: '保存跨设备可继续使用的个人记忆。',
    description: '查看长期记忆的来源与状态，个人内容直接维护，组织和项目内容经过审核。',
    tone: 'violet',
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
    id: 'workday-rhythm',
    category: 'planning',
    summary: '安排今天、保持节奏，也为一天留下复盘。',
    description: '围绕当天的重点、承诺和回顾组织工作，而不是堆积一张静态清单。',
    tone: 'mint',
  },
  {
    id: 'action-plans',
    category: 'planning',
    summary: '从阶段目标走到下一步可执行行动。',
    description: '把长期目标拆成可推进的阶段，清楚看见当前所在位置和下一步。',
    tone: 'violet',
  },
  {
    id: 'decisions',
    category: 'planning',
    summary: '把关键选择、依据与后续结果留在一起。',
    description: '记录决策背景和结论，多人修改时保留版本冲突保护。',
    tone: 'amber',
  },
  {
    id: 'calendar',
    category: 'life',
    summary: '查看重要日期、安排与经过授权的提醒。',
    description: '把日历、重要日期和工作日信息组织在统一时间视图中。',
    tone: 'rose',
  },
  {
    id: 'food',
    category: 'life',
    summary: '记录饮食与真实生活数据。',
    description: '围绕实际记录查看饮食情况，不用示例数据替代个人结果。',
    tone: 'amber',
  },
  {
    id: 'appearance',
    category: 'life',
    summary: '管理衣物、搭配与穿着记录。',
    description: '把电子衣库和日常搭配放在一个可持续维护的空间中。',
    tone: 'rose',
  },
  {
    id: 'pet-world',
    category: 'life',
    summary: '在高频工具之外，留一点轻松陪伴。',
    description: '进入松风庭院查看和互动；动效会尊重设备能力和减少动效偏好。',
    tone: 'mint',
  },
  {
    id: 'automation',
    category: 'system',
    summary: '让重复任务按计划运行并保留结果。',
    description: '查看自动化配置、最近运行和失败状态，在需要处理时获得明确入口。',
    tone: 'blue',
  },
  {
    id: 'data-center',
    category: 'system',
    summary: '知道 WAO 保存了什么、从哪里来、能做什么。',
    description: '盘点真实持久化数据和边界，不用代码仓库信息冒充业务数据。',
    tone: 'slate',
  },
  {
    id: 'devices',
    category: 'system',
    summary: '查看已登记终端和真实在线状态。',
    description: '设备状态只表示连接情况，不推断成员行为或工作状态。',
    tone: 'slate',
  },
  {
    id: 'infrastructure',
    category: 'system',
    summary: '查看 WAO 的运行环境和资源状态。',
    description: '了解服务部署位置和机器余量，异常时保留可核对的运行信息。',
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
    {
      featureId: 'updates',
      eyebrow: '更新日记',
      title: '每次变化，都有真实来源',
      summary: '只展示已经确认交付的新功能、修复和体验优化，不用示例内容冒充真实结果。',
      points: ['按真实交付日期整理', '没有更新的日期不会占位'],
    },
  ],
}
