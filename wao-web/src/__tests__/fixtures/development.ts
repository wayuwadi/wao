import type { DevelopmentOverview, DevelopmentRun } from '@/api/development'

export function developmentRun(overrides: Partial<DevelopmentRun> = {}): DevelopmentRun {
  return {
    id: 'run-1',
    featureId: 'notes',
    title: '小记阅读体验',
    goal: '保持书写轻盈，保护草稿。',
    status: 'WAITING_DECISION',
    phase: 'PLAN',
    plan: {
      recommendedIndex: 0,
      goal: '明确下一步目标',
      options: [
        { title: '保留留白', description: '最小可逆调整' },
        { title: '重排说明', description: '需要比较布局' },
        { title: '保持现状', description: '继续积累真实反馈' },
      ],
      reason: '保留现有书写节奏',
    },
    agents: [
      {
        id: 'analysis',
        title: '分析实际体验',
        goal: '核对真实状态',
        status: 'ANALYZING',
        dependsOn: [],
        paths: ['wao-web/src/views/NotesView.vue'],
      },
      {
        id: 'implementation',
        title: '实施选定方案',
        goal: '交付用户选择',
        status: 'QUEUED',
        dependsOn: ['analysis'],
        paths: ['wao-web/src/components/notes'],
      },
    ],
    evidence: null,
    error: null,
    decision: {
      id: 'decision-1',
      revision: 3,
      question: '是否采用保留留白方案？',
      reason: '布局方向需要确认',
      status: 'PENDING',
      deliveryStatus: 'SENT',
      reply: null,
    },
    createdAt: '2026-10-07T10:00:00',
    updatedAt: '2026-10-07T10:05:00',
    version: 5,
    ...overrides,
  }
}
export function developmentOverview(
  overrides: Partial<DevelopmentOverview> = {},
): DevelopmentOverview {
  return {
    canManage: true,
    policy: { enabled: false, version: 1, intervalHours: 4, maxParallel: 3 },
    worker: { online: false, lastSeenAt: null, message: '尚未连接执行电脑' },
    features: [
      { id: 'notes', name: '小记' },
      { id: 'todo', name: '待办' },
    ],
    runs: [],
    ...overrides,
  }
}
