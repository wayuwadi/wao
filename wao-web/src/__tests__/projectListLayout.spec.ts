import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { fetchPreference, savePreference } from '@/api/favorites'
import { useProjectListLayoutStore, PROJECT_LIST_LAYOUT_PREFERENCE } from '@/stores/projectListLayout'
import { useSessionStore } from '@/stores/session'
import type { Area, Project } from '@/types'

vi.mock('@/api/favorites', () => ({ fetchPreference: vi.fn(), savePreference: vi.fn() }))

const areas: Area[] = [
  { id: 'career', name: '事业', color: '#4f63df' },
  { id: 'life', name: '生活', color: '#c68d25' },
]
const projects = [
  { id: 'p1', name: '主站优化', area: 'career', goal: '', due: '2026-10-01', progress: 0 },
  { id: 'p2', name: '家庭改造', area: 'life', goal: '', due: '2026-10-10', progress: 0 },
] as Project[]

function setMember(id: string) {
  useSessionStore().account = {
    accountId: id,
    email: '',
    member: { id, name: id, role: '成员', initial: id, color: '#000000' },
  } as never
}

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  setMember('member-a')
  vi.mocked(fetchPreference).mockReset().mockResolvedValue('')
  vi.mocked(savePreference).mockReset().mockResolvedValue(undefined)
})

it('从真实领域初始化分组，个人创建、重命名、折叠和移动都保存到成员偏好', async () => {
  const layout = useProjectListLayoutStore()
  await layout.load(areas, projects)
  expect(layout.state.groups.map((group) => group.label)).toEqual(['事业', '生活'])
  expect(layout.state.groups[0]!.projectIds).toEqual(['p1'])

  expect(layout.addGroup('近期重点')).toBe(true)
  const custom = layout.state.groups.find((group) => group.label === '近期重点')!
  layout.moveProject('p1', custom.id)
  expect(layout.state.groups.find((group) => group.id === custom.id)!.projectIds).toEqual(['p1'])
  layout.renameGroup(custom.id, '本周重点')
  layout.reorderGroup('career', custom.id, true)
  layout.toggleGroup('life')
  await vi.waitFor(() => expect(layout.state.dirty).toBe(false))

  const [key, raw] = vi.mocked(savePreference).mock.calls[vi.mocked(savePreference).mock.calls.length - 1]!
  expect(key).toBe(PROJECT_LIST_LAYOUT_PREFERENCE)
  const saved = JSON.parse(raw) as { groups: { id: string; label: string; projectIds: string[] }[]; collapsedGroupIds: string[] }
  expect(saved.groups.map((group) => group.id)).toEqual(['life', custom.id, 'career'])
  expect(saved.groups.find((group) => group.id === custom.id)).toMatchObject({ label: '本周重点', projectIds: ['p1'] })
  expect(saved.collapsedGroupIds).toEqual(['life'])
})

it('读取保存的分组和项目顺序，并为新增项目自动归入来源领域', async () => {
  vi.mocked(fetchPreference).mockResolvedValue(JSON.stringify({
    version: 1,
    groups: [
      { id: 'custom-focus', label: '近期重点', color: '#4f63df', projectIds: ['p2'] },
      { id: 'career', areaId: 'career', label: '事业', color: '#4f63df', projectIds: ['p1'] },
    ],
    projectOrder: ['p2', 'p1'],
    collapsedGroupIds: [],
    grouping: '不分组',
    sort: '手动排序',
  }))
  const layout = useProjectListLayoutStore()
  await layout.load(areas, projects)
  expect(layout.state.groups.map((group) => group.id)).toEqual(['custom-focus', 'career', 'life'])
  expect(layout.sortProjects(projects).map((project) => project.id)).toEqual(['p2', 'p1'])
  expect(layout.state.grouping).toBe('不分组')

  layout.reconcile(areas, [...projects, { ...projects[0]!, id: 'p3' }])
  expect(layout.state.groups.find((group) => group.id === 'career')!.projectIds).toEqual(['p1', 'p3'])
  await flushPromises()
  expect(vi.mocked(savePreference)).toHaveBeenCalled()
})

it('不分组视图调整总顺序时保留分组归属，并同步各组内部顺序', async () => {
  const layout = useProjectListLayoutStore()
  const allProjects = [...projects, { ...projects[0]!, id: 'p3' }]
  await layout.load(areas, allProjects)
  expect(layout.reorderProjectInList('p2', 'p1')).toBe(true)
  expect(layout.reorderProjectInList('p3', 'p1')).toBe(true)
  expect(layout.sortProjects(allProjects).map((project) => project.id)).toEqual(['p2', 'p3', 'p1'])
  expect(layout.state.groups.map((group) => group.projectIds)).toEqual([['p3', 'p1'], ['p2']])
  expect(layout.state.groups.map((group) => group.id)).toEqual(['career', 'life'])
  expect(layout.state.sort).toBe('手动排序')
})

it('重新读取时移除已不存在项目的布局引用', async () => {
  vi.mocked(fetchPreference).mockResolvedValue(JSON.stringify({
    version: 1,
    groups: [{ id: 'career', areaId: 'career', label: '事业', projectIds: ['p1', 'deleted'] }],
    projectOrder: ['p1', 'deleted'],
  }))
  const layout = useProjectListLayoutStore()
  await layout.load(areas, projects)
  expect(layout.state.groups[0]!.projectIds).toEqual(['p1'])
  expect(layout.state.projectOrder).toEqual(['p1', 'p2'])
})

it('保存失败保留成员本机草稿，重试后清除草稿', async () => {
  vi.mocked(savePreference).mockRejectedValueOnce(new Error('网络不可用'))
  const layout = useProjectListLayoutStore()
  await layout.load(areas, projects)
  layout.moveProject('p2', 'career')
  await vi.waitFor(() => expect(layout.state.error).toBe('网络不可用'))
  expect(localStorage.getItem('wao-project-layout-v1:member-a')).toContain('p2')

  await layout.retry(areas, projects)
  expect(layout.state.dirty).toBe(false)
  expect(localStorage.getItem('wao-project-layout-v1:member-a')).toBeNull()
})

it('保存期间连续移动时串行提交最新布局', async () => {
  let finishFirst!: () => void
  vi.mocked(savePreference).mockImplementationOnce(() => new Promise<void>((resolve) => { finishFirst = resolve }))
  const layout = useProjectListLayoutStore()
  await layout.load(areas, projects)
  layout.moveProject('p2', 'career')
  await flushPromises()
  layout.moveProject('p1', 'life')
  finishFirst()
  await vi.waitFor(() => expect(layout.state.dirty).toBe(false))
  const latestCall = vi.mocked(savePreference).mock.calls[vi.mocked(savePreference).mock.calls.length - 1]!
  expect(latestCall[1]).toContain('"sort":"手动排序"')
  expect(JSON.parse(latestCall[1]).groups.map((group: { projectIds: string[] }) => group.projectIds))
    .toEqual([['p2'], ['p1']])
})

it('成员切换时忽略迟到的旧布局读取', async () => {
  let finish!: (value: string) => void
  vi.mocked(fetchPreference).mockImplementationOnce(() => new Promise((resolve) => { finish = resolve }))
  const layout = useProjectListLayoutStore()
  const pending = layout.load(areas, projects)
  setMember('member-b')
  finish(JSON.stringify({ version: 1, groups: [{ id: 'private', label: '旧成员分组', projectIds: ['p1'] }], projectOrder: ['p1'] }))
  await pending
  expect(layout.state.groups).toEqual([])
  expect(layout.state.loaded).toBe(false)
})
