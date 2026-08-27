import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import AiRunSkillCapabilities from '@/components/ai/AiRunSkillCapabilities.vue'

describe('AI Run Skill 审计', () => {
  it('展示固定版本与 Runner 实际加载结果', () => {
    const wrapper = mount(AiRunSkillCapabilities, { props: {
      skills: [{ skillId: 's1', versionId: 'v1', displayName: '代码审查', summary: '检查风险', version: '1.2.0', sha256: '1234567890abcdef', compatiblePlatforms: ['codex'], activationScope: 'WAO_APPLICATION' }],
      audits: [{ skillId: 's1', versionId: 'v1', displayName: '代码审查', version: '1.2.0', status: 'loaded', message: '已加载' }],
    } })
    expect(wrapper.text()).toContain('代码审查')
    expect(wrapper.text()).toContain('v1.2.0')
    expect(wrapper.text()).toContain('WAO 默认')
    expect(wrapper.text()).toContain('已加载')
  })
})
