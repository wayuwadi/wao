import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import SiteFooter from '@/components/SiteFooter.vue'

const viewport=vi.hoisted(()=>({mobile:null as unknown as {value:boolean}}))
vi.mock('@/composables/viewport',()=>({useIsMobile:()=>viewport.mobile}))
vi.mock('@/api/system',()=>({fetchPublicSiteInfo:vi.fn().mockResolvedValue({siteName:'WAO',description:'保留桌面说明',icpNumber:'测试备案号',icpUrl:'https://beian.miit.gov.cn/'})}))

describe('工作区手机页脚',()=>{
  it('只在明确启用的手机工作区收起说明，仍保留备案链接并随桌面恢复',async()=>{
    viewport.mobile=ref(true)
    const w=mount(SiteFooter,{props:{compactMobile:true,showBrand:true,reserveMobileActions:true},global:{stubs:{WaoLogo:true}}})
    await flushPromises()
    expect(w.text()).toContain('浏览器使用');expect(w.find('a[target="_blank"]').attributes('href')).toBe('https://beian.miit.gov.cn/')
    expect(w.text()).not.toContain('保留桌面说明');expect(w.find('.footer-brand').exists()).toBe(false)
    viewport.mobile.value=false;await flushPromises()
    expect(w.text()).toContain('保留桌面说明');expect(w.find('.footer-brand').exists()).toBe(true)
    viewport.mobile.value=true;await w.setProps({compactMobile:false})
    expect(w.text()).toContain('保留桌面说明');expect(w.find('.footer-brand').exists()).toBe(true)
    w.unmount()
  })
})
