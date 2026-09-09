<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { fetchPet, petFileUrl, type PetCard } from '@/api/pets'
import { petDownloadCommand, petInstallPrompt } from '@/utils/petInstall'
import RepositoryPetPreview from '@/components/assistant/RepositoryPetPreview.vue'
const baseOrigin = location.origin
const route = useRoute(),
  pet = ref<PetCard | null>(null),
  loading = ref(false),
  error = ref(''),
  notice = ref('')
const prompt = computed(() => (pet.value ? petInstallPrompt(pet.value, location.origin) : '')),
  command = computed(() => (pet.value ? petDownloadCommand(pet.value, location.origin) : ''))
let generation = 0
async function load() {
  const ticket = ++generation
  loading.value = true
  error.value = ''
  pet.value = null
  try {
    const result = await fetchPet(String(route.params.id))
    if (ticket === generation) pet.value = result
  } catch (e) {
    if (ticket === generation) error.value = e instanceof Error ? e.message : '读取失败'
  } finally {
    if (ticket === generation) loading.value = false
  }
}
async function copy(value: string) {
  try {
    await navigator.clipboard.writeText(value)
    notice.value = '已复制'
  } catch {
    notice.value = '复制失败，请选中下方文字手动复制'
  }
}
watch(() => route.params.id, load, { immediate: true })
</script>
<template>
  <RouterLink to="/pets">← Pet 仓库</RouterLink>
  <p v-if="loading" role="status">正在加载宠物…</p>
  <div v-if="error" class="hub-error" role="alert">
    {{ error }}<button class="hub-button" @click="load">重试</button>
  </div>
  <article v-if="pet" style="margin-top: 24px">
    <h1>{{ pet.displayName }}</h1>
    <p class="hub-note">{{ pet.author }} · @{{ pet.handle }} · 图集 v{{ pet.spriteVersion }}</p>
    <div class="hub-grid">
      <section class="hub-card">
        <RepositoryPetPreview :src="petFileUrl(pet.id, 'sprite')" :version="pet.spriteVersion" />
        <p class="hub-lead">{{ pet.description }}</p>
        <p>素材来源：{{ pet.source }}</p>
        <p>作者声明授权：{{ pet.license }}</p>
        <p class="hub-note">{{ (pet.packageSize / 1024).toFixed(1) }} KiB · 固定包 SHA-256</p>
        <code style="overflow-wrap: anywhere">{{ pet.sha256 }}</code>
        <div class="hub-actions">
          <a class="hub-button primary" :href="petFileUrl(pet.id, 'download')">下载 ZIP</a
          ><button class="hub-button" @click="copy(`${baseOrigin}/pets/${pet.id}`)">
            复制分享链接
          </button>
        </div>
      </section>
      <section class="hub-card">
        <h2>交给 Codex 安装</h2>
        <p class="hub-note">
          适用于支持自定义宠物的 Codex 桌面客户端。复制完整指令发给对方的
          Codex，即可下载、校验并安装。
        </p>
        <button class="hub-button primary" @click="copy(prompt)">复制 AI 安装指令</button>
        <pre class="hub-code">{{ prompt }}</pre>
      </section>
    </div>
    <p v-if="notice" role="status">{{ notice }}</p>
    <section class="hub-card" style="margin-top: 24px">
      <h2>手动安装教程</h2>
      <ol class="hub-lead">
        <li>点击“下载 ZIP”，或复制下面的下载命令到终端执行。</li>
        <li>校验 SHA-256 与本页一致；解压后确认有 pet.json 和对应图集。</li>
        <li>
          复制到用户目录下 .codex/pets/{{ pet.slug }}/；如果配置了 CODEX_HOME，则使用它下面的 pets
          目录。已有同名目录先备份并确认，勿直接覆盖。
        </li>
        <li>
          重启支持自定义宠物的 Codex
          桌面客户端，在设置的宠物选项中选择此角色。网页预览不代表你的客户端已经安装。
        </li>
      </ol>
      <button class="hub-button" @click="copy(command)">复制下载命令</button>
      <pre class="hub-code">{{ command }}</pre>
      <p class="hub-note">
        格式参考：<a
          href="https://github.com/astandrik/codex-pets"
          target="_blank"
          rel="noopener noreferrer"
          >开源 Codex Pets</a
        >。各客户端版本的设置入口可能不同。
      </p>
    </section>
  </article>
</template>
