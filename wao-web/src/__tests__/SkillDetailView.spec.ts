import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SkillDetailView from '@/components/capabilities/resources/SkillDetailPanel.vue'
import { fetchSkill, fetchSkillFile, type SkillDetail } from '@/api/skills'
import { copyText } from '@/utils/clipboard'

vi.mock('vue-router', () => ({
  useRoute: () => ({
    params: { handle: '@skill-hub', slug: 'archery-sql-check' },
    fullPath: '/skills/@skill-hub/archery-sql-check',
  }),
}))

vi.mock('@/stores/session', () => ({
  useSessionStore: () => ({ authed: false, currentUser: {} }),
}))

vi.mock('@/api/skills', async (original) => {
  const actual = await original<typeof import('@/api/skills')>()
  return {
    ...actual,
    fetchSkill: vi.fn<(typeof import('@/api/skills'))['fetchSkill']>(),
    fetchSkillFile: vi.fn<(typeof import('@/api/skills'))['fetchSkillFile']>(),
    setSkillFavorite: vi.fn(),
  }
})

vi.mock('@/utils/clipboard', () => ({ copyText: vi.fn() }))
vi.mock('@/composables/toast', () => ({ toast: vi.fn() }))

const latest = {
  id: 'sv-latest',
  version: '1.0.0',
  displayName: 'Archery SQL 检查',
  summary: '区分本地检查与生产门禁。',
  state: 'PUBLISHED',
  changelog: '首个公开版本',
  license: 'MIT',
  testedPlatforms: ['codex'],
  compatiblePlatforms: ['agent-skills', 'codex'],
  packageSize: 2048,
  unpackedSize: 4096,
  fileCount: 2,
  sha256: 'a'.repeat(64),
  files: [
    { path: 'SKILL.md', size: 128 },
    { path: 'references/guide.md', size: 32 },
  ],
  findings: [],
  scanOverrideConfirmed: false,
  skillMd:
    '---\nname: archery-sql-check\ndescription: safe\n---\n# Archery SQL 检查\n\n先本地检查。',
  reviewNote: '',
  submittedAt: '2026-08-25T10:00:00',
  reviewedAt: '2026-08-26T10:00:00',
}

const old = {
  ...latest,
  id: 'sv-old',
  version: '0.9.0',
  displayName: 'Archery SQL 检查（早期）',
  summary: '早期版本说明。',
  changelog: '早期版本',
  sha256: 'b'.repeat(64),
  skillMd: '---\nname: archery-sql-check\ndescription: safe\n---\n# 早期说明',
  reviewedAt: '2026-08-20T10:00:00',
}

const detail: SkillDetail = {
  skill: {
    id: 'skill-1',
    slug: 'archery-sql-check',
    displayName: 'Archery SQL 检查',
    summary: '区分本地检查与生产门禁。',
    category: 'dev-programming',
    tags: ['sql'],
    status: 'PUBLISHED',
    downloads: 8,
    favorites: 2,
    updatedAt: '2026-08-26T10:00:00',
    publisher: { handle: 'skill-hub', displayName: 'wage' },
    version: '1.0.0',
    compatiblePlatforms: ['codex'],
  },
  publishedVersion: latest,
  versions: [latest, old],
  favorite: false,
}

function button(wrapper: ReturnType<typeof mount>, label: string) {
  const match = wrapper.findAll('button').find((item) => item.text().includes(label))
  if (!match) throw new Error(`找不到按钮：${label}`)
  return match
}

describe('SkillDetailView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchSkill).mockResolvedValue(detail)
    vi.mocked(fetchSkillFile).mockResolvedValue({
      version: '1.0.0',
      path: 'references/guide.md',
      size: 32,
      previewable: true,
      content: '# 检查说明\n\n不要执行生产 SQL。',
      message: null,
    })
  })

  it('在公开详情页展示安全渲染的概述并复制可核验的安装提示词', async () => {
    const wrapper = mount(SkillDetailView)
    await flushPromises()

    expect(fetchSkill).toHaveBeenCalledWith('skill-hub', 'archery-sql-check')
    expect(wrapper.text()).toContain('区分本地检查与生产门禁')
    expect(wrapper.find('.skill-markdown h1').text()).toBe('Archery SQL 检查')
    expect(wrapper.text()).not.toContain('description: safe')

    await button(wrapper, '复制安装提示词').trigger('click')
    const prompt = vi.mocked(copyText).mock.calls[0]![0]
    expect(prompt).toContain('Archery SQL 检查')
    expect(prompt).toContain(
      '/api/public/skills/skill-hub/archery-sql-check/download?version=1.0.0',
    )
    expect(prompt).toContain(`SHA-256：${'a'.repeat(64)}`)
    expect(prompt).toContain('不要执行包内脚本')
  })

  it('从文件清单按版本读取文本内容并提供失败恢复入口', async () => {
    const wrapper = mount(SkillDetailView)
    await flushPromises()

    await button(wrapper, '文件').trigger('click')
    await wrapper.findAll('.file-list button')[1]!.trigger('click')
    await flushPromises()

    expect(fetchSkillFile).toHaveBeenCalledWith(
      'skill-hub',
      'archery-sql-check',
      '1.0.0',
      'references/guide.md',
    )
    expect(wrapper.find('.file-detail pre').text()).toContain('不要执行生产 SQL')

    vi.mocked(fetchSkillFile).mockRejectedValueOnce(new Error('文件暂时不可用'))
    await wrapper.findAll('.file-list button')[1]!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('文件暂时不可用')
    expect(button(wrapper, '重试').exists()).toBe(true)
  })

  it('可从版本历史切换并下载指定的不可变旧版本', async () => {
    const wrapper = mount(SkillDetailView)
    await flushPromises()

    await button(wrapper, '版本历史').trigger('click')
    expect(wrapper.text()).toContain('早期版本')
    await wrapper.findAll('.version-actions button')[1]!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('历史版本')
    expect(wrapper.find('.skill-markdown h1').text()).toBe('早期说明')
    expect(wrapper.find('.action-card .download').attributes('href')).toContain('version=0.9.0')
    expect(wrapper.text()).toContain('返回最新版 v1.0.0')
  })
})
