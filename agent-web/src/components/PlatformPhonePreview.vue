<script setup lang="ts">
import { computed } from 'vue'
import type { PublishTask } from '@/types'
import { publishStatusLabel } from '@/publishStatus'

const props = defineProps<{
  tasks: PublishTask[]
  activeTaskId: string
  draft: { title: string; content: string; tags: string }
}>()
const emit = defineEmits<{ select: [taskId: string] }>()

const active = computed(() => props.tasks.find((task) => task.id === props.activeTaskId) ?? props.tasks[0])
const previewTitle = computed(() => props.draft.title || active.value?.title || '')
const previewContent = computed(() => props.draft.content || active.value?.content || '')
const previewTags = computed(() => props.draft.tags.split(/[\s,，#]+/).filter(Boolean))

function platformIcon(platform: string) {
  return ({ DOUYIN: 'ri-tiktok-line', XIAOHONGSHU: 'ri-book-open-line', WECHAT_MP: 'ri-wechat-line' } as Record<string, string>)[platform]
    ?? 'ri-smartphone-line'
}
</script>

<template>
  <div class="platform-preview">
    <nav class="platform-tabs" aria-label="平台内容预览" role="tablist">
      <button
        v-for="task in tasks" :key="task.id" type="button"
        :class="{ active: task.id === activeTaskId }"
        :aria-selected="task.id === activeTaskId" role="tab"
        @click="emit('select', task.id)"
      >
        <i :class="platformIcon(task.platform)"></i>{{ task.platformLabel }}
        <span :class="task.status.toLowerCase()"></span>
      </button>
    </nav>

    <div v-if="active" class="phone-shell" :class="active.platform.toLowerCase()">
      <div class="phone-status"><span>9:41</span><i></i><b>● ●</b></div>
      <div class="phone-screen">
        <div v-if="active.status === 'GENERATING'" class="preview-state" role="status">
          <span></span><strong>正在生成{{ active.platformLabel }}内容</strong><small>会按这个平台的受众和阅读习惯单独成稿</small>
        </div>
        <div v-else-if="active.errorMessage" class="preview-state failed">
          <i class="ri-error-warning-line"></i><strong>生成失败</strong><small>{{ active.errorMessage }}</small>
        </div>

        <template v-else-if="active.platform === 'DOUYIN'">
          <div class="douyin-stage">
            <div class="douyin-cover"><span>抖音</span><strong>{{ active.coverText || previewTitle || active.topic }}</strong></div>
            <ol v-if="active.blocks.length" class="script-blocks">
              <li v-for="(block, index) in active.blocks" :key="index">
                <small>{{ block.label || `镜头 ${index + 1}` }}<em v-if="block.seconds">{{ block.seconds }}s</em></small>
                <p>{{ block.text }}</p>
              </li>
            </ol>
            <div class="douyin-caption"><b>@我的账号</b><p>{{ previewContent || '成稿完成后在这里预览发布文案与口播脚本' }}</p><span v-for="tag in previewTags" :key="tag">#{{ tag }} </span></div>
          </div>
        </template>

        <template v-else-if="active.platform === 'XIAOHONGSHU'">
          <div class="xhs-head"><b>发现</b><span>关注</span><i class="ri-search-line"></i></div>
          <article class="xhs-note">
            <div class="xhs-cover"><span>RED</span><strong>{{ previewTitle || active.topic }}</strong></div>
            <div class="xhs-author"><i>我</i><b>我的账号</b><button>关注</button></div>
            <h3>{{ previewTitle || active.topic }}</h3>
            <p>{{ previewContent || '成稿完成后在这里预览小红书笔记' }}</p>
            <div class="preview-tags"><span v-for="tag in previewTags" :key="tag">#{{ tag }}</span></div>
          </article>
        </template>

        <template v-else>
          <div class="wechat-head"><i class="ri-arrow-left-s-line"></i><b>公众号文章</b><i class="ri-more-line"></i></div>
          <article class="wechat-article">
            <h2>{{ previewTitle || active.topic }}</h2>
            <div class="wechat-meta"><span>我的公众号</span><time>刚刚</time></div>
            <p>{{ previewContent || '成稿完成后在这里预览公众号文章' }}</p>
            <div class="preview-tags"><span v-for="tag in previewTags" :key="tag">#{{ tag }}</span></div>
          </article>
        </template>
      </div>
      <div class="phone-home"></div>
    </div>
    <p v-if="active" class="preview-foot">{{ active.platformLabel }} · {{ publishStatusLabel(active.status) }} · 输入后立即同步</p>
  </div>
</template>

<style scoped>
.platform-preview{padding:14px;display:grid;gap:14px;place-items:center;background:linear-gradient(160deg,var(--wao-bg-sub),var(--wao-bg-card))}.platform-tabs{width:100%;display:grid;grid-template-columns:repeat(3,1fr);gap:6px}.platform-tabs button{min-width:0;padding:8px 6px;display:flex;align-items:center;justify-content:center;gap:5px;border:1px solid var(--wao-line-control);border-radius:9px;background:var(--wao-bg-card);color:var(--wao-text-weak);font:inherit;font-size:10px;cursor:pointer}.platform-tabs button.active{border-color:var(--wao-primary);background:var(--wao-primary-soft);color:var(--wao-primary-text);font-weight:700}.platform-tabs button>span{width:6px;height:6px;border-radius:50%;background:var(--wao-success-strong)}.platform-tabs button>span.generating{background:var(--wao-warning-strong)}.platform-tabs button>span.failed{background:var(--wao-danger-strong)}.phone-shell{width:min(330px,100%);padding:8px;border:2px solid #18201f;border-radius:34px;background:#101514;box-shadow:0 18px 45px rgba(16,36,32,.2)}.phone-status{height:24px;padding:0 14px;display:grid;grid-template-columns:1fr auto 1fr;align-items:center;color:#fff;font-size:8px}.phone-status i{width:72px;height:18px;border-radius:12px;background:#050706}.phone-status b{text-align:right;font-size:6px}.phone-screen{height:540px;position:relative;overflow:auto;border-radius:24px;background:#fff;color:#202222}.phone-home{width:100px;height:4px;margin:7px auto 1px;border-radius:4px;background:#d9dfdd}.preview-state{height:100%;padding:28px;display:grid;place-items:center;align-content:center;gap:10px;text-align:center}.preview-state>span{width:28px;height:28px;border:3px solid #d8e8e2;border-top-color:#197a5d;border-radius:50%;animation:spin .8s linear infinite}.preview-state small{color:#74807c;line-height:1.6}.preview-state.failed i{font-size:30px;color:#bd4545}@keyframes spin{to{transform:rotate(360deg)}}
.douyin-stage{min-height:100%;position:relative;background:#101111;color:#fff}.douyin-cover{min-height:185px;padding:32px 24px;display:grid;align-content:center;gap:10px;background:radial-gradient(circle at 80% 18%,#fe315b55,transparent 36%),radial-gradient(circle at 18% 80%,#23f2ea44,transparent 35%),#171a1a}.douyin-cover span{font-size:10px;color:#23f2ea}.douyin-cover strong{font-size:24px;line-height:1.3}.script-blocks{margin:0;padding:14px 18px 135px;display:grid;gap:8px;list-style:none}.script-blocks li{padding:8px 10px;border-left:2px solid #23f2ea;background:#ffffff0d}.script-blocks small{display:flex;justify-content:space-between;color:#23f2ea}.script-blocks em{color:#aaa;font-style:normal}.script-blocks p{margin:4px 0 0;font-size:11px;line-height:1.55;white-space:pre-wrap}.douyin-caption{position:absolute;right:0;bottom:0;left:0;padding:28px 16px 14px;background:linear-gradient(transparent,#090a0af2);font-size:10px}.douyin-caption p{margin:5px 0;line-height:1.5;white-space:pre-wrap}.douyin-caption span{color:#ddd}
.xhs-head,.wechat-head{height:44px;padding:0 14px;display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid #eee}.xhs-head b{padding-bottom:7px;border-bottom:2px solid #ff2442}.xhs-head span{color:#999}.xhs-note{padding-bottom:22px}.xhs-cover{height:210px;padding:24px;display:grid;align-content:center;gap:8px;background:linear-gradient(145deg,#fff1e2,#ffd8df)}.xhs-cover span{color:#ff2442;font-size:10px;font-weight:800}.xhs-cover strong{font-size:22px;line-height:1.35}.xhs-author{padding:12px 14px 6px;display:flex;align-items:center;gap:8px}.xhs-author i{width:28px;height:28px;display:grid;place-items:center;border-radius:50%;background:#222;color:#fff;font-style:normal}.xhs-author button{margin-left:auto;padding:3px 10px;border:1px solid #ff2442;border-radius:15px;background:#fff;color:#ff2442}.xhs-note h3,.xhs-note>p,.preview-tags{margin:8px 14px}.xhs-note h3{font-size:15px}.xhs-note>p{font-size:11px;line-height:1.75;white-space:pre-wrap}.preview-tags{display:flex;flex-wrap:wrap;gap:5px;color:#315a9b;font-size:10px}
.wechat-head{position:sticky;top:0;background:#f7f7f7;z-index:1}.wechat-article{padding:24px 20px}.wechat-article h2{margin:0 0 10px;font-size:22px;line-height:1.35}.wechat-meta{display:flex;gap:8px;color:#999;font-size:10px}.wechat-meta span{color:#52749b}.wechat-article>p{margin:22px 0;font-size:12px;line-height:1.9;white-space:pre-wrap}.preview-foot{margin:0;color:var(--wao-text-weak);font-size:9px;text-align:center}
@media(max-width:420px){.platform-preview{padding:10px}.platform-tabs button{font-size:9px}.phone-screen{height:500px}}
</style>
