import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import PetCreateView from '@/views/PetCreateView.vue'
const api=vi.hoisted(()=>({createPet:vi.fn(),readPetDraft:vi.fn(),writePetDraft:vi.fn(),clearPetDraft:vi.fn()}))
vi.mock('@/stores/session',()=>({useSessionStore:()=>({userId:'author'})}))
vi.mock('@/api/skills',()=>({fetchPublisher:async()=>({handle:'author',displayName:'作者'}),savePublisher:vi.fn()}))
vi.mock('@/api/pets',()=>({createPet:api.createPet}))
vi.mock('@/utils/petDrafts',()=>({readPetDraft:api.readPetDraft,writePetDraft:api.writePetDraft,clearPetDraft:api.clearPetDraft}))
async function render(){
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/pets/create',component:PetCreateView},{path:'/pets/manage',component:{template:'<p>我的宠物</p>'}}]})
 await router.push('/pets/create');await router.isReady()
 const wrapper=mount({template:'<RouterView />'},{global:{plugins:[router]}});await flushPromises();return {wrapper,router}
}
describe('宠物创建保存与防丢',()=>{
 beforeEach(()=>{Object.values(api).forEach(fn=>fn.mockReset());api.readPetDraft.mockResolvedValue({mode:'zip',slug:'',name:'',description:'',version:2,source:'原创',license:'CC0-1.0',file:new File(['zip'],'pet.zip')});api.writePetDraft.mockResolvedValue(undefined);api.clearPetDraft.mockResolvedValue(undefined)})
 it('恢复真实文件后保存成功进入我的宠物，清除本地草稿',async()=>{api.createPet.mockResolvedValue({id:'saved'});const {wrapper,router}=await render();expect(wrapper.text()).toContain('pet.zip');await wrapper.get('form').trigger('submit');await flushPromises();expect(api.createPet).toHaveBeenCalledOnce();expect(router.currentRoute.value.path).toBe('/pets/manage');expect(api.clearPetDraft).toHaveBeenCalledWith('author');wrapper.unmount()})
 it('服务端失败保留所选文件和输入，允许原样重试',async()=>{api.createPet.mockRejectedValueOnce(new Error('图集格式错误')).mockResolvedValueOnce({id:'saved'});const {wrapper,router}=await render();await wrapper.get('form').trigger('submit');await flushPromises();expect(wrapper.text()).toContain('图集格式错误');expect(wrapper.text()).toContain('pet.zip');expect(api.clearPetDraft).not.toHaveBeenCalled();await wrapper.get('form').trigger('submit');await flushPromises();expect(router.currentRoute.value.path).toBe('/pets/manage');wrapper.unmount()})
})
