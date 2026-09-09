<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref, shallowRef, toRaw, watch } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { fetchPublisher, savePublisher, type SkillPublisher } from '@/api/skills'
import { createPet } from '@/api/pets'
import { clearPetDraft, readPetDraft, writePetDraft } from '@/utils/petDrafts'
import RepositoryPetPreview from '@/components/assistant/RepositoryPetPreview.vue'
const session = useSessionStore(),
  router = useRouter(),
  owner = session.userId
const form = reactive({
  mode: 'zip',
  slug: '',
  name: '',
  description: '',
  version: 2,
  source: '',
  license: 'ALL-RIGHTS-RESERVED',
})
const file = shallowRef<File | null>(null),
  preview = ref(''),
  publisher = ref<SkillPublisher | null>(null),
  identity = reactive({ handle: '', displayName: '' })
const loading = ref(true),
  saving = ref(false),
  error = ref(''),
  draftStatus = ref(''),
  ready = ref(false),
  dirty = ref(false),
  draftFailed = ref(false),
  imageValid = ref(false)
let revision = 0,
  queue: Promise<void> = Promise.resolve()
function persist() {
  if (!ready.value) return
  dirty.value = true
  const current = ++revision
  draftStatus.value = '正在保存本地草稿…'
  const snapshot = { ...toRaw(form), file: file.value, identity: { ...toRaw(identity) } }
  queue = queue
    .catch(() => {})
    .then(() => writePetDraft(owner, snapshot))
    .then(() => {
      if (current === revision) {
        draftStatus.value = '本地草稿已保存（含所选文件）'
        draftFailed.value = false
      }
    })
    .catch(() => {
      draftFailed.value = true
      draftStatus.value = '本地草稿保存失败，请重试；离开页面前先保存宠物。'
    })
}
watch(form, persist)
watch(identity, persist)
watch(file, persist)
watch([file, () => form.mode, () => form.version], async () => {
  if (preview.value) URL.revokeObjectURL(preview.value)
  preview.value = ''
  imageValid.value = false
  if (form.mode !== 'create' || !file.value) return
  const selected = file.value,
    url = URL.createObjectURL(selected)
  preview.value = url
  const image = new Image()
  image.onload = () => {
    if (preview.value !== url) return
    imageValid.value = image.width === 1536 && image.height === (form.version === 1 ? 1872 : 2288)
    if (!imageValid.value) error.value = `图集尺寸应为 1536×${form.version === 1 ? 1872 : 2288}`
  }
  image.onerror = () => {
    if (preview.value === url) error.value = '图片无法解码，请重新选择 PNG/WebP 图集'
  }
  image.src = url
})
function choose(event: Event) {
  const selected = (event.target as HTMLInputElement).files?.[0]
  if (!selected) return
  if (selected.size > 10 * 1024 * 1024) {
    error.value = '文件超过 10 MiB'
    return
  }
  error.value = ''
  file.value = selected
}
async function initialize() {
  loading.value = true
  error.value = ''
  try {
    publisher.value = await fetchPublisher()
    if (!ready.value) {
      const draft = await readPetDraft(owner)
      if (draft) {
        const { file: saved, identity: savedIdentity, ...fields } = draft
        if (savedIdentity) Object.assign(identity, savedIdentity)
        Object.assign(form, fields)
        file.value = saved
        dirty.value = true
        draftStatus.value = '已恢复本地草稿及所选文件'
      }
    }
    ready.value = true
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败，请重试'
  } finally {
    loading.value = false
  }
}
async function saveIdentity() {
  saving.value = true
  error.value = ''
  try {
    publisher.value = await savePublisher({ ...identity })
  } catch (e) {
    error.value = e instanceof Error ? e.message : '作者身份保存失败'
  } finally {
    saving.value = false
  }
}
async function submit() {
  if (!file.value || saving.value) return
  saving.value = true
  error.value = ''
  try {
    const body = new FormData()
    body.append('source', form.source)
    body.append('license', form.license)
    if (form.mode === 'zip') body.append('archive', file.value)
    else {
      if (!imageValid.value) throw new Error('请选择尺寸正确且可解码的图集')
      body.append('sprite', file.value)
      body.append(
        'manifest',
        JSON.stringify({
          id: form.slug,
          displayName: form.name,
          description: form.description,
          spriteVersionNumber: form.version,
          spritesheetPath: file.value.type === 'image/png' ? 'spritesheet.png' : 'spritesheet.webp',
          kind: 'animal',
        }),
      )
    }
    await createPet(body)
    await queue
    try {
      await clearPetDraft(owner)
    } catch {
      /* 已落库的包可从我的宠物恢复 */
    }
    dirty.value = false
    draftStatus.value = '宠物已保存'
    await router.push('/pets/manage')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '保存失败，草稿仍在，请重试'
  } finally {
    saving.value = false
  }
}
function unload(event: BeforeUnloadEvent) {
  if (dirty.value && (draftFailed.value || saving.value)) {
    event.preventDefault()
    event.returnValue = ''
  }
}
onBeforeRouteLeave(async () => {
  if (saving.value && dirty.value) return false
  await queue
  if (dirty.value && draftFailed.value)
    return window.confirm('本地草稿未保存，离开将丢失输入。仍要离开吗？')
})
onMounted(() => {
  void initialize()
  window.addEventListener('beforeunload', unload)
})
onUnmounted(() => {
  if (preview.value) URL.revokeObjectURL(preview.value)
  window.removeEventListener('beforeunload', unload)
})
</script>
<template>
  <section style="max-width: 780px; margin: auto">
    <h1>创造 / 上传宠物</h1>
    <p class="hub-lead">
      从自己的完整动作图集创造一个可安装宠物，或上传现有宠物 ZIP。先私人保存，再决定是否公开分享。
    </p>
    <div v-if="loading" role="status">正在加载作者身份与草稿…</div>
    <div v-if="error" class="hub-error" role="alert">
      {{ error }}
      <button v-if="!ready || !publisher" class="hub-button" @click="initialize">重新加载</button>
    </div>
    <form v-if="!loading && ready && !publisher" class="hub-card" @submit.prevent="saveIdentity">
      <h2>建立公开作者身份</h2>
      <p class="hub-status" role="status">{{ draftStatus }}</p>
      <p class="hub-note">与 Skill 仓库共用，公开页不展示内部账号信息。</p>
      <label class="hub-field"
        >公开标识（建立后不可修改）<input
          v-model="identity.handle"
          required
          pattern="[a-z0-9][a-z0-9-]{2,31}"
          maxlength="32"
          class="hub-input" /></label
      ><label class="hub-field"
        >作者昵称<input
          v-model="identity.displayName"
          required
          maxlength="60"
          class="hub-input" /></label
      ><button class="hub-button primary" :disabled="saving">
        {{ saving ? '保存中…' : '保存作者身份' }}
      </button>
    </form>
    <form v-if="!loading && ready && publisher" class="hub-card" @submit.prevent="submit">
      <p class="hub-note">发布者：{{ publisher.displayName }} · @{{ publisher.handle }}</p>
      <fieldset :disabled="saving" style="border: 0; padding: 0; margin: 0">
        <label class="hub-field"
          >创建方式<select v-model="form.mode" class="hub-input" @change="file = null">
            <option value="zip">上传已有宠物 ZIP</option>
            <option value="create">用自己的图集创造宠物</option>
          </select></label
        ><template v-if="form.mode === 'create'"
          ><label class="hub-field"
            >宠物标识<input
              v-model="form.slug"
              class="hub-input"
              required
              maxlength="64"
              pattern="[a-z0-9]+(-[a-z0-9]+)*"
              placeholder="例如 zhang-sanfeng" /></label
          ><label class="hub-field"
            >宠物名称<input v-model="form.name" required maxlength="100" class="hub-input" /></label
          ><label class="hub-field"
            >角色描述<textarea
              v-model="form.description"
              required
              maxlength="1000"
              class="hub-input"
              rows="3"
            ></textarea></label
          ><label class="hub-field"
            >图集版本<select v-model="form.version" class="hub-input">
              <option :value="1">v1 · 1536×1872 · 8 列 9 行</option>
              <option :value="2">v2 · 1536×2288 · 8 列 11 行</option>
            </select></label
          >
          <details class="hub-note">
            <summary>如何制作图集？</summary>
            <p>
              使用绘图工具制作透明 PNG/WebP，每格
              192×208。从上到下依次安排待机、向右跑、向左跑、挥手、跳跃、失败、等待、工作、审阅；各行动作帧数为
              6、8、8、4、5、8、6、6、6。v2 最后两行放 16
              个顺时针视线方向。你也可以委托自己的图像工具创作，再在这里检查动作。此入口负责真实素材组装，不自动生成图片。
            </p>
            <a
              href="https://github.com/astandrik/codex-pets"
              target="_blank"
              rel="noopener noreferrer"
              >查看开源格式参考</a
            >
          </details></template
        ><label class="hub-field"
          >{{ form.mode === 'zip' ? '选择宠物 ZIP' : '选择完整 PNG/WebP 图集' }}（最多 10
          MiB）<input
            :key="form.mode"
            type="file"
            :accept="form.mode === 'zip' ? '.zip' : '.png,.webp'"
            @change="choose"
        /></label>
        <p v-if="file" class="hub-note">
          已选择 / 恢复：{{ file.name }} · {{ (file.size / 1024).toFixed(1) }} KiB
        </p>
        <RepositoryPetPreview v-if="preview && imageValid" :src="preview" :version="form.version" />
        <p v-if="form.mode === 'zip'" class="hub-note">
          ZIP 包含 pet.json、spritesheet.png / spritesheet.webp，可选
          README.md、LICENSE；可有一层共同根目录。保存时校验内容，错误时保留草稿。
        </p>
        <label class="hub-field"
          >素材来源 / 原作者<input
            v-model="form.source"
            required
            maxlength="500"
            class="hub-input"
            placeholder="原创请注明；使用他人素材请填写原作者与来源" /></label
        ><label class="hub-field"
          >素材授权<select v-model="form.license" class="hub-input">
            <option>ALL-RIGHTS-RESERVED</option>
            <option>CC0-1.0</option>
            <option>CC-BY-4.0</option>
            <option>MIT</option>
            <option>Apache-2.0</option>
          </select></label
        >
        <p class="hub-note">
          保留所有权利不等于拥有他人素材的分享权。保存不会自动公开；公开时还需要确认你拥有分享权限。
        </p>
        <p class="hub-status" role="status">{{ draftStatus }}</p>
        <button v-if="draftFailed" type="button" class="hub-button" @click="persist">
          重试保存草稿
        </button>
        <div class="hub-actions">
          <button
            class="hub-button primary"
            :disabled="saving || !file || (form.mode === 'create' && !imageValid)"
          >
            {{ saving ? '正在校验并保存…' : '保存私人宠物' }}</button
          ><RouterLink class="hub-button" to="/pets/manage">我的宠物</RouterLink>
        </div>
      </fieldset>
    </form>
  </section>
</template>
