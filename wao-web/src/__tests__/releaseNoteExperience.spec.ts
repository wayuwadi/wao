import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import { useReleaseNoteExperience } from '@/composables/releaseNoteExperience'
vi.mock('@/composables/toast', () => ({ toast: vi.fn() }))
import { toast } from '@/composables/toast'

describe('小记更新入口消费', () => {
  it('等实际数据加载，消费意图一次，保留筛选；同页重复操作仍生效', async () => {
    const load = ref('loading'), read = vi.fn().mockResolvedValue(true), ai = vi.fn().mockReturnValue(true), settings = vi.fn()
    const router = createRouter({history:createMemoryHistory(),routes:[{path:'/notes',component:{template:'<div />'}}]})
    await router.push('/notes?search=保留&releaseAction=reading&releaseRequest=1#keep')
    const w=mount(defineComponent({setup(){useReleaseNoteExperience({loadState:()=>load.value,openSettings:settings,read,ai});return{}},template:'<div />'}),{global:{plugins:[router]}})
    await flushPromises(); expect(read).not.toHaveBeenCalled()
    load.value='loaded'; await flushPromises()
    expect(read).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.fullPath).toBe('/notes?search=%E4%BF%9D%E7%95%99#keep')
    await router.push({path:'/notes',query:{search:'保留',releaseAction:'reading',releaseRequest:'2'}}); await flushPromises()
    expect(read).toHaveBeenCalledTimes(2)
    await router.push('/notes?releaseAction=settings&releaseRequest=3'); await flushPromises()
    expect(settings).toHaveBeenCalledOnce()
    await router.push('/notes?releaseAction=ai&releaseRequest=4'); await flushPromises()
    expect(ai).toHaveBeenCalledOnce()
    w.unmount()
  })
  it('空数据给出真实引导，未知动作不执行，处理期间到达的新意图不丢失', async () => {
    let done!: (result:boolean)=>void
    const read=vi.fn().mockImplementationOnce(()=>new Promise<boolean>(resolve=>{done=resolve})).mockResolvedValue(false)
    const ai=vi.fn().mockReturnValue(false), settings=vi.fn()
    const router=createRouter({history:createMemoryHistory(),routes:[{path:'/notes',component:{template:'<div />'}}]})
    await router.push('/notes?releaseAction=reading&releaseRequest=1')
    const w=mount(defineComponent({setup(){useReleaseNoteExperience({loadState:()=> 'loaded',openSettings:settings,read,ai});return{}},template:'<div />'}),{global:{plugins:[router]}})
    await flushPromises()
    await router.push('/notes?releaseAction=ai&releaseRequest=2'); await flushPromises()
    expect(ai).not.toHaveBeenCalled()
    done(true); await flushPromises()
    expect(ai).toHaveBeenCalledOnce()
    expect(toast).toHaveBeenCalledWith(expect.stringContaining('还没有本人的小记'), 'ri-information-line')
    await router.push('/notes?releaseAction=delete-all'); await flushPromises()
    expect(settings).not.toHaveBeenCalled(); expect(read).toHaveBeenCalledOnce()
    w.unmount()
  })
})
