import { ref } from 'vue'

type JourneyKind = 'rest' | 'writing'
const key = 'wao-home-journey-experience-v1'
function read(): Record<JourneyKind, boolean> {
  try {
    const value = JSON.parse(localStorage.getItem(key) ?? '{}')
    return { rest: value?.rest === true, writing: value?.writing === true }
  } catch { return { rest: false, writing: false } }
}
/** 仅记录此浏览器是否成功体验过入场，不含账号内容或练习记录。 */
export const homeJourneyExperience = ref(read())
export function rememberHomeJourney(kind: JourneyKind) {
  homeJourneyExperience.value = { ...homeJourneyExperience.value, [kind]: true }
  try { localStorage.setItem(key, JSON.stringify(homeJourneyExperience.value)) } catch { /* 仍在当前页面生命周期内快进，不阻断写作或休息。 */ }
}
