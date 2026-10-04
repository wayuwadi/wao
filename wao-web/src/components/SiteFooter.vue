<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchPublicSiteInfo, type SiteInfo } from '@/api/system'
import WaoLogo from '@/components/WaoLogo.vue'

defineProps<{
  /** 登录后应用的悬浮新建入口会占据手机右下角，需为它额外留白。 */
  reserveMobileActions?: boolean
  /** 应用壳右下角的品牌签名，独立于侧栏折叠状态。 */
  showBrand?: boolean
}>()

const site = ref<SiteInfo | null>(null)

onMounted(async () => {
  try {
    site.value = await fetchPublicSiteInfo()
  } catch {
    /* 备案接口短暂不可用时不影响页面主体功能。 */
  }
})
</script>

<template>
  <footer
    class="site-footer"
    :class="{ 'reserve-mobile-actions': reserveMobileActions, 'has-brand': showBrand }"
    aria-label="网站备案信息"
  >
    <div class="footer-info">
      <a href="/download">下载 WAO</a>
      <span class="separator">·</span>
      <span>© {{ new Date().getFullYear() }} {{ site?.siteName ?? 'WAO' }}</span>
      <span v-if="site?.description" class="separator">·</span>
      <span v-if="site?.description">{{ site?.description }}</span>
      <span v-if="site?.icpNumber" class="separator">·</span>
      <a v-if="site?.icpNumber" :href="site?.icpUrl" target="_blank" rel="noopener noreferrer">{{
        site?.icpNumber
      }}</a>
    </div>
    <span v-if="showBrand" class="footer-brand"><WaoLogo wordmark /><span class="brand-tagline">Life OS</span></span>
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
.site-footer a {
  color: inherit;
  text-decoration: none;
}
.footer-info { display: flex; align-items: center; justify-content: center; flex-wrap: wrap; gap: 6px; min-width: 0; }
.site-footer.has-brand { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 12px; }
.footer-brand { display: inline-flex; align-items: center; gap: 5px; justify-self: end; white-space: nowrap; }
.footer-brand :deep(.wao-logo) { gap: 5px; }
.footer-brand :deep(.wao-logo-symbol) { width: 21px; height: 21px; }
.footer-brand :deep(.wao-logo-name) { font-size: 12px; font-weight: 500; }
.site-footer a:hover {
  color: var(--wao-primary, #4358d6);
  text-decoration: underline;
}
.separator {
  color: var(--wao-line-strong, #d8dbe5);
}
@media (max-width: 760px) {
  .site-footer {
    padding: 12px 8px calc(12px + env(safe-area-inset-bottom));
    font-size: 9px;
  }
  .site-footer.reserve-mobile-actions {
    padding: 12px 52px calc(58px + env(safe-area-inset-bottom)) 8px;
  }
  .site-footer.has-brand { gap: 8px; }
  .footer-brand .brand-tagline { display: none; }
}
</style>
