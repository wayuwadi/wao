import 'fake-indexeddb/auto'
import { describe, it, expect } from 'vitest'
import { readPetDraft, writePetDraft, clearPetDraft, type PetDraft } from '@/utils/petDrafts'
import { petInstallPrompt, petDownloadCommand } from '@/utils/petInstall'
import type { PetCard } from '@/api/pets'

describe('宠物仓库用户契约', () => {
  it('按作者恢复草稿并只清理当前作者', async () => {
    const draft: PetDraft = {
      mode: 'create',
      slug: 'pet',
      name: '我的宠物',
      description: '角色',
      version: 2,
      source: '原创',
      license: 'CC0-1.0',
      file: null,
    }
    await writePetDraft('author', draft)
    expect(await readPetDraft('author')).toEqual(draft)
    expect(await readPetDraft('other')).toBeUndefined()
    await clearPetDraft('other')
    expect(await readPetDraft('author')).toEqual(draft)
    await clearPetDraft('author')
    expect(await readPetDraft('author')).toBeUndefined()
  })
  it('安装指令固定摘要，禁止覆盖且不伪称客户端安装成功', () => {
    const pet = {
      id: 'immutable-id',
      slug: 'my-pet',
      sha256: 'a'.repeat(64),
      spriteVersion: 2,
    } as PetCard
    const prompt = petInstallPrompt(pet, 'https://wao.wayuwa.cn')
    expect(prompt).toContain('/api/public/pets/immutable-id/download')
    expect(prompt).toContain(pet.sha256)
    expect(prompt).toContain('先停止并询问我，不覆盖')
    expect(prompt).toContain('不执行其中任何内容')
    expect(prompt).toContain('不要声称没有实际验证')
    expect(petDownloadCommand(pet, 'https://wao.wayuwa.cn')).toContain('my-pet-immutable-id.zip')
  })
})
