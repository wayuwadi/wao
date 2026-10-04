<script setup lang="ts">
import {
  computed,
  defineAsyncComponent,
  h,
  onBeforeUnmount,
  ref,
  watch,
  type AsyncComponentLoader,
} from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import {
  fetchWorkspaceCapabilities,
  fetchWorkspaceMcpTokens,
  type WorkspaceCapability,
  type WorkspaceMcpToken,
} from '@/api/workspaceMcp'
import {
  fetchSkills,
  fetchMySkills,
  fetchSkillCategories,
  type SkillCard,
  type MySkill,
  type SkillCategory,
} from '@/api/skills'
import { fetchPets, fetchMyPets, type PetCard } from '@/api/pets'
import WorkspaceConnectionPanel from '@/components/capabilities/WorkspaceConnectionPanel.vue'
import ApplicationConnectionsPanel from '@/components/capabilities/ApplicationConnectionsPanel.vue'
import '@/styles/capabilityResources.css'

function resourcePanel(loader: AsyncComponentLoader) {
  return defineAsyncComponent({
    loader,
    delay: 0,
    timeout: 20000,
    loadingComponent: {
      render: () => h('p', { role: 'status', class: 'cap-muted' }, '正在加载工作台…'),
    },
    errorComponent: {
      render: () =>
        h('div', { role: 'alert', class: 'cap-error' }, [
          '工作台加载失败，已保存的本机草稿仍保留。',
          h('button', { type: 'button', onClick: () => location.reload() }, '刷新重试'),
        ]),
    },
  })
}
const SkillDetailPanel = resourcePanel(
  () => import('@/components/capabilities/resources/SkillDetailPanel.vue'),
)
const SkillPublishPanel = resourcePanel(
  () => import('@/components/capabilities/resources/SkillPublishPanel.vue'),
)
const SkillManagePanel = resourcePanel(
  () => import('@/components/capabilities/resources/SkillManagePanel.vue'),
)
const SkillReviewsPanel = resourcePanel(
  () => import('@/components/capabilities/resources/SkillReviewsPanel.vue'),
)
const PetDetailPanel = resourcePanel(
  () => import('@/components/capabilities/resources/PetDetailPanel.vue'),
)
const PetCreatePanel = resourcePanel(
  () => import('@/components/capabilities/resources/PetCreatePanel.vue'),
)
const PetRepositoryPanel = resourcePanel(
  () => import('@/components/capabilities/resources/PetRepositoryPanel.vue'),
)
const route = useRoute(),
  router = useRouter(),
  session = useSessionStore()
const endpoint = `${location.origin}/api/mcp/workspace`
type CapabilityType = '全部' | 'CLI' | 'MCP' | 'Skill' | '应用接入' | 'Pet'
interface Entry {
  id: string
  type: CapabilityType | 'CLI / MCP'
  name: string
  description: string
  status: string
  icon: string
  skill?: SkillCard
  pet?: PetCard
  token?: WorkspaceMcpToken
  mine?: boolean
}
const capabilities = ref<WorkspaceCapability[]>([]),
  catalogError = ref(''),
  catalogLoading = ref(false)
const skills = ref<SkillCard[]>([]),
  pets = ref<PetCard[]>([]),
  mySkills = ref<MySkill[]>([]),
  myPets = ref<PetCard[]>([]),
  tokens = ref<WorkspaceMcpToken[]>([])
const loading = ref(false),
  error = ref(''),
  keyword = ref(''),
  category = ref(''),
  platform = ref(''),
  sort = ref('newest'),
  categories = ref<SkillCategory[]>([])
const page = ref(1),
  hasMore = ref(false),
  catalogPane = ref('overview'),
  selected = ref('cli')
const pendingType = ref<'Skill' | 'Pet'>('Skill')
const area = computed(() =>
  route.path.includes('/publish/') || route.path.includes('/reviews/')
    ? 'published'
    : ['connections', 'published'].includes(String(route.query.area))
      ? String(route.query.area)
      : 'discover',
)
const type = computed<CapabilityType>(() => {
  if (
    route.path.includes('/skills/') ||
    route.path.includes('/publish/skill') ||
    route.path.includes('/reviews/skill')
  )
    return 'Skill'
  if (route.path.includes('/pets/') || route.path.includes('/publish/pet')) return 'Pet'
  return ['CLI', 'MCP', 'Skill', '应用接入', 'Pet'].includes(String(route.query.type))
    ? (String(route.query.type) as CapabilityType)
    : '全部'
})
const publishKind = computed(() =>
  route.path.includes('/publish/skill')
    ? 'Skill'
    : route.path.includes('/publish/pet')
      ? 'Pet'
      : null,
)
const admin = computed(() => ['管理员', '超级管理员'].includes(session.currentUser.role))
const types = computed<CapabilityType[]>(() =>
  area.value === 'published'
    ? ['全部', 'Skill', 'Pet']
    : area.value === 'connections'
      ? ['全部', 'CLI', 'MCP', '应用接入']
      : ['全部', 'CLI', 'MCP', 'Skill', '应用接入', 'Pet'],
)
const builtin: Entry[] = [
  {
    id: 'cli',
    type: 'CLI',
    name: 'WAO CLI',
    description: '在终端操作项目、任务与文档',
    status: '现有能力',
    icon: 'ri-terminal-box-line',
  },
  {
    id: 'mcp',
    type: 'MCP',
    name: 'WAO MCP',
    description: '让 AI 客户端调用你的 WAO',
    status: '现有能力',
    icon: 'ri-plug-line',
  },
  {
    id: 'app',
    type: '应用接入',
    name: '外部应用 Skill',
    description: '外部账号与能力接入',
    status: '规划中',
    icon: 'ri-apps-2-line',
  },
]
const entries = computed<Entry[]>(() => {
  let items: Entry[]
  if (area.value === 'connections')
    items = [
      {
        id: 'new',
        type: type.value === 'CLI' ? 'CLI' : 'MCP',
        name: '新建连接',
        description: '逐项选择操作与数据范围',
        status: '权限草稿',
        icon: 'ri-add-circle-line',
      },
      ...tokens.value.map((token) => ({
        id: token.id,
        type: token.clientType ?? ('CLI / MCP' as const),
        name: token.name,
        description: `${token.permissionMode === 'granular' ? (token.permissions?.length ?? 0) + ' 项操作' : token.canWrite ? '整体读写' : '只读'} · ${token.tokenPrefix}••••`,
        status: token.permissionMode === 'granular' ? '逐项授权' : '旧版授权',
        icon: 'ri-key-2-line',
        token,
      })),
      ...builtin.filter((e) => e.id === 'app'),
    ]
  else if (area.value === 'published')
    items = [
      ...mySkills.value.map((skill) => ({
        id: skill.id,
        type: 'Skill' as const,
        name: skill.displayName,
        description: skill.summary,
        status: statusName(skill.status),
        icon: 'ri-sparkling-line',
        mine: true,
      })),
      ...myPets.value.map((pet) => ({
        id: pet.id,
        type: 'Pet' as const,
        name: pet.displayName,
        description: pet.description,
        status: pet.listed ? '已公开' : '私人 / 已下架',
        icon: 'ri-bear-smile-line',
        pet,
        mine: true,
      })),
    ]
  else
    items = [
      ...builtin,
      ...skills.value.map((skill) => ({
        id: skill.id,
        type: 'Skill' as const,
        name: skill.displayName,
        description: skill.summary,
        status: `v${skill.version}`,
        icon: 'ri-sparkling-line',
        skill,
      })),
      ...pets.value.map((pet) => ({
        id: pet.id,
        type: 'Pet' as const,
        name: pet.displayName,
        description: pet.description,
        status: `图集 v${pet.spriteVersion}`,
        icon: 'ri-bear-smile-line',
        pet,
      })),
    ]
  return items.filter(
    (e) =>
      (type.value === '全部' ||
        e.type === type.value ||
        (e.type === 'CLI / MCP' && ['CLI', 'MCP'].includes(type.value))) &&
      `${e.name} ${e.description}`.toLowerCase().includes(keyword.value.toLowerCase()),
  )
})
const entry = computed(() => entries.value.find((e) => e.id === selected.value))
const detailKind = computed(() => (route.params.handle ? 'skill' : route.params.id ? 'pet' : null))
const token = computed(() => entry.value?.token)
const requiresLogin = computed(() => !session.authed && area.value !== 'discover')
const groupNames: Record<string, string> = {
  projects: '项目',
  tasks: '任务与子任务',
  knowledge: '知识库与目录',
  documents: '文档',
  pages: 'HTML 发布',
}
const selectedCapabilities = computed(() =>
  capabilities.value.filter((c) => selected.value !== 'mcp' || !!c.mcp),
)
const catalogGroups = computed(() =>
  Object.entries(groupNames)
    .map(([id, name]) => ({
      id,
      name,
      items: selectedCapabilities.value.filter((c) => c.group === id),
    }))
    .filter((group) => group.items.length),
)
let loadGeneration = 0,
  catalogGeneration = 0,
  searchTimer: ReturnType<typeof setTimeout> | undefined
function statusName(value: string) {
  return (
    (
      {
        DRAFT: '草稿',
        PENDING_REVIEW: '待审核',
        PUBLISHED: '已发布',
        REJECTED: '已驳回',
        OFFLINE: '已下架',
      } as Record<string, string>
    )[value] ?? value
  )
}
function msg(value: unknown, fallback: string) {
  return value instanceof Error ? value.message : fallback
}
async function loadCatalog() {
  const ticket = ++catalogGeneration
  catalogLoading.value = true
  catalogError.value = ''
  try {
    const result = await fetchWorkspaceCapabilities()
    if (ticket === catalogGeneration) capabilities.value = result.capabilities
  } catch (cause) {
    if (ticket === catalogGeneration) catalogError.value = msg(cause, '能力目录读取失败')
  } finally {
    if (ticket === catalogGeneration) catalogLoading.value = false
  }
}
async function load(nextPage = 1) {
  const ticket = ++loadGeneration
  loading.value = true
  error.value = ''
  try {
    if (area.value === 'connections') {
      if (session.authed) {
        const result = await fetchWorkspaceMcpTokens()
        if (ticket !== loadGeneration) return
        tokens.value = result
      } else tokens.value = []
      hasMore.value = false
    } else if (area.value === 'published') {
      if (session.authed) {
        const [skillResult, petResult] = await Promise.all([fetchMySkills(), fetchMyPets()])
        if (ticket !== loadGeneration) return
        mySkills.value = skillResult
        myPets.value = petResult
      } else {
        mySkills.value = []
        myPets.value = []
      }
      hasMore.value = false
    } else {
      const [skillResult, petResult] = await Promise.all([
        ['全部', 'Skill'].includes(type.value)
          ? fetchSkills({
              keyword: keyword.value || undefined,
              category: category.value || undefined,
              platform: platform.value || undefined,
              sort: sort.value,
              page: nextPage,
              pageSize: 24,
            })
          : Promise.resolve({ items: [] as SkillCard[], total: 0 }),
        ['全部', 'Pet'].includes(type.value)
          ? fetchPets(keyword.value, nextPage)
          : Promise.resolve({ items: [] as PetCard[], hasMore: false }),
      ])
      if (ticket !== loadGeneration) return
      skills.value = nextPage === 1 ? skillResult.items : [...skills.value, ...skillResult.items]
      pets.value = nextPage === 1 ? petResult.items : [...pets.value, ...petResult.items]
      hasMore.value = skillResult.total > nextPage * 24 || petResult.hasMore
    }
    if (ticket === loadGeneration) page.value = nextPage
  } catch (cause) {
    if (ticket === loadGeneration) error.value = msg(cause, '读取失败，请重试')
  } finally {
    if (ticket === loadGeneration) loading.value = false
  }
}
function rootRoute(nextArea = area.value, nextType = type.value) {
  return {
    path: '/capabilities',
    query: {
      area: nextArea === 'discover' ? undefined : nextArea,
      type: nextType === '全部' ? undefined : nextType,
    },
  }
}
function selectArea(value: string) {
  keyword.value = ''
  selected.value = value === 'connections' ? 'new' : 'cli'
  catalogPane.value = 'overview'
  void router.push(rootRoute(value, '全部'))
}
function selectType(value: CapabilityType) {
  keyword.value = ''
  selected.value =
    value === 'CLI' ? 'cli' : value === 'MCP' ? 'mcp' : value === '应用接入' ? 'app' : ''
  catalogPane.value = 'overview'
  void router.push(rootRoute(area.value, value))
}
function selectEntry(item: Entry) {
  selected.value = item.id
  catalogPane.value = 'overview'
  if (item.skill && !item.mine)
    void router.push({
      path: `/capabilities/skills/@${item.skill.publisher.handle}/${item.skill.slug}`,
      query: { type: 'Skill' },
    })
  else if (item.pet && !item.mine)
    void router.push({ path: `/capabilities/pets/${item.pet.id}`, query: { type: 'Pet' } })
  else if (detailKind.value || publishKind.value || route.path.includes('/reviews/'))
    void router.push(rootRoute())
}
function newConnection() {
  selected.value = 'new'
  catalogPane.value = 'permissions'
  void router.push(rootRoute('connections', '全部'))
}
function login() {
  void router.push({ path: '/login', query: { redirect: route.fullPath } })
}
function publish(kind = pendingType.value) {
  pendingType.value = kind
  void router.push({
    path: `/capabilities/publish/${kind === 'Pet' ? 'pet' : 'skill'}`,
    query: { area: 'published', type: kind },
  })
}
watch(
  [area, type, () => session.userId],
  () => {
    tokens.value = []
    mySkills.value = []
    myPets.value = []
    skills.value = []
    pets.value = []
    void load()
  },
  { immediate: true },
)
watch(
  () => route.path,
  (next, previous) => {
    if (previous.includes('/publish/') && !next.includes('/publish/') && area.value === 'published')
      void load()
  },
)
watch([keyword, category, platform, sort], () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => void load(), 250)
})
watch([entries, () => route.path], ([items]) => {
  if (detailKind.value === 'skill') {
    selected.value =
      items.find(
        (item) =>
          item.skill?.slug === route.params.slug &&
          item.skill?.publisher.handle === String(route.params.handle).replace(/^@/, ''),
      )?.id ?? ''
    return
  }
  if (detailKind.value === 'pet') {
    selected.value = String(route.params.id)
    return
  }
  if (publishKind.value || selected.value === 'new') return
  if (!items.some((e) => e.id === selected.value)) selected.value = items[0]?.id ?? ''
})
void loadCatalog()
void fetchSkillCategories()
  .then((result) => {
    categories.value = result
  })
  .catch(() => {})
onBeforeUnmount(() => {
  loadGeneration++
  catalogGeneration++
  clearTimeout(searchTimer)
})
</script>

<template>
  <main class="cap-center">
    <header class="cap-center-header">
      <div>
        <span class="cap-eyebrow">WAO · 开放与扩展</span>
        <h1>能力中心</h1>
        <p>发现、连接和分享 WAO 的开放能力与资源。</p>
      </div>
      <button
        type="button"
        class="cap-button primary"
        @click="session.authed ? publish() : login()"
      >
        <i class="ri-upload-cloud-2-line" />发布资源
      </button>
    </header>
    <nav class="cap-area-tabs" aria-label="能力中心管理区域">
      <button
        v-for="tab in [
          { id: 'discover', name: '发现能力' },
          { id: 'connections', name: '我的连接' },
          { id: 'published', name: '我的发布' },
        ]"
        :key="tab.id"
        type="button"
        :class="{ active: area === tab.id }"
        :aria-pressed="area === tab.id"
        @click="selectArea(tab.id)"
      >
        {{ tab.name }}
      </button>
    </nav>
    <div class="cap-toolbar">
      <div class="cap-type-filters" aria-label="按类型筛选">
        <button
          v-for="item in types"
          :key="item"
          type="button"
          :class="{ active: type === item }"
          :aria-pressed="type === item"
          @click="selectType(item)"
        >
          {{ item }}
        </button>
      </div>
      <label class="cap-search"
        ><i class="ri-search-line" /><input
          v-model="keyword"
          type="search"
          aria-label="搜索名称或能力"
          placeholder="搜索名称或能力"
          maxlength="100"
      /></label>
    </div>
    <div v-if="type === 'Skill' && area === 'discover'" class="cap-skill-filters">
      <select v-model="category" aria-label="Skill 分类">
        <option value="">全部分类</option>
        <option v-for="item in categories" :key="item.key" :value="item.key">
          {{ item.name }}
        </option></select
      ><select v-model="platform" aria-label="Skill 兼容平台">
        <option value="">全部平台</option>
        <option value="codex">Codex</option>
        <option value="claude-code">Claude Code</option>
        <option value="openclaw">OpenClaw</option></select
      ><select v-model="sort" aria-label="Skill 排序">
        <option value="newest">最近更新</option>
        <option value="downloads">下载最多</option>
        <option value="favorites">收藏最多</option>
      </select>
    </div>
    <div v-if="requiresLogin" class="cap-detail cap-login">
      <h2>登录后管理连接与发布</h2>
      <p class="cap-muted">公开资源仍可在发现能力中浏览。</p>
      <button type="button" class="cap-button primary" @click="login">登录 WAO</button>
    </div>
    <div v-else class="cap-columns">
      <aside class="cap-catalog" aria-label="能力与资源清单">
        <div class="cap-list-heading">
          <span>{{
            area === 'connections'
              ? '设备与客户端'
              : area === 'published'
                ? '我的资源'
                : '能力与资源'
          }}</span
          ><span>{{ entries.length }} 项</span>
        </div>
        <button
          v-if="area === 'connections'"
          type="button"
          class="cap-button primary"
          @click="newConnection"
        >
          新建连接
        </button>
        <div v-if="loading" role="status" class="cap-muted">正在读取…</div>
        <div v-if="error" class="cap-error" role="alert">
          {{ error }} <button type="button" @click="load(page)">重试</button>
        </div>
        <div class="cap-entry-grid">
          <button
            v-for="item in entries"
            :key="item.id"
            type="button"
            class="cap-entry"
            :class="{ active: selected === item.id }"
            :aria-pressed="selected === item.id"
            @click="selectEntry(item)"
          >
            <span class="cap-icon"><i :class="item.icon" /></span
            ><span
              ><strong>{{ item.name }}</strong
              ><small>{{ item.description }}</small
              ><span class="cap-badge">{{ item.type }}</span>
              <span
                class="cap-badge"
                :class="{ warm: item.status === '规划中' || item.status === '旧版授权' }"
                >{{ item.status }}</span
              ></span
            >
          </button>
        </div>
        <p v-if="!loading && !error && !entries.length" class="cap-muted">
          {{
            area === 'published' ? '还没有发布记录，可以先保存私人草稿。' : '没有符合条件的条目。'
          }}
        </p>
        <button
          v-if="hasMore"
          type="button"
          class="cap-button"
          :disabled="loading"
          @click="load(page + 1)"
        >
          {{ loading ? '读取中…' : '加载更多' }}
        </button>
        <p class="cap-catalog-note">
          类型决定详情与管理动作。<br />安装资源不会获得 WAO 账号授权。
        </p>
        <button
          v-if="area === 'published' && admin"
          type="button"
          class="cap-button"
          @click="router.push('/capabilities/reviews/skill')"
        >
          Skill 审核队列
        </button>
      </aside>
      <section class="cap-detail" aria-label="能力详情与管理">
        <div v-if="catalogError" class="cap-error" role="alert">
          {{ catalogError }} <button type="button" @click="loadCatalog">重试能力目录</button>
        </div>
        <div v-if="publishKind" class="cap-resource-type-switch">
          <button
            type="button"
            :class="{ active: publishKind === 'Skill' }"
            @click="publish('Skill')"
          >
            Skill</button
          ><button type="button" :class="{ active: publishKind === 'Pet' }" @click="publish('Pet')">
            Pet
          </button>
        </div>
        <KeepAlive :key="session.userId"
          ><SkillPublishPanel
            v-if="publishKind === 'Skill'"
            :key="`skill-publish:${route.query.skillId ?? 'new'}`"
            :target-skill-id="String(route.query.skillId ?? '')" /><PetCreatePanel
            v-else-if="publishKind === 'Pet'"
            key="pet-create"
        /></KeepAlive>
        <template v-if="!publishKind"
          ><SkillReviewsPanel v-if="route.path.includes('/reviews/') && admin" />
          <p v-else-if="route.path.includes('/reviews/')" class="cap-muted">
            仅管理员可以查看审核队列。
          </p>
          <SkillDetailPanel
            v-else-if="detailKind === 'skill'"
            :key="`${route.params.handle}/${route.params.slug}`"
          />
          <PetDetailPanel v-else-if="detailKind === 'pet'" :key="String(route.params.id)" />
          <ApplicationConnectionsPanel v-else-if="entry?.id === 'app'" />
          <WorkspaceConnectionPanel
            v-else-if="
              (area === 'connections' && entry?.id !== 'app') ||
              (catalogPane === 'permissions' && ['cli', 'mcp'].includes(selected))
            "
            :capabilities="capabilities"
            :token="token"
            :client-type="selected === 'cli' || type === 'CLI' ? 'CLI' : 'MCP'"
            @changed="load()"
            @new-draft="newConnection"
          />
          <template v-else-if="area === 'published'"
            ><SkillManagePanel
              v-if="entry?.type === 'Skill'"
              :selected-id="entry.id"
              @changed="load()"
            /><PetRepositoryPanel
              v-else-if="entry?.type === 'Pet'"
              :mine="true"
              :selected-id="entry.id"
              @changed="load()"
            />
            <div v-else class="cap-empty">
              <span class="cap-icon"><i class="ri-box-3-line" /></span>
              <h2>管理你的作品与草稿</h2>
              <p class="cap-muted">Skill 保留版本与审核，Pet 先私人保存再确认公开。</p>
              <div class="cap-actions">
                <button type="button" class="cap-button primary" @click="publish('Skill')">
                  发布 Skill</button
                ><button type="button" class="cap-button" @click="publish('Pet')">创建 Pet</button>
              </div>
            </div></template
          >
          <template v-else-if="['cli', 'mcp'].includes(selected)"
            ><div class="cap-detail-heading">
              <span class="cap-icon"
                ><i :class="selected === 'cli' ? 'ri-terminal-box-line' : 'ri-plug-line'"
              /></span>
              <div>
                <small>{{ selected === 'cli' ? 'CLI' : 'MCP' }} · WAO 开放能力</small>
                <h2>{{ selected === 'cli' ? 'WAO CLI' : 'WAO MCP' }}</h2>
              </div>
            </div>
            <p class="cap-muted">
              {{
                selected === 'cli'
                  ? '在终端查询、创建和更新内容，或发布单文件 HTML 页面。'
                  : '让 AI 客户端按授权使用你的项目、任务与知识库。'
              }}
            </p>
            <div class="cap-subtabs">
              <button
                type="button"
                :class="{ active: catalogPane === 'overview' }"
                @click="catalogPane = 'overview'"
              >
                能力介绍</button
              ><button
                type="button"
                @click="session.authed ? (catalogPane = 'permissions') : login()"
              >
                逐项授权</button
              ><button
                type="button"
                :class="{ active: catalogPane === 'connect' }"
                @click="catalogPane = 'connect'"
              >
                连接方式
              </button>
            </div>
            <p v-if="catalogLoading" role="status" class="cap-muted">正在读取真实能力目录…</p>
            <template v-if="catalogPane === 'connect'"
              ><h3>连接方式</h3>
              <code>{{
                selected === 'cli' ? 'wao auth login\nwao auth status\nwao tools list' : endpoint
              }}</code>
              <p class="cap-info">
                每台设备使用独立令牌。登录验证后，以实时 tools/list 为准。Windows CLI
                将凭据保存在当前用户加密存储中。
              </p>
              <div class="cap-actions">
                <button
                  type="button"
                  class="cap-button primary"
                  @click="session.authed ? newConnection() : login()"
                >
                  新建连接
                </button>
              </div></template
            ><template v-else
              ><div class="cap-section-heading">
                <h3>目前具备哪些能力</h3>
                <span class="cap-badge">{{ selectedCapabilities.length }} 项业务操作</span>
              </div>
              <details v-for="group in catalogGroups" :key="group.id" class="cap-capability-group">
                <summary>
                  <strong>{{ group.name }}</strong
                  ><span class="cap-muted">{{ group.items.length }} 项 · 查看命令与工具</span>
                </summary>
                <div v-for="item in group.items" :key="item.name" class="cap-operation-description">
                  <h3>{{ item.title }}</h3>
                  <p class="cap-muted">{{ item.description }}</p>
                  <code>{{ item.cli }}</code
                  ><small>{{ item.mcp ?? 'MCP 尚未开放' }}</small>
                </div>
              </details>
              <template v-if="selected === 'cli'"
                ><h3 class="cap-helper-heading">本机辅助命令</h3>
                <code>wao auth login / status / logout<br />wao tools list / call</code></template
              >
              <p class="cap-info">
                创建、更新、移动和发布分别授权。Skill/Pet 上传与管理目前不在 CLI/MCP 工具范围中。
              </p>
              <div class="cap-actions">
                <button
                  type="button"
                  class="cap-button primary"
                  :disabled="!capabilities.length"
                  @click="session.authed ? (catalogPane = 'permissions') : login()"
                >
                  配置逐项权限</button
                ><button type="button" class="cap-button" @click="selectArea('connections')">
                  我的连接
                </button>
              </div></template
            ></template
          >
          <div v-else class="cap-empty">
            <h2>选择一项能力或资源</h2>
            <p class="cap-muted">浏览介绍、版本、授权范围与使用方式。</p>
          </div>
        </template>
      </section>
    </div>
  </main>
</template>

<style scoped>
.cap-center {
  max-width: 1420px;
  margin: 0 auto;
  padding: 28px 28px 50px;
  color: var(--wao-text);
  min-width: 0;
}
.cap-center-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 20px;
}
.cap-center-header h1 {
  font-size: 27px;
  margin: 4px 0;
}
.cap-center-header p {
  font-size: 13px;
  color: var(--wao-text-3);
  margin: 0;
}
.cap-eyebrow {
  font-size: 11px;
  letter-spacing: 0.08em;
  color: var(--wao-primary-text);
}
.cap-area-tabs {
  display: flex;
  gap: 28px;
  border-bottom: 1px solid var(--wao-line);
}
.cap-area-tabs button {
  padding: 11px 0;
  border: 0;
  border-bottom: 2px solid transparent;
  background: none;
  color: var(--wao-text-3);
  font: inherit;
  font-size: 14px;
  cursor: pointer;
}
.cap-area-tabs button.active {
  color: var(--wao-primary-text);
  border-color: var(--wao-primary);
}
.cap-toolbar {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  margin: 18px 0;
}
.cap-type-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.cap-type-filters button {
  border: 0;
  background: transparent;
  color: var(--wao-text-3);
  border-radius: 8px;
  padding: 7px 10px;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
.cap-type-filters button.active {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
.cap-search {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 8px 11px;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  color: var(--wao-text-3);
  width: 230px;
}
.cap-search input {
  font: inherit;
  font-size: 12px;
  background: transparent;
  border: 0;
  width: 100%;
  min-width: 0;
  color: var(--wao-text);
}
.cap-columns {
  display: grid;
  grid-template-columns: 228px minmax(0, 1fr);
  gap: 22px;
  align-items: start;
}
.cap-list-heading {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--wao-text-3);
  margin-bottom: 10px;
}
.cap-entry-grid {
  margin-top: 10px;
}
.cap-entry {
  width: 100%;
  display: flex;
  gap: 10px;
  align-items: start;
  text-align: left;
  padding: 13px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  margin-bottom: 10px;
  cursor: pointer;
}
.cap-entry.active {
  background: var(--wao-primary-soft);
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 1px var(--wao-primary);
}
.cap-entry > span:last-child {
  min-width: 0;
}
.cap-entry strong {
  display: block;
  font-size: 13px;
  overflow-wrap: anywhere;
}
.cap-entry small {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  color: var(--wao-text-3);
  font-size: 11px;
  margin: 4px 0 8px;
}
.cap-catalog-note {
  font-size: 11px;
  line-height: 1.8;
  color: var(--wao-text-3);
  padding: 8px 3px;
}
.cap-detail {
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 14px;
  padding: 22px;
  min-width: 0;
  min-height: 420px;
}
.cap-empty {
  text-align: center;
  padding: 40px 12px;
}
.cap-empty > .cap-icon {
  margin: 0 auto 15px;
}
.cap-empty .cap-actions {
  justify-content: center;
}
.cap-login {
  min-height: 0;
}
.cap-skill-filters {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin: 0 0 17px;
}
.cap-skill-filters select {
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  padding: 8px;
  font: inherit;
  font-size: 12px;
}
.cap-capability-group {
  border-bottom: 1px solid var(--wao-line);
  padding: 15px 0;
}
.cap-capability-group summary {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
  cursor: pointer;
  align-items: center;
  flex-wrap: wrap;
}
.cap-operation-description {
  margin-top: 15px;
  padding: 12px;
  background: var(--wao-bg-sub);
  border-radius: 9px;
}
.cap-operation-description h3 {
  margin: 0;
  font-size: 13px;
}
.cap-operation-description small {
  font-size: 11px;
  overflow-wrap: anywhere;
  color: var(--wao-text-3);
}
.cap-helper-heading {
  font-size: 14px;
  margin: 18px 0 10px;
}
.cap-resource-type-switch {
  display: flex;
  gap: 8px;
  margin-bottom: 17px;
}
.cap-resource-type-switch button {
  font: inherit;
  font-size: 12px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-3);
  border: 0;
  border-radius: 7px;
  padding: 7px 12px;
  cursor: pointer;
}
.cap-resource-type-switch .active {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
@media (max-width: 900px) {
  .cap-center {
    padding: 22px 18px;
  }
  .cap-columns {
    grid-template-columns: 206px minmax(0, 1fr);
    gap: 16px;
  }
  .cap-toolbar {
    flex-direction: column;
    align-items: stretch;
  }
  .cap-search {
    width: auto;
  }
}
@media (max-width: 650px) {
  .cap-center {
    padding: 18px 12px;
  }
  .cap-center-header {
    align-items: start;
    flex-wrap: wrap;
  }
  .cap-area-tabs {
    justify-content: space-between;
    gap: 8px;
  }
  .cap-area-tabs button {
    font-size: 13px;
  }
  .cap-columns {
    grid-template-columns: 1fr;
    gap: 15px;
  }
  .cap-entry-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px;
  }
  .cap-entry {
    margin: 0;
    padding: 10px;
    gap: 7px;
  }
  .cap-entry .cap-icon {
    width: 26px;
    height: 26px;
    border-radius: 7px;
  }
  .cap-entry strong {
    font-size: 12px;
  }
  .cap-catalog-note {
    display: none;
  }
  .cap-detail {
    padding: 15px;
    min-height: 0;
  }
  .cap-search input {
    font-size: 16px;
  }
  .cap-skill-filters select {
    min-width: 0;
    flex: 1 1 100px;
  }
  .cap-capability-group summary {
    align-items: start;
    flex-direction: column;
  }
}
</style>
