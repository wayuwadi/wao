<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { fetchMyPets, fetchPets, petFileUrl, setPetVisibility, type PetCard } from '@/api/pets'
import RepositoryPetPreview from '@/components/assistant/RepositoryPetPreview.vue'
const route = useRoute(),
  mine = computed(() => route.path.endsWith('/manage'))
const items = ref<PetCard[]>([]),
  keyword = ref(''),
  page = ref(1),
  more = ref(false),
  loading = ref(false),
  error = ref(''),
  busy = ref(''),
  confirmId = ref(''),
  confirmed = ref(false)
let generation = 0
async function load(next = 1) {
  const ticket = ++generation
  loading.value = true
  error.value = ''
  try {
    const result = mine.value
      ? { items: await fetchMyPets(), hasMore: false, page: 1 }
      : await fetchPets(keyword.value, next)
    if (ticket !== generation) return
    items.value = result.items
    more.value = result.hasMore
    page.value = result.page
  } catch (e) {
    if (ticket === generation) error.value = e instanceof Error ? e.message : '加载失败，请重试'
  } finally {
    if (ticket === generation) loading.value = false
  }
}
async function visibility(pet: PetCard) {
  busy.value = pet.id
  error.value = ''
  try {
    const updated = await setPetVisibility(pet.id, !pet.listed, confirmed.value)
    items.value = items.value.map((p) => (p.id === pet.id ? updated : p))
    confirmId.value = ''
    confirmed.value = false
  } catch (e) {
    error.value = e instanceof Error ? e.message : '操作失败，请重试'
  } finally {
    busy.value = ''
  }
}
watch(
  mine,
  () => {
    items.value = []
    confirmId.value = ''
    void load()
  },
  { immediate: true },
)
</script>
<template>
  <section>
    <h1>{{ mine ? '我的宠物' : 'Pet 仓库' }}</h1>
    <p class="hub-lead">
      {{
        mine
          ? '私人保存后再决定是否公开。每个包保留独立下载地址；下架即停止公网访问。'
          : '发现真实创作的宠物，预览动作，将安装指令分享给你的 Codex。'
      }}
    </p>
    <div class="hub-actions">
      <RouterLink class="hub-button primary" to="/pets/create">创造 / 上传宠物</RouterLink
      ><RouterLink class="hub-button" :to="mine ? '/pets' : '/pets/manage'">{{
        mine ? '浏览公开仓库' : '我的宠物'
      }}</RouterLink>
    </div>
    <form v-if="!mine" class="hub-actions" @submit.prevent="load()">
      <input
        v-model="keyword"
        class="hub-input"
        style="max-width: 440px"
        aria-label="搜索宠物"
        placeholder="搜索名称、标识或描述"
        maxlength="100"
      /><button class="hub-button" :disabled="loading">搜索</button>
    </form>
    <div v-if="error" class="hub-error" role="alert">
      {{ error }} <button class="hub-button" @click="load(page)">重新加载</button>
    </div>
    <p v-if="loading" role="status">正在加载宠物…</p>
    <div v-else-if="!items.length && !error" class="hub-empty">
      {{
        mine
          ? '还没有保存的宠物，从自制图集或已有 ZIP 开始吧。'
          : '暂时没有符合条件的公开宠物。你可以成为第一位分享者。'
      }}
    </div>
    <div class="hub-grid">
      <article v-for="pet in items" :key="pet.id" class="hub-card">
        <RepositoryPetPreview
          :src="petFileUrl(pet.id, 'sprite', mine)"
          :version="pet.spriteVersion"
        />
        <h2>{{ pet.displayName }}</h2>
        <p class="hub-note">{{ pet.author }} · @{{ pet.handle }} · v{{ pet.spriteVersion }}</p>
        <p>{{ pet.description }}</p>
        <p v-if="mine" class="hub-status">{{ pet.listed ? '已公开' : '私人 / 已下架' }}</p>
        <div class="hub-actions">
          <RouterLink v-if="pet.listed" class="hub-button" :to="`/pets/${pet.id}`"
            >详情与安装</RouterLink
          ><a v-if="mine" class="hub-button" :href="petFileUrl(pet.id, 'download', true)"
            >下载 ZIP</a
          ><button
            v-if="mine"
            class="hub-button"
            :disabled="!!busy"
            @click="pet.listed ? visibility(pet) : ((confirmId = pet.id), (confirmed = false))"
          >
            {{ busy === pet.id ? '保存中…' : pet.listed ? '下架' : '公开分享' }}
          </button>
        </div>
        <div v-if="confirmId === pet.id" class="hub-note">
          <p>公开后，任何人都能查看、下载此包。来源：{{ pet.source }}；授权：{{ pet.license }}。</p>
          <label><input v-model="confirmed" type="checkbox" />我拥有该素材的公开分享权限</label>
          <div class="hub-actions">
            <button
              class="hub-button primary"
              :disabled="!confirmed || !!busy"
              @click="visibility(pet)"
            >
              确认公开</button
            ><button class="hub-button" @click="confirmId = ''">取消</button>
          </div>
        </div>
      </article>
    </div>
    <div v-if="!mine" class="hub-actions">
      <button v-if="page > 1" class="hub-button" :disabled="loading" @click="load(page - 1)">
        上一页</button
      ><button v-if="more" class="hub-button" :disabled="loading" @click="load(page + 1)">
        下一页
      </button>
    </div>
  </section>
</template>
