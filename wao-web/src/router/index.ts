import { createRouter, createWebHistory } from 'vue-router'
import AppLayout from '@/layouts/AppLayout.vue'
import { useSessionStore } from '@/stores/session'

export function legacyMemorySettingsRedirect(tab: unknown) {
  if (!['memory-mcp', 'api'].includes(String(tab || ''))) return
  return { path: '/memories', query: { section: 'mcp' }, replace: true } as const
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/auth/feishu/callback',
      name: 'feishu-callback',
      component: () => import('@/views/FeishuCallbackView.vue'),
      meta: { public: true },
    },
    {
      path: '/auth/wechat/callback',
      name: 'wechat-callback',
      component: () => import('@/views/WechatCallbackView.vue'),
      meta: { public: true },
    },
    {
      path: '/auth/claim',
      name: 'auth-claim',
      component: () => import('@/views/AccountClaimView.vue'),
      meta: { public: true },
    },
    {
      path: '/desktop',
      name: 'desktop-download',
      component: () => import('@/views/DesktopDownloadView.vue'),
      meta: { public: true },
    },
    // 文件下载页：公开免登录，但要人点一次并过一道人机校验才换得到一次下载。
    {
      path: '/f/:id',
      name: 'file-download',
      component: () => import('@/views/FileDownloadView.vue'),
      meta: { public: true },
    },
    // 任务分享页：公开只读，免登录。/s/t/:id 含子任务；/s/t/:id/:subId 只含该子任务。
    {
      path: '/s/t/:id/:subId?',
      name: 'share-task',
      component: () => import('@/views/ShareTaskView.vue'),
      meta: { public: true },
    },
    // 文档 / 小记分享页：公开只读，免登录。
    {
      path: '/s/d/:id',
      name: 'share-doc',
      component: () => import('@/views/ShareDocView.vue'),
      meta: { public: true },
    },
    // “天人一卦”结果分享页：凭不可猜测 token 公开只读，不含成员与私有 AI 对话。
    {
      path: '/s/tianren/:token',
      name: 'share-tianren',
      component: () => import('@/views/TianrenShareView.vue'),
      meta: { public: true },
    },
    {
      path: '/skills',
      component: () => import('@/layouts/SkillMarketLayout.vue'),
      meta: { public: true, skillBase: '/skills' },
      children: [
        {
          path: '',
          name: 'skill-market',
          component: () => import('@/views/SkillMarketView.vue'),
          meta: { public: true },
        },
        {
          path: 'publish',
          name: 'skill-publish',
          component: () => import('@/views/SkillPublishView.vue'),
          meta: { public: false },
        },
        {
          path: 'manage',
          name: 'skill-manage',
          component: () => import('@/views/SkillManageView.vue'),
          meta: { public: false },
        },
        {
          path: 'reviews',
          name: 'skill-reviews',
          component: () => import('@/views/SkillReviewsView.vue'),
          meta: { public: false },
        },
        {
          path: ':handle/:slug',
          name: 'skill-detail',
          component: () => import('@/views/SkillDetailView.vue'),
          meta: { public: true },
        },
      ],
    },
    {
      path: '/',
      component: AppLayout,
      children: [
        {
          path: '',
          name: 'home',
          component: () => import('@/views/HomeView.vue'),
          meta: { module: 'home', tabKind: 'module' },
        },
        {
          path: 'navigation',
          name: 'navigation',
          component: () => import('@/views/NavigationView.vue'),
          meta: { module: 'navigation', tabKind: 'module' },
        },
        {
          path: 'projects',
          name: 'projects',
          component: () => import('@/views/ProjectsView.vue'),
          meta: { module: 'projects', tabKind: 'module' },
        },
        {
          path: 'ideas',
          name: 'ideas',
          component: () => import('@/views/IdeasView.vue'),
          meta: { module: 'ideas', tabKind: 'module' },
        },
        {
          path: 'projects/:id',
          name: 'project',
          component: () => import('@/views/ProjectDetailView.vue'),
          meta: { module: 'projects', tabKind: 'project' },
        },
        {
          path: 'wao/skills',
          component: () => import('@/layouts/SkillMarketLayout.vue'),
          props: { embedded: true },
          meta: { module: 'skills', tabKind: 'module', skillBase: '/wao/skills' },
          children: [
            {
              path: '',
              name: 'wao-skill-market',
              component: () => import('@/views/SkillMarketView.vue'),
            },
            {
              path: 'publish',
              name: 'wao-skill-publish',
              component: () => import('@/views/SkillPublishView.vue'),
            },
            {
              path: 'manage',
              name: 'wao-skill-manage',
              component: () => import('@/views/SkillManageView.vue'),
            },
            {
              path: 'reviews',
              name: 'wao-skill-reviews',
              component: () => import('@/views/SkillReviewsView.vue'),
            },
            {
              path: ':handle/:slug',
              name: 'wao-skill-detail',
              component: () => import('@/views/SkillDetailView.vue'),
            },
          ],
        },
        {
          path: 'calendar',
          name: 'calendar',
          component: () => import('@/views/CalendarView.vue'),
          meta: { module: 'calendar', tabKind: 'module' },
        },
        {
          path: 'skill-connections',
          name: 'skill-connections',
          component: () => import('@/views/SkillConnectionsView.vue'),
          meta: { module: 'skill-connections', tabKind: 'module' },
        },
        {
          path: 'todo',
          name: 'todo',
          component: () => import('@/views/TodoView.vue'),
          meta: { module: 'todo', tabKind: 'module' },
        },
        {
          path: 'action-plans',
          name: 'action-plans',
          component: () => import('@/views/ActionPlansView.vue'),
          meta: { module: 'action-plans', tabKind: 'module' },
        },
        {
          path: 'workday-rhythm',
          name: 'workday-rhythm',
          component: () => import('@/views/WorkdayRhythmView.vue'),
          meta: { module: 'workday-rhythm', tabKind: 'module' },
        },
        {
          path: 'weekly-report',
          name: 'weekly-report',
          component: () => import('@/views/WeeklyReportView.vue'),
          meta: { module: 'weekly-report', tabKind: 'module' },
        },
        {
          path: 'life-review',
          name: 'life-review',
          component: () => import('@/views/LifeReviewView.vue'),
          meta: { module: 'life-review', tabKind: 'module' },
        },
        {
          path: 'chat',
          name: 'chat',
          redirect: (to) => ({ path: '/', query: { ...to.query, chat: '1' }, replace: true }),
        },
        {
          path: 'chat-reviews',
          name: 'chat-reviews',
          redirect: (to) => ({
            path: '/',
            query: { ...to.query, chat: '1', review: to.query.review ?? '1' },
            replace: true,
          }),
        },
        {
          path: 'system-reports',
          name: 'system-reports',
          component: () => import('@/views/SystemReportsView.vue'),
          meta: { module: 'system-reports', tabKind: 'module' },
        },
        {
          path: 'knowledge',
          name: 'knowledge',
          component: () => import('@/views/KnowledgeView.vue'),
          meta: { module: 'knowledge', tabKind: 'module' },
        },
        {
          path: 'kb/:id',
          name: 'kb',
          component: () => import('@/views/KbDetailView.vue'),
          meta: { module: 'knowledge', tabKind: 'kb' },
        },
        {
          path: 'memory',
          redirect: { path: '/memories', query: { scope: 'personal' } },
        },
        {
          path: 'memories',
          name: 'memory',
          component: () => import('@/views/MemoryView.vue'),
          meta: { module: 'memory', tabKind: 'module' },
        },
        {
          path: 'workspaces',
          name: 'workspaces',
          component: () => import('@/views/WorkspacesView.vue'),
          meta: { module: 'workspaces', tabKind: 'module' },
        },
        {
          path: 'files',
          name: 'files',
          component: () => import('@/views/FilesView.vue'),
          meta: { module: 'files', tabKind: 'module' },
        },
        {
          path: 'automation',
          name: 'automation',
          component: () => import('@/views/AutomationView.vue'),
          meta: { module: 'automation', tabKind: 'module' },
        },
        {
          path: 'games',
          name: 'games',
          component: () => import('@/views/GamesView.vue'),
          meta: { module: 'games', tabKind: 'module' },
        },
        {
          path: 'request-monitoring',
          name: 'request-monitoring',
          component: () => import('@/views/RequestMonitoringView.vue'),
          meta: { module: 'request-monitoring', tabKind: 'module' },
        },
        {
          path: 'english-learning',
          name: 'english-learning',
          component: () => import('@/views/EnglishLearningView.vue'),
          meta: { module: 'english-learning', tabKind: 'module' },
        },
        {
          path: 'vocabulary',
          name: 'vocabulary',
          component: () => import('@/views/VocabularyView.vue'),
          meta: { module: 'vocabulary', tabKind: 'module' },
        },
        {
          path: 'drawings',
          name: 'drawings',
          component: () => import('@/views/DrawingsView.vue'),
          meta: { module: 'drawings', tabKind: 'module' },
        },
        {
          path: 'tianren',
          name: 'tianren',
          component: () => import('@/views/TianrenView.vue'),
          meta: { module: 'tianren', tabKind: 'module' },
        },
        {
          path: 'dashboard',
          name: 'dashboard',
          component: () => import('@/views/DashboardView.vue'),
          meta: { module: 'dashboard', tabKind: 'module' },
        },
        { path: 'media-agent', redirect: '/apps/media-agent' },
        {
          path: 'apps/:appId',
          name: 'external-app',
          component: () => import('@/views/ExternalAppView.vue'),
          meta: { tabKind: 'external' },
        },
        {
          path: 'quick-links/:linkId',
          name: 'quick-link',
          component: () => import('@/views/QuickLinkView.vue'),
          meta: { tabKind: 'quick-link' },
        },
        {
          path: 'vault',
          name: 'vault',
          component: () => import('@/views/VaultView.vue'),
          meta: { module: 'vault', tabKind: 'module' },
        },
        {
          path: 'settings',
          name: 'settings',
          component: () => import('@/views/SettingsView.vue'),
          beforeEnter: (to) => legacyMemorySettingsRedirect(to.query.tab) || true,
          meta: { module: 'settings', tabKind: 'module' },
        },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async (to) => {
  const session = useSessionStore()
  await session.initialize()
  if (!to.meta.public && !session.authed) {
    return { name: 'login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : undefined }
  }
  if (to.name === 'login' && session.authed) {
    return { path: (to.query.redirect as string) || '/' }
  }
  return true
})

export default router
