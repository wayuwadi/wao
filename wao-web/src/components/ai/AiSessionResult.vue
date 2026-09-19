<script setup lang="ts">
import { computed } from 'vue'
import type { AiRun } from '@/types'
import { renderMarkdown } from '@/utils/markdown'
import AiRunDeliverySteps from './AiRunDeliverySteps.vue'
const props = defineProps<{ run: AiRun }>()
const emit = defineEmits<{ continue: []; newSession: [] }>()
const terminal = computed(() => ['succeeded', 'failed', 'cancelled'].includes(props.run.status))
const resumable = computed(() => !!(props.run.sessionId && props.run.runnerId))
const unsupportedModel = computed(() => props.run.status === 'failed' && /model is not supported when using Codex with a ChatGPT account/i.test(props.run.resultSummary || ''))
const failureReason = computed(() => props.run.failureLabel || (unsupportedModel.value
  ? '本轮结果显示：执行账号不支持所选模型。'
  : '记录未提供结构化失败原因，请结合结果原文与执行过程核对。'))
const heading = computed(() => props.run.status === 'failed' ? '本轮执行遇到问题'
  : props.run.status === 'cancelled' ? '本轮已暂停' : '本轮执行结束')
const continuation = computed(() => props.run.continuationPending
  ? '补充要求已保存，正在等待原执行电脑接收，请勿重复发送。'
  : props.run.status === 'failed'
    ? '这条记录当前没有正在执行的状态。是否另有自动重试运行，请查看会话列表；本页不能据此确认。'
    : '本轮执行已停止。你可以查看结果，再决定是否补充要求。')
</script>

<template>
  <section v-if="terminal" class="session-result" aria-label="本轮结果与下一步">
    <h3>{{ heading }}</h3>
    <p v-if="run.status === 'succeeded'">执行结束不等于你已验收。请对照初始要求检查结果；本页没有用户验收记录。</p>
    <p v-else-if="run.status === 'failed'">{{ failureReason }}</p>
    <p v-else>已保留的结果仍可查看，暂停不代表任务已完成。</p>
    <AiRunDeliverySteps v-if="run.deliveryStage" :stage="run.deliveryStage" />
    <p class="continuation">{{ continuation }}</p>
    <div class="result-materials">
      <h4>已保存材料</h4>
      <a v-if="run.reportUrl" :href="run.reportUrl" target="_blank" rel="noopener noreferrer">打开验收报告 ↗</a>
      <p v-else>本轮没有报告链接。</p>
      <details v-if="run.resultSummary">
        <summary>结果原文 · AI 执行记录</summary>
        <div class="markdown result-summary" v-html="renderMarkdown(run.resultSummary)"></div>
      </details>
      <p v-else>本轮没有结果摘要。下方保留已有对话和执行过程；无法据此确认哪些工作已完成。</p>
    </div>
    <h4>下一步</h4>
    <p v-if="unsupportedModel">先检查 AI 账号与工作流所用模型，再决定是否继续；仅重发要求可能再次失败。 <a href="/settings?tab=ai-accounts">检查 AI 账号</a></p>
    <p>{{ resumable ? '先核对结果；需要补充或修正时，可在原会话说明要求。发送后会继续执行。' : '此运行没有可恢复的会话绑定。先保留有用结果，再新建会话说明目标与已完成内容。' }}</p>
    <div class="result-actions">
      <button v-if="resumable" type="button" :disabled="run.continuationPending" @click="emit('continue')">{{ run.continuationPending ? '等待接收补充要求' : '补充或修正要求' }}</button>
      <button v-else type="button" @click="emit('newSession')">新建会话继续处理</button>
      <small>不会自动确认验收或重新执行。</small>
    </div>
  </section>
</template>

<style scoped>
.session-result { margin-bottom: 20px; border: 1px solid var(--wao-line); border-radius: 12px; padding: 16px; background: var(--wao-bg-card); color: var(--wao-text); }
h3 { font-size: 16px; margin: 0 0 8px; }
h4 { font-size: 13px; margin: 14px 0 6px; }
p, small { font-size: 12px; line-height: 1.7; color: var(--wao-text-weak); margin: 6px 0; }
.continuation { padding: 8px 10px; background: var(--wao-bg-sub); border-radius: 8px; }
a { color: var(--wao-primary-text); font-size: 13px; }
details { margin-top: 10px; }
summary { cursor: pointer; font-size: 12px; }
.result-summary { max-height: 320px; overflow: auto; overflow-wrap: anywhere; margin-top: 8px; }
.result-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
button { border: 0; padding: 8px 12px; border-radius: 8px; background: var(--wao-primary); color: #fff; cursor: pointer; }
button:disabled { opacity: .55; cursor: default; }
</style>
