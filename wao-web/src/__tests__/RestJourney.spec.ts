import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import HomeBreathingStage from '@/components/home/HomeBreathingStage.vue'
import RestEntryTransition from '@/components/relax/RestEntryTransition.vue'
import { completeRestJourney, requestRestJourney, restArrivalSerial, restJourneyRequested, restSelectionSerial } from '@/composables/restJourney'

const mounted: VueWrapper[] = []
function stage(arrival=0) {
  const wrapper=mount(HomeBreathingStage,{attachTo:document.body,props:{restful:true,minimal:true,arrival},global:{plugins:[createPinia()]}})
  mounted.push(wrapper);return wrapper
}
beforeEach(()=>{ vi.useFakeTimers();localStorage.clear();restJourneyRequested.value=false;restArrivalSerial.value=0;restSelectionSerial.value=0;Object.defineProperty(document,'hidden',{configurable:true,value:false}) })
afterEach(()=>{mounted.forEach(w=>w.unmount());mounted.length=0;vi.useRealTimers();vi.restoreAllMocks();document.body.innerHTML='';Object.defineProperty(document,'hidden',{configurable:true,value:false})})

describe('伙伴休息旅程',()=>{
 it('直接访问先选择，不自动计时；走走与返回不创建练习',async()=>{
   const w=stage();expect(w.find('.rest-guidance').exists()).toBe(false);expect(w.classes()).toContain('idle')
   expect(w.findAll('.rest-choice')).toHaveLength(2)
   await w.findAll('.rest-choice')[1]!.trigger('click');expect(w.text()).toContain('不用盯着屏幕');expect(w.find('.timer').exists()).toBe(false)
   await w.get('.walk-return').trigger('click');expect(w.text()).toContain('欢迎回来');expect(w.classes()).toContain('idle')
 })
 it('到达后只引导一轮，后台暂停并要求主动继续，结束后提供选择',async()=>{
   const w=stage();await w.setProps({arrival:1});expect(w.find('.rest-guidance').exists()).toBe(true)
   await vi.advanceTimersByTimeAsync(2000)
   Object.defineProperty(document,'hidden',{configurable:true,value:true});document.dispatchEvent(new Event('visibilitychange'))
   await vi.advanceTimersByTimeAsync(20000);expect(w.text()).toContain('安心停一会儿')
   Object.defineProperty(document,'hidden',{configurable:true,value:false});document.dispatchEvent(new Event('visibilitychange'));await flushPromises()
   expect(w.text()).toContain('继续引导');await w.findAll('.rest-guidance button')[0]!.trigger('click')
   await vi.advanceTimersByTimeAsync(6100);expect(w.findAll('.rest-choice')).toHaveLength(2);expect(w.classes()).toContain('idle')
   expect(w.find('.timer').exists()).toBe(false)
 })
 it('跳过引导后不开始计时；主动开始才计时，后台暂停后不会自动继续',async()=>{
   const w=stage();await w.setProps({arrival:1});await w.get('.rest-skip').trigger('click');await w.get('.rest-choice').trigger('click')
   expect(w.classes()).toContain('idle');await w.get('.duration button').trigger('click');await w.get('.actions .primary').trigger('click')
   await vi.advanceTimersByTimeAsync(2100)
   Object.defineProperty(document,'hidden',{configurable:true,value:true});document.dispatchEvent(new Event('visibilitychange'));await flushPromises()
   const time=w.get('.timer').text(),pose=w.get('.rest-companions').attributes('style')
   await vi.advanceTimersByTimeAsync(10000);expect(w.get('.timer').text()).toBe(time);expect(w.get('.rest-companions').attributes('style')).toBe(pose)
   Object.defineProperty(document,'hidden',{configurable:true,value:false});document.dispatchEvent(new Event('visibilitychange'));await flushPromises()
   expect(w.classes()).toContain('paused');await w.get('.actions .primary').trigger('click');expect(w.classes()).toContain('running')
 })
 it('静坐不伸缩伙伴；认证快照恢复到暂停练习，结束后回到选择',async()=>{
   const w=stage();w.vm.restore({mode:'sitting',total:60000,remaining:42000,inhale:3,exhale:4});await flushPromises()
   expect(w.classes()).toContain('paused');expect(w.get('.timer').text()).toContain('00:42')
   await w.get('.primary').trigger('click');const pose=w.get('.rest-companions').attributes('style');await vi.advanceTimersByTimeAsync(3000)
   expect(w.get('.rest-companions').attributes('style')).toBe(pose)
   await w.get('.actions .text-button').trigger('click');await w.get('.dialog .primary').trigger('click');expect(w.findAll('.rest-choice')).toHaveLength(2)
 })
 it('入场防重入，只有完整旅程申请呼吸引导',()=>{
   requestRestJourney();requestRestJourney();expect(restJourneyRequested.value).toBe(true)
   completeRestJourney(false);expect(restArrivalSerial.value).toBe(0)
   completeRestJourney(false,true);expect(restSelectionSerial.value).toBe(1)
   requestRestJourney();completeRestJourney(true);expect(restArrivalSerial.value).toBe(1)
 })
 it('入场跳过完成后把焦点移到休息选择，而取消不发到达通知',async()=>{
   const w=stage();await w.setProps({selection:1});await flushPromises();expect(document.activeElement).toBe(w.get('.rest-choice').element)
   completeRestJourney(false);expect(restSelectionSerial.value).toBe(0)
 })
 it('跳转失败允许重试，卸载还原工作区焦点屏障和滚动锁',async()=>{
   const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:{template:'<div />'}},{path:'/breathe',component:{template:'<div />'}}]})
   await router.push('/');const push=vi.spyOn(router,'push').mockRejectedValueOnce(new Error('blocked'))
   const app=document.createElement('div');app.id='app';document.body.append(app);document.body.style.overflow='auto'
   const w=mount(RestEntryTransition,{attachTo:document.body,global:{plugins:[router],stubs:{Teleport:true}}});mounted.push(w)
   await flushPromises();expect(app.inert).toBe(true)
   await w.get('.entry-skip').trigger('click');await flushPromises();expect(w.text()).toContain('请重试');expect(restArrivalSerial.value).toBe(0)
   await w.get('.entry-skip').trigger('click');await flushPromises();expect(push).toHaveBeenCalledTimes(2);expect(router.currentRoute.value.path).toBe('/breathe')
   w.unmount();expect(app.inert).toBe(false);expect(document.body.style.overflow).toBe('auto')
 })
})
