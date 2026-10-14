import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { apiDeleteProject } from '@/api/board'
import { seedDataStore } from './helpers/seedDataStore'
vi.mock('@/api/board', async original => ({ ...await original<typeof import('@/api/board')>(), apiDeleteProject: vi.fn() }))

describe('data store · 项目知识库', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('新建项目不再自动生成关联知识库', async () => {
    const data = useDataStore()
    const before = data.kbs.length

    const project = await data.addProject({
      name: '只管理任务的项目',
      area: 'career',
      goal: '验证默认知识库已取消',
      visAll: false,
    })

    expect(data.kbs).toHaveLength(before)
    expect(data.kbs.some((kb) => kb.project === project.id)).toBe(false)
  })
  it('删除写入失败保留本地项目和任务，成功后才移除', async () => {
    seedDataStore()
    const data = useDataStore(), project = data.projects[0]!
    data.apiLive = true
    const tasks = data.tasks.filter(task => task.project === project.id).map(task => task.id)
    vi.mocked(apiDeleteProject).mockRejectedValueOnce(new Error('断网')).mockResolvedValue()
    await expect(data.deleteProject(project.id)).rejects.toThrow('断网')
    expect(data.projectById(project.id)).toBeDefined()
    expect(data.tasks.filter(task => task.project === project.id).map(task => task.id)).toEqual(tasks)
    await data.deleteProject(project.id)
    expect(data.projectById(project.id)).toBeUndefined()
    expect(data.tasks.some(task => task.project === project.id)).toBe(false)
  })
  it('历史项目知识库创建文档不继承项目，删除项目不移除知识库', async () => {
    const data = useDataStore()
    const kb = await data.addKb({ name: '独立资料', project: 'p-old' })
    expect(kb!.project).toBeNull()
    kb!.project = 'p-old'
    const id = await data.addDoc(kb!.id, kb!.folders[0]!.id)
    expect(data.docById(id!)!.links).toEqual([])
    await data.deleteProject('p-old')
    expect(data.kbById(kb!.id)).toBeDefined()
    expect(data.docById(id!)).toBeDefined()
  })

})
