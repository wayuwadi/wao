import { createRouter, createWebHistory } from 'vue-router'
import AppLayout from '@/layouts/AppLayout.vue'
import { useSessionStore } from '@/stores/session'

export function legacyNotificationSettingsRedirect() {
  return { path: '/settings', query: { tab: 'notifications' }, replace: true } as const
}

const router = createRouter({
  // Static release paths are independent of the public application's routes.
  history: createWebHistory('/'),
  routes: [
    {
      path: '/connection-recovery',
      name: 'connection-recovery',
      component: () => import('@/views/ConnectionRecoveryView.vue'),
      meta: { public: true },
    },
    {
      path: '/breathe',
      alias: '/login',
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
      path: '/auth/claim',
      name: 'auth-claim',
      component: () => import('@/views/AccountClaimView.vue'),
      meta: { public: true },
    },
    {
      path: '/download',
      name: 'client-download',
      component: () => import('@/views/ClientDownloadView.vue'),
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
      path: '/desktop-pet',
      name: 'desktop-pet',
      component: () => import('@/views/DesktopPetView.vue'),
      meta: { public: true },
    },
    {
      path: '/hub',
      component: () => import('@/layouts/HubLayout.vue'),
      meta: { public: true },
      children: [{ path: '', name: 'wao-hub', component: () => import('@/views/HubView.vue') }],
    },
    {
      path: '/pets',
      component: () => import('@/layouts/HubLayout.vue'),
      meta: { public: true },
      children: [
        { path: '', name: 'pets', component: () => import('@/views/PetRepositoryView.vue') },
        {
          path: 'create',
          name: 'pet-create',
          component: () => import('@/views/PetCreateView.vue'),
          meta: { public: false },
        },
        {
          path: 'manage',
          name: 'pet-manage',
          component: () => import('@/views/PetRepositoryView.vue'),
          meta: { public: false },
        },
        { path: ':id', name: 'pet-detail', component: () => import('@/views/PetDetailView.vue') },
      ],
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
          path: 'features',
          name: 'changelog',
          component: () => import('@/views/FeatureExplorerView.vue'),
          meta: { module: 'changelog', tabKind: 'module' },
        },
        {
          path: 'changelog',
          redirect: '/features',
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
          path: 'wao/hub',
          component: () => import('@/layouts/HubLayout.vue'),
          meta: { module: 'skills', tabKind: 'module' },
          children: [
            { path: '', name: 'embedded-hub', component: () => import('@/views/HubView.vue') },
          ],
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
          path: 'notes',
          name: 'notes',
          component: () => import('@/views/NotesView.vue'),
          meta: { module: 'notes', tabKind: 'module' },
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
          path: 'files',
          name: 'files',
          component: () => import('@/views/FilesView.vue'),
          meta: { module: 'files', tabKind: 'module' },
        },
        {
          path: 'reference-images',
          name: 'reference-images',
          component: () => import('@/views/ReferenceImagesView.vue'),
          meta: { module: 'reference-images', tabKind: 'module' },
        },
        {
          path: 'automation',
          name: 'automation',
          component: () => import('@/views/AutomationView.vue'),
          meta: { module: 'automation', tabKind: 'module' },
        },
        {
          path: 'request-monitoring',
          name: 'request-monitoring',
          component: () => import('@/views/RequestMonitoringView.vue'),
          meta: { module: 'request-monitoring', tabKind: 'module' },
        },
        {
          path: 'text-polish',
          name: 'text-polish',
          component: () => import('@/views/TextPolishView.vue'),
          meta: { module: 'text-polish', tabKind: 'module' },
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
        { path: 'media-agent', component: () => import('@/views/NotFoundView.vue') },
        { path: 'apps/media-agent', component: () => import('@/views/NotFoundView.vue') },
        { path: 'apps/weknora', component: () => import('@/views/NotFoundView.vue') },
        { path: 'apps/jumpserver', component: () => import('@/views/NotFoundView.vue') },
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
          path: 'notification-settings',
          redirect: legacyNotificationSettingsRedirect,
        },
        {
          path: 'settings',
          name: 'settings',
          component: () => import('@/views/SettingsView.vue'),
          meta: { module: 'settings', tabKind: 'module' },
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue'),
      meta: { public: true },
    },
  ],
})

router.beforeEach(async (to) => {
  // 安装包分发无需会话；服务端临时不可用也不应阻塞公开下载页。
  if (to.name === 'client-download' || to.name === 'connection-recovery' || to.name === 'not-found')
    return true
  const session = useSessionStore()
  await session.initialize()
  if (session.connectionError && !to.meta.public) {
    return { name: 'connection-recovery', query: { redirect: to.fullPath }, replace: true }
  }
  if (!to.meta.public && !session.authed) {
    return { name: 'login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : undefined }
  }
  return true
})

export default router
