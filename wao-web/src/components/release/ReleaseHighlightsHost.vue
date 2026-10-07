<script setup lang="ts">
import { onMounted } from 'vue'
import { completeReleaseUpdate } from '@/composables/releaseUpdateState'
import { useFeatureExperience } from '@/composables/featureExperience'
import { useSessionStore } from '@/stores/session'
import { consumeReleaseExperience } from '@/utils/versionUpdate'
import { toast } from '@/composables/toast'
const session = useSessionStore()
const experience = useFeatureExperience()
// 位于已认证布局，路由与会话就绪后才兑现用户主动选择的入口。
onMounted(async () => {
  completeReleaseUpdate()
  if (!session.authed) return
  const request = consumeReleaseExperience(document.querySelector('script[data-v]')?.getAttribute('data-v') || '', session.userId)
  if (request === 'version-mismatch') {
    toast('新版本尚未加载完成，请从更新卡片重试「去看看」', 'ri-information-line')
  } else if (request) {
    try {
      if (!(await experience(request.featureId, request.action)))
        toast('暂未打开功能，可从更新卡片再次进入', 'ri-information-line')
    } catch { toast('功能打开失败，可从更新卡片再次进入', 'ri-information-line') }
  }
})
</script>
<template><span hidden aria-hidden="true"></span></template>
