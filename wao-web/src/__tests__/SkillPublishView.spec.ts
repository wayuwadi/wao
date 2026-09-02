import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import SkillPublishView from '@/views/SkillPublishView.vue'

const api = vi.hoisted(() => ({
  createSkillDraft: vi.fn<() => Promise<unknown>>(),
  fetchMySkills: vi.fn<() => Promise<unknown>>(),
  fetchPublisher: vi.fn<() => Promise<unknown>>(),
  fetchSkillCategories: vi.fn<() => Promise<unknown>>(),
  inspectSkillPackage: vi.fn<() => Promise<unknown>>(),
  savePublisher: vi.fn<() => Promise<unknown>>(),
  submitSkill: vi.fn<() => Promise<unknown>>(),
}))
vi.mock('@/api/skills', () => api)

async function render() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/skills/publish', component: SkillPublishView, meta: { skillBase: '/skills' } },
      { path: '/skills/manage', component: { template: '<div />' } },
    ],
  })
  await router.push('/skills/publish')
  await router.isReady()
  const wrapper = mount(SkillPublishView, {
    global: { plugins: [router], stubs: { RouterLink: false } },
  })
  await flushPromises()
  return wrapper
}

async function selectFolder(wrapper: Awaited<ReturnType<typeof render>>) {
  const file = new File(['---\nname: Parsed Skill\ndescription: Parsed summary\n---'], 'SKILL.md', {
    type: 'text/markdown',
  })
  Object.defineProperty(file, 'webkitRelativePath', { value: 'parsed-skill/SKILL.md' })
  const input = wrapper.get('input[webkitdirectory]').element as HTMLInputElement
  Object.defineProperty(input, 'files', { configurable: true, value: [file] })
  await wrapper.get('input[webkitdirectory]').trigger('change')
  await flushPromises()
}

describe('Skill 发布预检', () => {
  beforeEach(() => {
    localStorage.clear()
    Object.values(api).forEach((mock) => mock.mockReset())
    api.fetchPublisher.mockResolvedValue({ handle: 'wao-author', displayName: 'WAO 作者' })
    api.fetchSkillCategories.mockResolvedValue([{ key: 'office-efficiency', name: '办公效率' }])
    api.fetchMySkills.mockResolvedValue([])
  })

  it('先选文件夹，再用 SKILL.md 自动预填发布信息', async () => {
    api.inspectSkillPackage.mockResolvedValue({
      name: 'Parsed Skill',
      description: 'Parsed summary from frontmatter',
      suggestedSlug: 'parsed-skill',
      unpackedSize: 80,
      fileCount: 1,
      sha256: 'a'.repeat(64),
      findings: [],
    })
    const wrapper = await render()

    expect(wrapper.text()).toContain('第一步：选择 Skill 文件夹')
    expect(wrapper.find('input[maxlength="80"]').exists()).toBe(false)
    await selectFolder(wrapper)

    expect(api.inspectSkillPackage).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('安全检查通过')
    expect((wrapper.get('input[maxlength="80"]').element as HTMLInputElement).value).toBe(
      'Parsed Skill',
    )
    expect(
      (wrapper.get('input[placeholder="meeting-summary"]').element as HTMLInputElement).value,
    ).toBe('parsed-skill')
    expect((wrapper.get('textarea[maxlength="500"]').element as HTMLTextAreaElement).value).toBe(
      'Parsed summary from frontmatter',
    )
  })

  it('敏感信息命中时展示具体位置并可复用已选文件 Pass', async () => {
    api.createSkillDraft.mockResolvedValue({
      id: 'skill-1', displayName: 'Unsafe', summary: 'Unsafe package', versions: [],
    })
    api.inspectSkillPackage.mockResolvedValue({
      name: 'Unsafe',
      description: 'Unsafe package',
      suggestedSlug: 'unsafe',
      unpackedSize: 80,
      fileCount: 1,
      sha256: 'b'.repeat(64),
      findings: ['scripts/geo_db.py:42：检测到疑似密码或密钥配置'],
    })
    const wrapper = await render()

    await selectFolder(wrapper)

    expect(wrapper.text()).toContain('安全扫描拦截了以下位置')
    expect(wrapper.text()).toContain('scripts/geo_db.py:42')
    expect(wrapper.find('input[maxlength="80"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('确认信息并创建草稿')

    await wrapper.get('.warning-pass').trigger('click')

    expect(wrapper.text()).toContain('已 Pass 安全扫描警告')
    expect(wrapper.find('input[maxlength="80"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('确认信息并创建草稿')
    expect(api.inspectSkillPackage).toHaveBeenCalledOnce()

    await wrapper.get('select').setValue('office-efficiency')
    await wrapper.get('.rights input').setValue(true)
    await wrapper.get('.actions button').trigger('click')
    await flushPromises()

    expect(api.createSkillDraft).toHaveBeenCalledWith(
      expect.objectContaining({ scanOverrideConfirmed: true }),
      null,
      expect.arrayContaining([expect.any(File)]),
      expect.any(Function),
    )
    expect(api.inspectSkillPackage).toHaveBeenCalledOnce()
  })

  it('连续选择时只采用最后一个包的预检结果', async () => {
    let resolveFirst: ((value: unknown) => void) | undefined
    api.inspectSkillPackage
      .mockReturnValueOnce(new Promise((resolve) => { resolveFirst = resolve }))
      .mockResolvedValueOnce({
        name: 'Latest Skill', description: 'Latest summary', suggestedSlug: 'latest-skill',
        unpackedSize: 80, fileCount: 1, sha256: 'c'.repeat(64), findings: [],
      })
    const wrapper = await render()

    await selectFolder(wrapper)
    await selectFolder(wrapper)
    expect((wrapper.get('input[maxlength="80"]').element as HTMLInputElement).value).toBe('Latest Skill')

    resolveFirst?.({
      name: 'Stale Skill', description: 'Stale summary', suggestedSlug: 'stale-skill',
      unpackedSize: 80, fileCount: 1, sha256: 'd'.repeat(64), findings: [],
    })
    await flushPromises()
    expect((wrapper.get('input[maxlength="80"]').element as HTMLInputElement).value).toBe('Latest Skill')
  })
})
