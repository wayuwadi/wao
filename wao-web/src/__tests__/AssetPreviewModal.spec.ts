import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import AssetPreviewModal from '@/components/files/AssetPreviewModal.vue'

describe('hosted HTML preview isolation', () => {
  it('lets the trusted manual-page shell restore demo storage while keeping AI reports restricted', () => {
    const manual = mount(AssetPreviewModal, {
      props: { asset: { kind: 'html', name: '演示', url: '/api/pages/demo', sourceType: 'MANUAL_UPLOAD' } },
    })
    expect(manual.find('iframe').attributes('sandbox')).toContain('allow-same-origin')
    expect(manual.find('iframe').attributes('sandbox')).toContain('allow-forms')

    const report = mount(AssetPreviewModal, {
      props: { asset: { kind: 'html', name: '报告', url: '/api/pages/report', sourceType: 'AI_RUN_REPORT' } },
    })
    expect(report.find('iframe').attributes('sandbox')).not.toContain('allow-same-origin')
    expect(report.find('iframe').attributes('sandbox')).not.toContain('allow-forms')
  })
})
