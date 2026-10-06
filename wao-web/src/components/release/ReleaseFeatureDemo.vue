<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ReleaseDemo } from '@/types/featureExplorer'
const props = defineProps<{ demo: ReleaseDemo; editionId: string }>()
const expanded = ref(false),
  failed = ref(false),
  retry = ref(0)
function retryImage() { failed.value = false; retry.value++ }
watch(
  () => [props.demo, props.editionId],
  () => {
    expanded.value = false
    failed.value = false
    retry.value = 0
  },
)
const imageUrl = computed(() => {
  const sha = /^git-release-([a-f0-9]{40})$/.exec(props.editionId)?.[1]
  return `${sha ? `/releases/${sha}/` : import.meta.env.BASE_URL}${props.demo.image}${retry.value ? `?retry=${retry.value}` : ''}`
})
</script>
<template>
  <div class="feature-demo" :aria-label="demo.alt">
    <template v-if="demo.image">
      <div v-if="failed" class="image-error" role="status">
        <p>演示图暂时无法显示，仍可按使用步骤操作。</p>
        <button
          type="button"
          @click="retryImage"
        >
          重试图片
        </button>
      </div>
      <img v-else :src="imageUrl" :alt="demo.alt" @error="failed = true" />
    </template>
    <template v-else>
      <div class="demo-bar">
        <span class="mark">W</span><span>功能演示</span><small>操作示意</small>
      </div>
      <div class="scene">
        <template v-if="demo.kind === 'update-card'">
          <div class="mini-arrival">✦ 新变化送到了 ✦</div>
          <div class="mini-content">
            <b>更新内容，打开就能看懂</b>
            <p>先看用途，再看使用步骤。</p>
            <button type="button" :aria-expanded="expanded" @click="expanded = !expanded">
              {{ expanded ? '收起演示' : '看看怎么用' }}
            </button>
            <ol v-if="expanded">
              <li>从顶栏打开更新记录</li>
              <li>在卡片里阅读具体用法</li>
              <li>更新后前往对应功能</li>
            </ol>
          </div>
        </template>
        <template v-else-if="demo.kind === 'workspace-mcp'">
          <div class="mini-path">能力中心 → 我的连接</div>
          <div class="mini-content">
            <b>连接你的工具</b>
            <p>按操作与数据范围授权，CLI 与 MCP 共用连接。</p>
            <button type="button" :aria-expanded="expanded" @click="expanded = !expanded">
              {{ expanded ? '收起连接步骤' : '展开连接步骤' }}
            </button>
            <ol v-if="expanded">
              <li>新建连接，选择日常完整或所需能力</li>
              <li>生成连接指令，在本机安全登录</li>
              <li>运行连接检查，确认当前授权工具</li>
            </ol>
          </div>
        </template>
        <template v-else-if="demo.kind === 'notes-reading'">
          <div class="mini-path">小记 → 侧栏速览 / 全屏阅读</div>
          <div class="mini-content">
            <b>{{ expanded ? '编辑同一篇小记' : '读到想改的地方' }}</b>
            <p>{{ expanded ? '保存后回到原来的阅读布局与位置。' : '双击正文可原地修改，保留正在阅读的位置。' }}</p>
            <button type="button" :aria-expanded="expanded" @click="expanded = !expanded">{{ expanded ? '返回阅读示意' : '演示双击编辑' }}</button>
            <ol v-if="expanded"><li>双击正文进入编辑</li><li>修改后保存，继续阅读</li><li>按 Esc 可选择保存退出或不保存退出</li></ol>
          </div>
        </template>
        <template v-else-if="demo.kind === 'notes-layout'">
          <div class="mini-toolbar">
            <b>小记</b
            ><button type="button" :aria-expanded="expanded" @click="expanded = !expanded">
              记录 <i class="ri-layout-right-line" aria-hidden="true"></i>
            </button>
          </div>
          <div class="mini-notes">
            <div>
              <b>今天的小想法</b>
              <p>把编辑空间留给正文。</p>
            </div>
            <aside v-if="expanded">
              <b>记录区</b>
              <p>已有记录集中在这里。</p>
              <span>拖动边界可调整编辑宽度</span>
            </aside>
          </div>
        </template>
      </div>
    </template>
  </div>
</template>
<style scoped>
.feature-demo {
  border-radius: 14px;
  overflow: hidden;
  background:
    radial-gradient(ellipse at top left, #292542, transparent 75%),
    radial-gradient(ellipse at bottom right, #193b30, transparent 70%), #111613;
  color: #f8f8fc;
}
.demo-bar {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 12px 14px;
  border-bottom: 1px solid #ffffff14;
  font-size: 12px;
}
.mark {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  background: #7c67f3;
  border-radius: 7px;
  font-weight: 700;
}
small {
  margin-left: auto;
  font-size: 11px;
  color: #c5baf6;
}
.scene {
  padding: 16px;
}
.mini-arrival {
  background: linear-gradient(110deg, #4b58dc, #8a71dc);
  text-align: center;
  font-size: 12px;
  padding: 9px;
  border-radius: 10px 10px 0 0;
}
.mini-content {
  padding: 16px;
  background: #fff;
  color: #27304a;
  border-radius: 0 0 10px 10px;
}
b {
  font-size: 13px;
}
p,
li,
aside span {
  font-size: 12px;
  line-height: 1.8;
}
p {
  color: #6d7690;
  margin: 8px 0 12px;
}
ol {
  margin: 12px 0 0;
  padding-left: 19px;
}
button {
  border: 0;
  border-radius: 8px;
  padding: 8px 12px;
  background: #6b59e4;
  color: #fff;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
button:focus-visible {
  outline: 2px solid #ad9aff;
  outline-offset: 3px;
}
.mini-path {
  font-size: 12px;
  color: #b4e9cf;
  margin-bottom: 12px;
}
.mini-path + .mini-content {
  border-radius: 10px;
}
.mini-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  background: #f2f1fd;
  color: #2c3350;
  padding: 10px 12px;
  border-radius: 10px 10px 0 0;
}
.mini-notes {
  display: flex;
  background: #fff;
  color: #283049;
  border-radius: 0 0 10px 10px;
  min-height: 120px;
}
.mini-notes > div {
  flex: 1;
  padding: 16px;
  min-width: 0;
}
aside {
  flex: 1;
  min-width: 0;
  padding: 12px;
  border-left: 2px solid #aea0f7;
  background: #f8f7ff;
}
aside span {
  color: #6d7690;
}
img {
  display: block;
  width: 100%;
  height: auto;
}
.image-error {
  padding: 16px;
}
.image-error p {
  color: #e2e1ed;
}
@media (pointer: coarse) {
  button {
    min-height: 44px;
  }
}
</style>
