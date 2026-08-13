<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchPublicSiteInfo, type SiteInfo } from '@/api/system'

defineProps<{
  /** 登录后应用的底部导航和悬浮操作会占据手机底部，需为它们额外留白。 */
  reserveMobileActions?: boolean
}>()

const site = ref<SiteInfo | null>(null)

onMounted(async () => {
  try { site.value = await fetchPublicSiteInfo() }
  catch { /* 备案接口短暂不可用时不影响页面主体功能。 */ }
})
</script>

<template>
  <footer
    v-if="site"
    class="site-footer"
    :class="{ 'reserve-mobile-actions': reserveMobileActions }"
    aria-label="网站备案信息"
  >
    <span>© {{ new Date().getFullYear() }} {{ site.siteName }}</span>
    <span v-if="site.description" class="separator">·</span>
    <span v-if="site.description">{{ site.description }}</span>
    <span v-if="site.icpNumber" class="separator">·</span>
    <a v-if="site.icpNumber" :href="site.icpUrl" target="_blank" rel="noopener noreferrer">{{ site.icpNumber }}</a>
  </footer>
</template>

<style scoped>
.site-footer {
  min-height: 44px;
  box-sizing: border-box;
  padding: 12px 20px 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 6px;
  color: var(--wao-text-faint, #979baa);
  font-size: 11px;
  line-height: 1.5;
}
.site-footer a { color: inherit; text-decoration: none; }
.site-footer a:hover { color: var(--wao-primary, #4358d6); text-decoration: underline; }
.separator { color: var(--wao-line-strong, #d8dbe5); }
@media (max-width: 760px) {
  .site-footer { padding: 20px 12px calc(20px + env(safe-area-inset-bottom)); font-size: 10px; }
  .site-footer.reserve-mobile-actions { padding-bottom: calc(92px + env(safe-area-inset-bottom)); }
}
</style>
