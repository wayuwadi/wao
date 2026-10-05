import { ref } from 'vue'
import { rememberHomeJourney } from './homeJourneyExperience'
import { restJourneyRequested } from './restJourney'

export const writingJourneys = [
  {
    id: 'room',
    name: '伙伴的秘密写作室',
    scene: 'wj-room',
    lines: ['等等，我们给你腾个地方。', '纸铺好了，笔也借给你。', '好了，先写一句就很好。'],
  },
  {
    id: 'book',
    name: '把首页翻成一本书',
    scene: 'wj-book-scene',
    lines: ['这里，还藏着空白的一页。', '来，跟我们翻进去。', '这一页，等你来写。'],
  },
  {
    id: 'chase',
    name: '抓住那个小念头',
    scene: 'wj-chase-scene',
    lines: ['诶，刚刚是不是想到什么了？', '别跑——我们接住你！', '抓住了，趁现在写下来。'],
  },
] as const

export type WritingJourney = (typeof writingJourneys)[number]
export const writingJourneyRequested = ref<WritingJourney | null>(null)
/** 只在显式首页入场后交给小记，普通访问和刷新不抢焦点。 */
export const writingFocusPending = ref(0)
let last = -1
let serial = 0

export function requestWritingJourney() {
  if (writingJourneyRequested.value || restJourneyRequested.value) return
  const candidates = writingJourneys.filter((_, index) => index !== last)
  const chosen = candidates[Math.floor(Math.random() * candidates.length)]!
  last = writingJourneys.indexOf(chosen)
  writingJourneyRequested.value = chosen
}

export function completeWritingJourney(arrived = false) {
  if (arrived) rememberHomeJourney('writing')
  writingJourneyRequested.value = null
}
export function requestWritingFocus() {
  writingFocusPending.value = ++serial
}
