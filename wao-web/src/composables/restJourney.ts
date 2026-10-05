import { ref } from 'vue'
import { rememberHomeJourney } from './homeJourneyExperience'

/** 当前页面生命周期内的一次休息入场；刷新与直接访问均不重播。 */
export const restJourneyRequested = ref(false)
export const restArrivalSerial = ref(0)
export const restSelectionSerial = ref(0)

export function requestRestJourney() {
  if (restJourneyRequested.value) return
  restJourneyRequested.value = true
}

export function completeRestJourney(guided: boolean, arrived = false) {
  restJourneyRequested.value = false
  if (guided || arrived) rememberHomeJourney('rest')
  if (guided) restArrivalSerial.value++
  else if (arrived) restSelectionSerial.value++
}
